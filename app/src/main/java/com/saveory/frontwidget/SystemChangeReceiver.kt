package com.saveory.frontwidget

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Keeps the widget's alarm line (and any timezone-derived text) in sync with the system in real
 * time. The alarm label is computed from [AlarmManager.getNextAlarmClock] during a render, so
 * without this the widget only picked up a newly set/removed alarm the next time some UNRELATED
 * refresh happened (weather/events/timer), which is why setting an alarm didn't show up promptly.
 *
 * All four actions below are on the platform's allow-list of implicit broadcasts that manifest
 * receivers may still receive on API 26+ (see the "Implicit broadcast exceptions" doc), so a static
 * manifest registration is sufficient and the widget updates even when our process was idle:
 *  - NEXT_ALARM_CLOCK_CHANGED: the next alarm was added/removed/changed -> re-read it.
 *  - TIME_SET / TIMEZONE_CHANGED: the wall clock or zone moved -> the "EEE h:mm a" alarm text (and
 *    any zone-formatted text) must be recomputed.
 *  - LOCALE_CHANGED: day/AM-PM formatting is locale-sensitive.
 */
class SystemChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED -> {
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
        }
    }
}
