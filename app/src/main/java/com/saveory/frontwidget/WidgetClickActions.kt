package com.saveory.frontwidget

import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.saveory.frontwidget.proton.calendar.ProtonEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Glance's actionStartActivity wraps intents in PendingIntents and may attach a
 * glance-action: data URI. That breaks implicit intents (e.g. SHOW_ALARMS) on
 * Android 14+. Running from ActionCallback starts the real intent from our process.
 */
class OpenCalendarAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        context.startActivity(WidgetIntents.calendar(context))
    }
}

class OpenAlarmAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        context.startActivity(WidgetIntents.alarms(context))
    }
}

/**
 * Opens the app (landing + settings screen). Wired to the invisible filler below the events so the
 * empty area of the widget is a single-tap shortcut into the app, where sign-in and all settings
 * (background, calendar target, weather source, event window) live.
 */
class OpenAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

class OpenMapsAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        context.startActivity(WidgetIntents.maps(context))
    }
}

class OpenWeatherSearchAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        context.startActivity(WidgetIntents.weatherSearch(context))
    }
}

/** Opens the currently displayed ("active") Proton event directly in the Proton Calendar app. */
class OpenEventAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val eventId = prefs.getString("active_event_id", "") ?: ""
        val startMillis = prefs.getLong("active_event_start", 0L)
        context.startActivity(WidgetIntents.protonEvent(context, eventId, startMillis))
    }
}

/**
 * Opens the tapped event directly in Proton Calendar. Each flipper frame carries its own
 * PendingIntent with that event's id ([EXTRA_EVENT_ID]) — and its start time ([EXTRA_START]) as a
 * day-view fallback — so the correct event opens regardless of how far the flipper auto-advanced.
 */
class EventClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: ""
        val startMillis = intent.getLongExtra(EXTRA_START, 0L)
        context.startActivity(WidgetIntents.protonEvent(context, eventId, startMillis))
    }

    companion object {
        const val ACTION = "com.saveory.frontwidget.action.OPEN_EVENT"
        const val EXTRA_START = "event_start"
        const val EXTRA_EVENT_ID = "event_id"
    }
}

/**
 * Handles the events ‹ / › arrow taps. Each visible frame carries its own arrows with an absolute
 * [EXTRA_TARGET] index (see FrontWidget.eventFrameRemoteViews), so navigation is deterministic and
 * wraps correctly (first ‹ -> last, last › -> first). We write that target into the widget's Glance
 * state via updateAppWidgetState — the composition subscribes to it (currentState), so the change
 * reliably recomposes and the flipper jumps to exactly that event. A plain updateAll() does NOT:
 * Glance coalesces back-to-back updateAll calls and drops every other one, which made arrows stick.
 */
class EventNavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_GO) return
        val target = intent.getIntExtra(EXTRA_TARGET, -1)
        if (target < 0) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val size = ProtonEventStore.load(appContext).size
                if (size > 0) {
                    val safe = ((target % size) + size) % size
                    val manager = GlanceAppWidgetManager(appContext)
                    val ids = manager.getGlanceIds(FrontWidget::class.java)
                    ids.forEach { id ->
                        updateAppWidgetState(appContext, id) { prefs ->
                            prefs[FrontWidget.EVENTS_INDEX_KEY] = safe
                        }
                    }
                    // Restart the auto-cycle countdown from now so a manual jump isn't immediately
                    // overridden by an already-pending advance (explicit, ahead of the render's own
                    // re-arm, to win any race with a cycle alarm about to fire).
                    WidgetCycle.schedule(appContext)
                    FrontWidget().updateAll(appContext)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_GO = "com.saveory.frontwidget.action.EVENTS_GO"
        const val EXTRA_TARGET = "events_target"
    }
}

/**
 * Fired by an exact alarm at a running timer's finish instant: re-renders the widget so it flips
 * from the live countdown to the ended state (hourglass + restart + dismiss). Needed because the
 * NotificationListener's in-process update can be dropped when Clock's full-screen ring backgrounds
 * us at exactly that moment. The ended-vs-active choice is finish-vs-now, so no extra state is read.
 */
class TimerExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FrontWidget.forceRefresh(appContext)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.saveory.frontwidget.action.TIMER_EXPIRY"
    }
}

/**
 * The refresh control on an ended timer: starts a new system timer of the same length. Uses a real
 * intent from our process (SET_TIMER, SKIP_UI) rather than actionStartActivity, which mangles
 * implicit intents on Android 14+ (see OpenAlarmAction).
 */
class RestartTimerAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val totalMs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            .getLong(TimerListenerService.KEY_TIMER_TOTAL, 0L)
        if (totalMs <= 0L) return
        try {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, ((totalMs + 500L) / 1000L).toInt())
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}

/**
 * The X on an ended timer: removes it from the widget. Latches a "dismissed" flag so the listener
 * won't re-show the ended state while the Clock app's firing notification is still up; the latch is
 * cleared automatically once that notification goes away (see TimerListenerService.refresh).
 */
class TimerDismissAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
            .putLong(TimerListenerService.KEY_TIMER_FINISH, 0L)
            .putLong(TimerListenerService.KEY_TIMER_TOTAL, 0L)
            .putString(TimerListenerService.KEY_TIMER_PHASE, TimerListenerService.PHASE_NONE)
            .putBoolean(TimerListenerService.KEY_TIMER_DISMISSED, true)
            .apply()
        FrontWidget.forceRefresh(context)
    }
}

object WidgetIntents {
    private const val PROTON_CALENDAR = "me.proton.android.calendar"

    /**
     * True when the user chose the Proton Calendar integration for calendar/event taps. Otherwise
     * everything routes to the device's default apps. Read live at tap time (no widget refresh
     * needed) from the same prefs the settings screen writes.
     */
    private fun useProtonCalendar(context: Context): Boolean =
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            .getString(FrontWidget.KEY_CALENDAR_TARGET, FrontWidget.DEFAULT_CALENDAR_TARGET) ==
            FrontWidget.CALENDAR_TARGET_PROTON

    /**
     * Opens a specific Proton Calendar event's details sheet via Proton Calendar's own deep link
     * (proton-calendar://protonmail.com/event/details?eventId=..&occurrenceNumber=..), which is the
     * scheme its nav graph / Navigation.Deeplink.toEventDetails registers for EventDetailsFragment.
     * This shows the real event, unlike the time/epoch VIEW intent — that only opens the day and
     * Proton treats it as "new event at this date", which looked like a blank event on tap.
     *
     * occurrenceNumber is left at 0 (the app's own default): we don't track per-occurrence indices,
     * so a recurring series opens at its first occurrence rather than a blank form. Falls back to
     * the day view at [startMillis] when the id is missing or Proton Calendar can't handle the link.
     */
    fun protonEvent(context: Context, eventId: String, startMillis: Long): Intent {
        if (useProtonCalendar(context) && eventId.isNotBlank() && isPackageInstalled(context, PROTON_CALENDAR)) {
            val uri = Uri.Builder()
                .scheme("proton-calendar")
                .authority("protonmail.com")
                .appendPath("event")
                .appendPath("details")
                .appendQueryParameter("eventId", eventId)
                .appendQueryParameter("occurrenceNumber", "0")
                .build()
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage(PROTON_CALENDAR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        return calendarEventAtTime(context, startMillis)
    }

    /**
     * Opens the calendar app at a specific day/time. Proton Calendar registers a VIEW filter for
     * content://com.android.calendar/time/<epochMillis> with MIME type "time/epoch" (verified via
     * its manifest), so we target that explicitly and prefer the Proton app when installed. This
     * avoids the "Invalid link to the event" error caused by untyped content deep links.
     */
    fun calendarEventAtTime(context: Context, startMillis: Long): Intent {
        val millis = if (startMillis > 0) startMillis else System.currentTimeMillis()
        val uri = Uri.parse("content://com.android.calendar/time/$millis")
        val typed = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "time/epoch")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (useProtonCalendar(context) && isPackageInstalled(context, PROTON_CALENDAR)) {
            val proton = Intent(typed).setPackage(PROTON_CALENDAR)
            if (proton.resolveActivity(context.packageManager) != null) return proton
        }
        // Otherwise let the device's default calendar app handle the day/time view.
        if (typed.resolveActivity(context.packageManager) != null) return typed

        // Fallback: open whatever calendar app handles the day view.
        return calendar(context)
    }

    fun calendar(context: Context): Intent {
        // When the Proton integration is selected, open the Proton Calendar app directly.
        if (useProtonCalendar(context) && isPackageInstalled(context, PROTON_CALENDAR)) {
            context.packageManager.getLaunchIntentForPackage(PROTON_CALENDAR)?.let {
                return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        // Open the default calendar app's main view via the APP_CALENDAR category.
        // We intentionally avoid the Google-style content://.../time/<millis> VIEW URI:
        // some calendars (e.g. Proton) treat it as an event deep link and reject it
        // with "Invalid link to the event".
        val mainCalendar = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_CALENDAR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (mainCalendar.resolveActivity(context.packageManager) != null) {
            return mainCalendar
        }

        // Fallback: standard "view at time" intent (Google Calendar / AOSP).
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()
        return Intent(Intent.ACTION_VIEW)
            .setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun timers(context: Context): Intent {
        // Opens the Clock app's Timers tab (SHOW_TIMERS, API 26+) so tapping a running countdown
        // jumps straight to it. Falls back to launching a known clock app, then to SHOW_ALARMS.
        val showTimers = Intent(AlarmClock.ACTION_SHOW_TIMERS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val resolved = showTimers.resolveActivity(context.packageManager)
        if (resolved != null) {
            return showTimers.setComponent(resolved)
        }

        val commonClocks = arrayOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage"
        )
        for (pkg in commonClocks) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return alarms(context)
    }

    fun alarms(context: Context): Intent {
        val showAlarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val resolved = showAlarms.resolveActivity(context.packageManager)
        if (resolved != null) {
            return showAlarms.setComponent(resolved)
        }

        val commonClocks = arrayOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage"
        )
        for (pkg in commonClocks) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return showAlarms
    }

    fun maps(context: Context): Intent {
        // Just open the default maps app (no pin, no search).
        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val resolved = geoIntent.resolveActivity(context.packageManager)
        if (resolved != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(resolved.packageName)
            if (launch != null) {
                return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return geoIntent.setComponent(resolved)
        }
        return geoIntent
    }

    fun weatherSearch(context: Context): Intent {
        // Search just "weather"; the search app resolves the location itself. Route to whatever
        // app handles WEB_SEARCH by default (assistant/search/browser) rather than forcing Google.
        val query = "weather"

        val webSearch = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (webSearch.resolveActivity(context.packageManager) != null) {
            return webSearch
        }

        // Last resort: open a weather search in the default browser.
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
