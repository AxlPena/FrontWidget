package com.saveory.frontwidget

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android exposes no public API for the system clock's countdown timers, so the only way to show a
 * live timer in the widget is to read the Clock app's ongoing "timer running" notification. This
 * NotificationListenerService watches Google Clock's timer notifications, derives the finish time,
 * and stores it in widget_prefs; the widget then renders a self-ticking Chronometer counting down
 * to it. Requires the user to grant Notification Access (BIND_NOTIFICATION_LISTENER_SERVICE).
 */
class TimerListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        refresh()
        // On (re)connect after a process restart, force the widget to match the stored state - an
        // in-flight update may have been lost if the process died mid-render.
        scope.launch {
            try {
                FrontWidget.forceRefresh(applicationContext)
            } catch (_: Exception) {
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    /**
     * Recompute the timer phase from the current notifications and publish it. Three phases:
     *  - active:  a "Timers" notification is counting down -> store its finish + track duration.
     *  - expired: the countdown ended and a "Firing" notification is up (Stop / Add 1 min) -> keep
     *             the finish so the widget shows the ended state with refresh + dismiss controls.
     *             Only entered if we were already tracking a timer, so a firing *alarm* won't trigger it.
     *  - none:    no timer at all.
     */
    private fun refresh() {
        val now = System.currentTimeMillis()
        val running = try {
            activeTimerFinishMs()
        } catch (e: Exception) {
            Log.w(TAG, "timer scan failed", e); 0L
        }
        val firing = try {
            hasFiringTimerNotif()
        } catch (e: Exception) {
            Log.w(TAG, "firing scan failed", e); false
        }

        val prefs = getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val prevFinish = prefs.getLong(KEY_TIMER_FINISH, 0L)
        val prevTotal = prefs.getLong(KEY_TIMER_TOTAL, 0L)
        val prevPhase = prefs.getString(KEY_TIMER_PHASE, PHASE_NONE) ?: PHASE_NONE
        val dismissed = prefs.getBoolean(KEY_TIMER_DISMISSED, false)
        val hadTimer = prevPhase != PHASE_NONE || prevFinish > 0L

        // The Clock app's "Firing" notification is short-lived on some devices, so expiry is detected
        // from the known finish instead: when the running countdown disappears at/near its finish it
        // expired; if it vanished well before, it was cancelled. The ended state then persists until
        // the user dismisses it (X) or a new timer starts.
        val phase = when {
            running > 0 -> PHASE_ACTIVE
            dismissed -> PHASE_NONE
            prevPhase == PHASE_EXPIRED -> PHASE_EXPIRED
            prevPhase == PHASE_ACTIVE && prevFinish > 0L && prevFinish - now <= END_GRACE_MS -> PHASE_EXPIRED
            firing && hadTimer -> PHASE_EXPIRED
            else -> PHASE_NONE
        }

        var finish = prevFinish
        var total = prevTotal
        var newDismissed = dismissed
        when (phase) {
            PHASE_ACTIVE -> {
                finish = running
                // Reset the tracked duration for a new timer; keep the max within one run so we
                // capture the original length even if we first saw it a beat after it started.
                val newRun = prevPhase != PHASE_ACTIVE || kotlin.math.abs(running - prevFinish) > 2000L
                total = if (newRun) running - now else maxOf(prevTotal, running - now)
                newDismissed = false
            }
            PHASE_EXPIRED -> {
                // Clamp finish to now so the widget immediately sees it as past and renders the ended
                // state on this very update (expiry can be committed a moment before the real finish).
                finish = if (prevFinish > 0L) minOf(prevFinish, now) else now
            }
            else -> {
                finish = 0L
                total = 0L
            }
        }

        val phaseChanged = phase != prevPhase
        val finishDrift = phase == PHASE_ACTIVE && kotlin.math.abs(finish - prevFinish) > 1500L
        val metaChanged = total != prevTotal || newDismissed != dismissed
        if (phaseChanged || finishDrift || metaChanged) {
            prefs.edit()
                .putLong(KEY_TIMER_FINISH, finish)
                .putLong(KEY_TIMER_TOTAL, total)
                .putString(KEY_TIMER_PHASE, phase)
                .putBoolean(KEY_TIMER_DISMISSED, newDismissed)
                .apply()
            // While active, arm an exact one-shot at the finish instant so the ended state renders
            // even if this process is frozen when the timer fires (Clock's full-screen ring can
            // background us, dropping an in-process updateAll). Keep it armed through the EXPIRED
            // transition - that pending alarm IS what reliably paints the ended UI - and only drop
            // it when the timer goes away entirely (cancelled or dismissed).
            when (phase) {
                PHASE_ACTIVE -> scheduleExpiryUpdate(finish)
                PHASE_NONE -> cancelExpiryUpdate()
                else -> Unit
            }
            scope.launch {
                try {
                    FrontWidget.forceRefresh(applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "widget update failed", e)
                }
            }
        }
    }

    private fun expiryPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, TimerExpiryReceiver::class.java)
            .setAction(TimerExpiryReceiver.ACTION)
        return PendingIntent.getBroadcast(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleExpiryUpdate(finishMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = finishMs + 300L
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (!exact) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, expiryPendingIntent())
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, expiryPendingIntent())
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, expiryPendingIntent())
        }
    }

    private fun cancelExpiryUpdate() {
        (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(expiryPendingIntent())
    }

    /** True if Google Clock has a "Firing" notification up (an expired timer or alarm going off). */
    private fun hasFiringTimerNotif(): Boolean {
        val active = activeNotifications ?: return false
        return active.any { it.packageName == CLOCK_PKG && it.notification?.channelId == FIRING_CHANNEL }
    }

    /** The SOONEST future finish among running Clock timers (0 if none) - that's the one about to fire. */
    private fun activeTimerFinishMs(): Long {
        val active = activeNotifications ?: return 0L
        val now = System.currentTimeMillis()
        var soonest = Long.MAX_VALUE
        for (sbn in active) {
            if (sbn.packageName != CLOCK_PKG) continue
            val finish = timerFinish(sbn.notification ?: continue) ?: continue
            if (finish > now && finish < soonest) soonest = finish
        }
        return if (soonest == Long.MAX_VALUE) 0L else soonest
    }

    /** Best-effort extraction of a running timer's finish time (epoch millis) from a notification. */
    private fun timerFinish(n: Notification): Long? {
        val now = System.currentTimeMillis()
        val extras = n.extras

        // 1) Standard countdown chronometer template: `when` is the finish instant.
        if (extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true && n.`when` > now) {
            return n.`when`
        }
        // 2) A remaining "H:MM:SS"/"MM:SS" in a standard text field.
        for (key in arrayOf(Notification.EXTRA_TEXT, Notification.EXTRA_TITLE, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)) {
            val t = extras?.getCharSequence(key)?.toString() ?: continue
            parseRemaining(t)?.let { return now + it }
        }
        // 3) Google Clock puts the countdown only in its custom notification view. Inflate that
        //    RemoteViews and read the Chronometer's finish (public getBase/isCountDown), or parse a
        //    MM:SS TextView as a fallback.
        return finishFromCustomView(n)
    }

    private fun finishFromCustomView(n: Notification): Long? {
        val rv = n.bigContentView ?: n.contentView ?: return null
        return try {
            val root = rv.apply(applicationContext, FrameLayout(applicationContext))
            findChronometerFinish(root)?.let { return it }
            findTimeText(root)?.let { parseRemaining(it)?.let { r -> return System.currentTimeMillis() + r } }
            null
        } catch (e: Exception) {
            Log.w(TAG, "custom view inflate failed", e)
            null
        }
    }

    private fun findChronometerFinish(v: View): Long? {
        if (v is Chronometer && v.isCountDown) {
            val remaining = v.base - SystemClock.elapsedRealtime()
            if (remaining > 0) return System.currentTimeMillis() + remaining
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findChronometerFinish(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findTimeText(v: View): String? {
        if (v is Chronometer) return null // handled above; its text isn't the remaining string
        if (v is TextView) {
            val t = v.text?.toString()
            if (t != null && parseRemaining(t) != null) return t
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findTimeText(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Parses a "H:MM:SS" / "MM:SS" / "M:SS" duration string into millis, or null. */
    private fun parseRemaining(text: String): Long? {
        val m = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})").find(text) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val mm = m.groupValues[2].toLongOrNull() ?: return null
        val ss = m.groupValues[3].toLongOrNull() ?: return null
        val total = ((h * 3600) + (mm * 60) + ss) * 1000L
        return if (total in 1_000L..24L * 3600_000L) total else null
    }

    companion object {
        const val TAG = "TimerListener"
        const val CLOCK_PKG = "com.google.android.deskclock"
        const val FIRING_CHANNEL = "Firing"
        // A running countdown that disappears within this window of its finish is treated as expired
        // (vs. cancelled), absorbing listener latency around the finish instant.
        const val END_GRACE_MS = 3000L
        const val KEY_TIMER_FINISH = "timer_finish_ms"
        const val KEY_TIMER_TOTAL = "timer_total_ms"
        const val KEY_TIMER_PHASE = "timer_phase"
        const val KEY_TIMER_DISMISSED = "timer_dismissed"
        const val PHASE_NONE = "none"
        const val PHASE_ACTIVE = "active"
        const val PHASE_EXPIRED = "expired"
    }
}
