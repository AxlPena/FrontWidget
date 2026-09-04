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
    // Pass the removed notification's key so the rescan ignores it: on some OEMs (incl. this Sony
    // build) getActiveNotifications() can still momentarily include the notification that was just
    // removed, which made a stopped timer look like it was still running - so the widget never
    // cleared. Excluding it makes "stop the timer outside the widget" reliably hide it.
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh(sbn?.key)

    /**
     * Recompute the timer phase from the current notifications and publish it. Three phases:
     *  - active:  a "Timers" notification is counting down -> store its finish + track duration.
     *  - expired: the countdown ended and a "Firing" notification is up (Stop / Add 1 min) -> keep
     *             the finish so the widget shows the ended state with refresh + dismiss controls.
     *             Only entered if we were already tracking a timer, so a firing *alarm* won't trigger it.
     *  - none:    no timer at all.
     */
    private fun refresh(removedKey: String? = null) {
        val now = System.currentTimeMillis()
        val running = try {
            activeTimerFinishMs(removedKey)
        } catch (e: Exception) {
            Log.w(TAG, "timer scan failed", e); 0L
        }
        val firing = try {
            scanFiring(removedKey)
        } catch (e: Exception) {
            Log.w(TAG, "firing scan failed", e); FiringScan(any = false, timer = false)
        }
        // `firing.any`  -> any Clock "Firing" notification is up (timer OR alarm).
        // `firing.timer`-> at least one of them is classified as a TIMER firing (not an alarm).
        // The ended state keys off `firing.timer`, so a ringing ALARM can never paint the ended
        // timer even while we were tracking a recent timer (the reported bug).
        val firingAny = firing.any
        val firingTimer = firing.timer

        val prefs = getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val prevFinish = prefs.getLong(KEY_TIMER_FINISH, 0L)
        val prevTotal = prefs.getLong(KEY_TIMER_TOTAL, 0L)
        val prevPhase = prefs.getString(KEY_TIMER_PHASE, PHASE_NONE) ?: PHASE_NONE
        val dismissed = prefs.getBoolean(KEY_TIMER_DISMISSED, false)
        // Whether we're actually tracking a Clock TIMER (running, or already showing its ended
        // state) - the only situation in which a shared-channel "Firing" notification is ours to
        // render as an expired timer. Deliberately NOT "prevFinish > 0" (stale state from a long-
        // gone timer would let a firing ALARM flip the widget into the ended-timer state).
        val trackingTimer = prevPhase == PHASE_ACTIVE || prevPhase == PHASE_EXPIRED

        // The ended ("expired") state is shown ONLY while the Clock is still firing the timer, plus a
        // brief grace window right as a running countdown crosses its finish (to bridge the instant
        // before the firing notification appears, and cover very short-lived firing notifs). Crucially
        // it is NOT sticky: the moment the timer is stopped/dismissed OUTSIDE the widget - which
        // removes Clock's notification - we fall through to NONE and the widget stops showing it,
        // matching the phone. (Stopping a running countdown early likewise removes its notification ->
        // running==0 and not firing -> NONE.) A previously-sticky EXPIRED phase kept the ended row on
        // screen forever after an external stop, which is the reported "timer won't disappear" bug.
        val phase = decidePhase(
            now = now,
            running = running,
            firingAny = firingAny,
            firingTimer = firingTimer,
            prevPhase = prevPhase,
            prevFinish = prevFinish,
            dismissed = dismissed,
        )

        // Diagnosis hook (esp. for OEM Clock builds that format firing notifications differently):
        // shows exactly how a scan resolved, so an alarm-misread-as-timer is visible in logcat.
        Log.d(TAG, "phase: running=$running firingAny=$firingAny firingTimer=$firingTimer prev=$prevPhase tracking=$trackingTimer -> $phase")

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
                // Timer is truly gone (nothing firing at all): drop the dismiss latch so the next
                // timer starts fresh instead of being suppressed by a stale flag.
                if (!firingAny) newDismissed = false
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

    /** Result of scanning Clock's "Firing" notifications: whether ANY is up, and whether any is a TIMER. */
    private data class FiringScan(val any: Boolean, val timer: Boolean)

    /**
     * Scans Google Clock's "Firing" channel (shared by alarms AND timers) and classifies what's
     * ringing. [excludeKey] is a just-removed notification to ignore (see onNotificationRemoved).
     *
     * Google Clock puts firing timers and firing alarms on the same channel with the same
     * `category=alarm`, so the channel alone can't tell them apart. They differ in their action
     * buttons and layout (verified on this build): a firing ALARM offers "Snooze"; a firing TIMER
     * offers "Add 1 min" and renders a custom countdown view. [classifyFiring] encodes that.
     */
    private fun scanFiring(excludeKey: String?): FiringScan {
        val active = activeNotifications ?: return FiringScan(any = false, timer = false)
        var any = false
        var timer = false
        for (sbn in active) {
            if (sbn.packageName != CLOCK_PKG) continue
            if (sbn.key == excludeKey) continue
            val n = sbn.notification ?: continue
            if (n.channelId != FIRING_CHANNEL) continue
            any = true
            val kind = firingKindOf(n)
            if (kind == FiringKind.TIMER) timer = true
            Log.d(TAG, "firing notif: kind=$kind actions=${actionTitlesOf(n)} customView=${hasCustomView(n)}")
        }
        return FiringScan(any = any, timer = timer)
    }

    private fun hasCustomView(n: Notification): Boolean =
        n.contentView != null || n.bigContentView != null || n.headsUpContentView != null

    private fun actionTitlesOf(n: Notification): List<String> =
        n.actions?.mapNotNull { it?.title?.toString() } ?: emptyList()

    /** Classifies a firing Clock notification as a timer or an alarm from its layout + actions. */
    private fun firingKindOf(n: Notification): FiringKind =
        classifyFiring(hasCustomView(n), actionTitlesOf(n))

    /** The SOONEST future finish among running Clock timers (0 if none) - that's the one about to fire.
     *  [excludeKey] is the key of a just-removed notification to ignore (see onNotificationRemoved). */
    private fun activeTimerFinishMs(excludeKey: String?): Long {
        val active = activeNotifications ?: return 0L
        val now = System.currentTimeMillis()
        var soonest = Long.MAX_VALUE
        for (sbn in active) {
            if (sbn.packageName != CLOCK_PKG) continue
            if (sbn.key == excludeKey) continue
            val n = sbn.notification ?: continue
            // Skip the group summary: it carries no real countdown (its custom view is null) but can
            // linger after the child timers are gone and, on some builds, expose a stale time string
            // that would be misread as a still-running timer.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            // Never treat a FIRING notification as a running countdown. Clock shares one "Firing"
            // channel for alarms AND timers ("Firing alarms & timers"), and a firing alarm's title
            // is a clock time like "6:12" that parseRemaining() would misread as "6:12 remaining" -
            // showing a phantom timer whenever an alarm goes off. Running timers live on the "Timers"
            // channel; the Firing channel only appears once something has already ended.
            if (n.channelId == FIRING_CHANNEL) continue
            val finish = timerFinish(n) ?: continue
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

    /** What a Clock "Firing" notification represents. UNKNOWN is treated as NOT a timer (safe). */
    enum class FiringKind { TIMER, ALARM, UNKNOWN }

    companion object {
        const val TAG = "TimerListener"
        const val CLOCK_PKG = "com.google.android.deskclock"
        const val FIRING_CHANNEL = "Firing"

        /**
         * Pure classifier for a firing Clock notification, split out so it is unit-testable without
         * a device. Grounded in the real notifications from Google Clock (both share
         * channel="Firing", category="alarm"):
         *   - firing ALARM  -> actions ["Snooze", "Stop"], standard template (no custom view)
         *   - firing TIMER  -> actions ["Stop", "Add 1 min"], custom countdown RemoteView
         *
         * A "Snooze" action is unique to alarms; an add-a-minute action is unique to timers. The
         * custom-view flag is the tiebreaker. Anything we can't place is UNKNOWN, and callers treat
         * UNKNOWN as "not a timer" so a ringing alarm can never be rendered as an ended timer.
         */
        /**
         * Pure phase decision, split out so the alarm-vs-timer race is unit-testable without a
         * device. Priority order:
         *  1. a live countdown -> ACTIVE
         *  2. user dismissed on the widget while anything Clock is still ringing -> stay NONE
         *  3. a TIMER is firing AND we were tracking a timer -> EXPIRED (ended state). Keyed on
         *     [firingTimer], NOT "any firing", so a ringing ALARM can never show the ended timer even
         *     when we were mid-timer (the reported bug).
         *  4. brief grace right as a running countdown crosses its finish -> EXPIRED
         *  5. otherwise NONE.
         */
        internal fun decidePhase(
            now: Long,
            running: Long,
            firingAny: Boolean,
            firingTimer: Boolean,
            prevPhase: String,
            prevFinish: Long,
            dismissed: Boolean,
        ): String {
            val trackingTimer = prevPhase == PHASE_ACTIVE || prevPhase == PHASE_EXPIRED
            return when {
                running > 0 -> PHASE_ACTIVE
                dismissed && firingAny -> PHASE_NONE
                firingTimer && trackingTimer -> PHASE_EXPIRED
                // Brief bridge right as a running countdown crosses its finish, before the firing
                // notification appears. Bounded to |now - finish| <= grace on BOTH sides: a finish
                // only a few seconds away (just before/after) qualifies, but a stale ACTIVE with a
                // long-past finish does NOT (that used to resolve EXPIRED for any past finish, so a
                // cancelled timer / a later alarm could paint a phantom ended timer).
                prevPhase == PHASE_ACTIVE && prevFinish > 0L &&
                    kotlin.math.abs(prevFinish - now) <= END_GRACE_MS -> PHASE_EXPIRED
                else -> PHASE_NONE
            }
        }

        internal fun classifyFiring(hasCustomView: Boolean, actionTitles: List<String>): FiringKind {
            val titles = actionTitles.map { it.trim().lowercase() }
            val hasSnooze = titles.any { it.contains("snooze") }
            val hasAddMinute = titles.any { t ->
                (t.contains("add") && t.contains("min")) ||   // "Add 1 min" / "Add a minute"
                    Regex("""\+\s*\d""").containsMatchIn(t) || // "+1:00", "+ 1"
                    (t.contains("min") && Regex("""\d""").containsMatchIn(t)) // "1 min"
            }
            return when {
                hasSnooze -> FiringKind.ALARM        // alarms snooze; timers never do
                hasAddMinute -> FiringKind.TIMER     // only timers add a minute
                hasCustomView -> FiringKind.TIMER    // timer draws a custom countdown; alarm uses the plain template
                else -> FiringKind.UNKNOWN
            }
        }
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
