package com.saveory.frontwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only: inject an arbitrary weather location so we can eyeball how the clock row's location
 * block renders long city/region names (ellipsis, NYC-block safety) without waiting on a real
 * geocode. Unlike launching MainActivity, a broadcast does NOT trip onAccountReady -> refreshWidget,
 * so the injected values aren't immediately overwritten by a weather fetch.
 *
 *   adb shell "am broadcast -a com.saveory.frontwidget.action.TEST_LOCATION \
 *     --es locality 'SAN FRANCISCO' --es region 'Northern California' --es country US"
 */
class DebugLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val app = context.applicationContext
        app.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
            .putString("weather_locality", intent.getStringExtra("locality") ?: "")
            .putString("weather_region", intent.getStringExtra("region") ?: "")
            .putString("weather_country", intent.getStringExtra("country") ?: "")
            .apply()

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FrontWidget().updateAll(app)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.saveory.frontwidget.action.TEST_LOCATION"
    }
}
