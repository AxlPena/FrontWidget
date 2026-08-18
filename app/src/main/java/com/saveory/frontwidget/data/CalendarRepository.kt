package com.saveory.frontwidget.data

import android.content.Context
import android.provider.CalendarContract
import java.util.*

class CalendarRepository(private val context: Context) {

    data class CalendarInfo(val id: Long, val name: String, val accountName: String)
    data class CalendarEvent(val title: String, val startTime: Long)

    fun getAvailableCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )

        val calendars = mutableListOf<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    calendars.add(
                        CalendarInfo(
                            id = cursor.getLong(0),
                            name = cursor.getString(1) ?: "Unknown",
                            accountName = cursor.getString(2) ?: "Unknown"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return calendars
    }

    fun getUpcomingEvents(selectedCalendarIds: Set<String>? = null, limit: Int = 1): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val endOfDay = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 2) // Check next 48 hours for more events
        }.timeInMillis

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.CALENDAR_ID
        )

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(endOfDay.toString())
            .build()
        
        val selection = if (selectedCalendarIds.isNullOrEmpty()) {
            null
        } else {
            "${CalendarContract.Instances.CALENDAR_ID} IN (${selectedCalendarIds.joinToString(",")})"
        }

        val events = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                null,
                CalendarContract.Instances.BEGIN + " ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    events.add(
                        CalendarEvent(
                            title = cursor.getString(0) ?: "Unnamed Event",
                            startTime = cursor.getLong(1)
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return events
    }

    fun getNextEvent(selectedCalendarIds: Set<String>? = null): CalendarEvent? {
        return getUpcomingEvents(selectedCalendarIds, 1).firstOrNull()
    }
}
