package com.saveory.frontwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import com.saveory.frontwidget.proton.calendar.ProtonEventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Drives the events auto-cycle. A self-rescheduling alarm advances the shown-event index (the same
 * [FrontWidget.EVENTS_INDEX_KEY] Glance-state value the ‹ / › arrows set), so cycling and manual
 * navigation share one deterministic tracker.
 *
 * The timer "resets on tap" for free: an arrow tap re-renders the widget, [FrontWidget.provideGlance]
 * calls [schedule] on every render, and since we always re-arm at now + [INTERVAL_MS] the countdown
 * starts over from the moment of the tap.
 *
 * Caveat: unlike the launcher's old ViewFlipper (which ran in the always-alive launcher process),
 * this runs off AlarmManager, which the OS throttles in the background — so the real interval is
 * close to [INTERVAL_MS] while the phone is in use and can stretch when it's been idle/Dozing.
 */
object WidgetCycle {
    const val ACTION = "com.saveory.frontwidget.action.CYCLE"
    private const val INTERVAL_MS = 4_000L

    // Battery: the flip is only visible while the screen is on, so there's no point waking up to
    // recompose every 4s with the display off. When non-interactive we stop advancing and re-arm at
    // this coarse cadence purely as a self-healing check that resumes the fast flip once the screen
    // comes back (a render — e.g. the launcher redrawing the widget — also re-arms via schedule()).
    private const val IDLE_INTERVAL_MS = 10 * 60_000L

    /** True when the display is on; the only time the event flip is actually visible. */
    internal fun isInteractive(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** Arms the next auto-advance (or cancels it when there's nothing to cycle). Called every render,
     *  which is what makes an arrow tap reset the countdown. Uses the fast [INTERVAL_MS] only while
     *  the screen is on; off-screen it falls back to the coarse [IDLE_INTERVAL_MS] safety re-check. */
    fun schedule(context: Context) {
        if (ProtonEventStore.load(context).size <= 1) {
            cancel(context)
            return
        }
        val interval = if (isInteractive(context)) INTERVAL_MS else IDLE_INTERVAL_MS
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + interval,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetCycleReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class WidgetCycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WidgetCycle.ACTION) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val size = ProtonEventStore.load(appContext).size
                // Only advance + recompose when the screen is on (the flip isn't visible otherwise);
                // an off-screen tick falls through to schedule() below, which re-arms the coarse
                // idle check instead of burning battery on an unseen render.
                if (size > 1 && WidgetCycle.isInteractive(appContext)) {
                    val ids = GlanceAppWidgetManager(appContext).getGlanceIds(FrontWidget::class.java)
                    ids.forEach { id ->
                        updateAppWidgetState(appContext, id) { prefs ->
                            val cur = prefs[FrontWidget.EVENTS_INDEX_KEY] ?: 0
                            prefs[FrontWidget.EVENTS_INDEX_KEY] = (cur + 1) % size
                        }
                    }
                    FrontWidget().updateAll(appContext)
                }
            } catch (_: Exception) {
            } finally {
                // provideGlance also re-arms on the render above, but re-arm here too so a dropped
                // render never leaves the cycle stalled.
                WidgetCycle.schedule(appContext)
                pending.finish()
            }
        }
    }
}
