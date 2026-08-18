package com.saveory.frontwidget.data

import android.app.AlarmManager
import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class AlarmRepository(private val context: Context) {

    fun getNextAlarm(): String {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextAlarm = alarmManager.nextAlarmClock

        return if (nextAlarm != null) {
            val date = Date(nextAlarm.triggerTime)
            // Match the widget clocks (which use TextClock's 12/24-hour auto-switch): on a 24-hour
            // device show a leading zero and no am/pm ("Mon 08:30"); on a 12-hour device drop the
            // leading zero and show am/pm ("Mon 8:30 AM").
            val pattern = if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
            val format = SimpleDateFormat(pattern, Locale.getDefault())
            "Alarm: " + format.format(date)
        } else {
            "No Alarms Set"
        }
    }
}
