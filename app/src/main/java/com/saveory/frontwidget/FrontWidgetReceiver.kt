package com.saveory.frontwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class FrontWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FrontWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Refresh weather + Proton events when the widget is (re)added, and start cycling.
        WeatherWorker.enqueue(context)
        EventsWorker.enqueue(context, force = true)
        WeeklySpendWorker.enqueue(context, force = true)
        WidgetCycle.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetCycle.cancel(context)
    }
}
