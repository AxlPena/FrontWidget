package com.saveory.frontwidget.proton.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A decrypted (or partially decrypted) Proton Calendar event, reduced to what the widget shows.
 *
 * [title] may be a placeholder when decryption of the encrypted part failed but the cleartext
 * timing is still known, so the widget can always at least render "when".
 */
data class ProtonEvent(
    val calendarId: String,
    val eventId: String,
    val uid: String,
    val title: String,
    val startTime: Long,   // epoch millis
    val endTime: Long,     // epoch millis
    val fullDay: Boolean
)

/**
 * Persists the decrypted event window as JSON in SharedPreferences so the widget (a separate
 * Glance render pass) can read it synchronously without touching the network or crypto.
 */
object ProtonEventStore {
    private const val PREFS = "widget_prefs"
    private const val KEY_EVENTS = "proton_events_json"
    private const val KEY_UPDATED = "proton_events_updated_at"
    private const val KEY_INDEX = "proton_events_cycle_index"

    fun save(context: Context, events: List<ProtonEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(
                JSONObject()
                    .put("calendarId", e.calendarId)
                    .put("eventId", e.eventId)
                    .put("uid", e.uid)
                    .put("title", e.title)
                    .put("startTime", e.startTime)
                    .put("endTime", e.endTime)
                    .put("fullDay", e.fullDay)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EVENTS, arr.toString())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    /** Debug helper: the raw persisted events JSON (and last-updated epoch), or null if never saved. */
    fun rawJson(context: Context): Pair<String?, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EVENTS, null) to prefs.getLong(KEY_UPDATED, 0L)
    }

    fun load(context: Context): List<ProtonEvent> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ProtonEvent(
                    calendarId = o.optString("calendarId"),
                    eventId = o.optString("eventId"),
                    uid = o.optString("uid"),
                    title = o.optString("title"),
                    startTime = o.optLong("startTime"),
                    endTime = o.optLong("endTime"),
                    fullDay = o.optBoolean("fullDay")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Auto-cycle: advance the pointer each time this is read so the widget shows a different
     * upcoming event on every refresh, wrapping around the list.
     */
    fun nextCycleIndex(context: Context, size: Int): Int {
        if (size <= 0) return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_INDEX, 0)
        val next = (current + 1) % size
        prefs.edit().putInt(KEY_INDEX, next).apply()
        return current % size
    }

    fun currentCycleIndex(context: Context, size: Int): Int {
        if (size <= 0) return 0
        val current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INDEX, 0)
        return current % size
    }
}
