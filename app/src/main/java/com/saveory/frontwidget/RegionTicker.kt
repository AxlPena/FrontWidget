package com.saveory.frontwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Drives the "ticker" region-reveal mode: a self-rescheduling inexact alarm that advances a scroll
 * offset and re-renders the widget, so the long "region, COUNTRY" line appears to scroll. This is
 * only armed while [RegionReveal.PREF_MODE] == [RegionReveal.TICKER]; any other mode cancels it, so
 * the fast tick never runs (and never costs battery) outside this mode.
 *
 * NOTE: This is inherently choppy - widget re-renders are the only way to move text in a widget,
 * and the OS throttles/batches them. It exists so the ticker option can be evaluated against the
 * smoother auto-flip; end-ellipsis remains the default.
 */
object RegionReveal {
    const val PREF_MODE = "region_reveal_mode"
    const val PREF_OFFSET = "region_scroll_offset"
    const val ELLIPSIS = "ellipsis"
    const val TICKER = "ticker"
    const val FLIP = "flip"

    fun mode(context: Context): String =
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            .getString(PREF_MODE, TICKER) ?: TICKER
}

object RegionTicker {
    const val ACTION = "com.saveory.frontwidget.action.REGION_TICK"
    private const val INTERVAL_MS = 1_000L

    /**
     * The ticker now scrolls via a self-advancing ViewFlipper in the launcher process, so no
     * alarm-driven re-render is needed (the OS throttled the 1s tick to ~5s and the host dropped
     * most of the repaints anyway). Always cancel any legacy tick alarm.
     */
    fun scheduleOrCancel(context: Context) {
        cancel(context)
    }

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RegionTickerReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class RegionTickerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RegionTicker.ACTION) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Advance the scroll offset, then re-render so the window shifts one step.
                val prefs = appContext.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                val next = prefs.getInt(RegionReveal.PREF_OFFSET, 0) + 1
                prefs.edit().putInt(RegionReveal.PREF_OFFSET, next).apply()
                FrontWidget().updateAll(appContext)
            } catch (_: Exception) {
            } finally {
                // Keep ticking only while ticker mode is still selected.
                RegionTicker.scheduleOrCancel(appContext)
                pending.finish()
            }
        }
    }
}
