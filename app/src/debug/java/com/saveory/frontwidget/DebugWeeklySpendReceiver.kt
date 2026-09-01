package com.saveory.frontwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saveory.frontwidget.data.WeeklySpendRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only (src/debug): push a `weekly_spend` result onto the device the way the phone-side Monarch
 * sync will (see docs/weekly-spend-widget.md). Writes the tool's spent_cents / as_of_ms /
 * pending_included / auth_ok into widget_prefs and forceRefreshes, so the spend ring shows a real
 * number without a live on-device Monarch session yet.
 *
 * Because it is exported (so `adb` can reach it) this receiver is compiled ONLY into debug builds -
 * it must never ship in a release APK, where any installed app could spoof the displayed spend.
 *
 * Target the component explicitly with -n (Android 15+ filters the action-only implicit broadcast):
 *
 *   adb shell "am broadcast -n com.saveory.frontwidget/.DebugWeeklySpendReceiver \
 *     -a com.saveory.frontwidget.action.SET_WEEKLY_SPEND \
 *     --el spent_cents 5684 --ez auth_ok true --ez pending_included true"
 *
 * Defaults: as_of_ms = now, auth_ok = true, pending_included = true.
 *
 * Pass --es sync_url <url> to instead point the on-device sync (WeeklySpendWorker /
 * MonarchWeeklySpendClient) at a bridge endpoint that returns the weekly_spend JSON; an empty
 * string clears it. When sync_url is present the spent_cents/auth_ok extras are ignored.
 */
class DebugWeeklySpendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

        // Debug test hook: fire exactly one real TAP sync (queues a bank refresh + schedules the
        // follow-up), instead of writing a canned number. `--ez reset_refresh true` first clears the
        // debounce so the refresh is guaranteed to queue. Lets us watch the full cycle in logcat
        // without the app-launch double syncNow.
        if (intent.getBooleanExtra("sync_now", false)) {
            if (intent.getBooleanExtra("reset_refresh", false)) {
                prefs.edit().remove(WeeklySpendRepository.KEY_LAST_REFRESH_MS).apply()
            }
            WeeklySpendWorker.syncNow(app)
            return
        }

        if (intent.hasExtra("sync_url")) {
            prefs.edit().putString(WeeklySpendRepository.KEY_SYNC_URL, intent.getStringExtra("sync_url") ?: "").apply()
        } else {
            val nowMs = System.currentTimeMillis()
            prefs.edit()
                .putLong(WeeklySpendRepository.KEY_SPENT_CENTS, intent.getLongExtra("spent_cents", 0L))
                .putLong(WeeklySpendRepository.KEY_AS_OF_MS, intent.getLongExtra("as_of_ms", nowMs))
                .putBoolean(WeeklySpendRepository.KEY_PENDING_INCLUDED, intent.getBooleanExtra("pending_included", true))
                .putBoolean(WeeklySpendRepository.KEY_AUTH_OK, intent.getBooleanExtra("auth_ok", true))
                .apply()
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FrontWidget.forceRefresh(app)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.saveory.frontwidget.action.SET_WEEKLY_SPEND"
    }
}
