package com.saveory.frontwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.saveory.frontwidget.proton.calendar.ProtonEventStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only (src/debug) inspector for the widget's persisted events. Trigger it with:
 *   adb shell am broadcast -a com.saveory.frontwidget.action.DUMP_EVENTS -n com.saveory.frontwidget/.DumpEventsReceiver
 * then read: adb logcat -s EventDump
 *
 * Logs the raw proton_events_json from widget_prefs plus each parsed event's fields (notably the
 * eventId now used to deep-link into Proton Calendar), so we can verify the store without root.
 *
 * This writes potentially sensitive calendar data (event titles) to logcat and is exported so `adb`
 * can reach it, so it is compiled ONLY into debug builds and never shipped in a release APK.
 */
class DumpEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val (raw, updatedAt) = ProtonEventStore.rawJson(context)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val updatedStr = if (updatedAt > 0L) fmt.format(Date(updatedAt)) else "never"
        Log.d(TAG, "widget_prefs.proton_events_json (updatedAt=$updatedStr):")
        Log.d(TAG, "raw = $raw")

        val events = ProtonEventStore.load(context)
        Log.d(TAG, "parsed ${events.size} event(s):")
        events.forEachIndexed { i, e ->
            Log.d(
                TAG,
                "[$i] eventId=${e.eventId} calendarId=${e.calendarId} fullDay=${e.fullDay} " +
                    "start=${fmt.format(Date(e.startTime))} end=${fmt.format(Date(e.endTime))} " +
                    "title='${e.title}'"
            )
        }
    }

    companion object {
        const val ACTION = "com.saveory.frontwidget.action.DUMP_EVENTS"
        private const val TAG = "EventDump"
    }
}
