package com.saveory.frontwidget

import com.saveory.frontwidget.TimerListenerService.FiringKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the timer-vs-alarm classifier that fixes "when an alarm goes off I still see the timer go
 * off." Google Clock puts firing alarms AND firing timers on one shared "Firing" channel with the
 * same category, so the widget's ended-timer state must key off the notification's shape. The
 * action lists below are the real ones captured from Google Clock on the test device.
 */
class TimerFiringClassifierTest {

    @Test
    fun firingAlarmIsClassifiedAsAlarm() {
        // Real firing alarm: standard template (no custom view), Snooze + Stop.
        val kind = TimerListenerService.classifyFiring(
            hasCustomView = false,
            actionTitles = listOf("Snooze", "Stop"),
        )
        assertEquals(FiringKind.ALARM, kind)
    }

    @Test
    fun firingTimerIsClassifiedAsTimer() {
        // Real firing timer: custom countdown view, Stop + Add 1 min.
        val kind = TimerListenerService.classifyFiring(
            hasCustomView = true,
            actionTitles = listOf("Stop", "Add 1 min"),
        )
        assertEquals(FiringKind.TIMER, kind)
    }

    @Test
    fun snoozeWinsEvenIfClockEverAddsACustomAlarmView() {
        // Defensive: a Snooze action means alarm regardless of layout, so an alarm can never be
        // mistaken for a timer.
        assertEquals(
            FiringKind.ALARM,
            TimerListenerService.classifyFiring(hasCustomView = true, actionTitles = listOf("Snooze", "Dismiss")),
        )
    }

    @Test
    fun addMinuteVariantsClassifyAsTimer() {
        for (label in listOf("Add 1 min", "Add a minute", "+1:00", "+ 1 min")) {
            assertEquals(
                "\"$label\" should read as a timer",
                FiringKind.TIMER,
                TimerListenerService.classifyFiring(hasCustomView = false, actionTitles = listOf("Stop", label)),
            )
        }
    }

    @Test
    fun unrecognizedFiringIsNotTreatedAsTimer() {
        // No snooze, no add-minute, no custom view -> UNKNOWN, which callers treat as NOT a timer.
        assertEquals(
            FiringKind.UNKNOWN,
            TimerListenerService.classifyFiring(hasCustomView = false, actionTitles = listOf("Stop")),
        )
    }

    // --- Phase decision: the exact alarm-vs-timer race -----------------------------------------

    private val now = 1_000_000L

    private fun phase(
        running: Long = 0L,
        firingAny: Boolean = false,
        firingTimer: Boolean = false,
        prevPhase: String = TimerListenerService.PHASE_NONE,
        prevFinish: Long = 0L,
        dismissed: Boolean = false,
    ) = TimerListenerService.decidePhase(now, running, firingAny, firingTimer, prevPhase, prevFinish, dismissed)

    @Test
    fun alarmFiringWhileTrackingRecentTimerDoesNotShowEndedTimer() {
        // THE BUG: a timer just ended (prev=expired, so we're "tracking"), and now ONLY an alarm is
        // ringing (firingAny=true but firingTimer=false). Must resolve to NONE, not EXPIRED.
        val result = phase(
            running = 0L,
            firingAny = true,
            firingTimer = false,
            prevPhase = TimerListenerService.PHASE_EXPIRED,
            prevFinish = now - 60_000L,
        )
        assertEquals(TimerListenerService.PHASE_NONE, result)
    }

    @Test
    fun alarmFiringRightAfterActiveTimerVanishesDoesNotShowEndedTimer() {
        // Same race off the ACTIVE state (timer cancelled/vanished as the alarm starts).
        val result = phase(
            running = 0L,
            firingAny = true,
            firingTimer = false,
            prevPhase = TimerListenerService.PHASE_ACTIVE,
            prevFinish = now - 5_000L, // well past the end-grace window
        )
        assertEquals(TimerListenerService.PHASE_NONE, result)
    }

    @Test
    fun realTimerFiringWhileTrackingShowsEndedTimer() {
        val result = phase(
            running = 0L,
            firingAny = true,
            firingTimer = true,
            prevPhase = TimerListenerService.PHASE_EXPIRED,
            prevFinish = now,
        )
        assertEquals(TimerListenerService.PHASE_EXPIRED, result)
    }

    @Test
    fun runningCountdownAlwaysWins() {
        assertEquals(
            TimerListenerService.PHASE_ACTIVE,
            phase(running = now + 30_000L, firingAny = true, firingTimer = false,
                prevPhase = TimerListenerService.PHASE_EXPIRED),
        )
    }

    @Test
    fun loneAlarmWithNoPriorTimerIsNone() {
        assertEquals(
            TimerListenerService.PHASE_NONE,
            phase(firingAny = true, firingTimer = false, prevPhase = TimerListenerService.PHASE_NONE),
        )
    }
}
