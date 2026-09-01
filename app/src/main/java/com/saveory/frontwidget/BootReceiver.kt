package com.saveory.frontwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms everything that does not survive a reboot.
 *
 * WorkManager restores its own scheduled work after boot, but the [WidgetCycle] auto-cycle runs on
 * an AlarmManager alarm which the OS drops on restart. Without this, the events section would stop
 * rotating (and data would go stale) until the next manual widget update. We also kick a one-off
 * refresh so the widget shows current weather/events right after the device comes back up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val appContext = context.applicationContext
                WeatherWorker.enqueue(appContext, force = true)
                EventsWorker.enqueue(appContext, force = true)
                WeeklySpendWorker.enqueue(appContext, force = true)
                WidgetCycle.schedule(appContext)
            }
        }
    }
}
