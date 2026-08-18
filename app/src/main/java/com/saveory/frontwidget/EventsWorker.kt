package com.saveory.frontwidget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.*
import com.saveory.frontwidget.di.ProtonEntryPoint
import com.saveory.frontwidget.proton.calendar.ProtonCalendarRepository
import com.saveory.frontwidget.proton.calendar.ProtonEventStore
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * Fetches + decrypts the next 30 days of Proton Calendar events and persists them for the widget.
 * Runs off the Proton Core singletons via a Hilt EntryPoint (this is a plain CoroutineWorker).
 */
class EventsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val tag = "EventsWorker"

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ProtonEntryPoint::class.java
            )

            val repo = ProtonCalendarRepository(
                accountManager = entryPoint.accountManager(),
                userManager = entryPoint.userManager(),
                apiProvider = entryPoint.apiProvider(),
                cryptoContext = entryPoint.cryptoContext()
            )

            val now = System.currentTimeMillis()
            val windowDays = applicationContext
                .getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                .getInt("events_window_days", DEFAULT_WINDOW_DAYS)
                .toLong()
            val end = now + TimeUnit.DAYS.toMillis(windowDays)
            val events = repo.getUpcomingEvents(now, end)
            Log.d(tag, "Decrypted ${events.size} upcoming Proton events")

            ProtonEventStore.save(applicationContext, events)
            FrontWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Events update failed", e)
            Result.retry()
        }
    }

    companion object {
        // Fallback window (days) when the user hasn't picked one yet.
        const val DEFAULT_WINDOW_DAYS = 28
        private const val UNIQUE = "proton_events_update"
        private const val UNIQUE_ONESHOT = "proton_events_update_now"

        fun enqueue(context: Context, force: Boolean = false) {
            val periodic = PeriodicWorkRequestBuilder<EventsWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                // KEEP (never REPLACE): a forced refresh must not cancel an in-flight run, and the
                // periodic schedule should persist across the many triggers (widget onUpdate, app
                // open, boot). The one-shot below handles "refresh now".
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
            if (force) {
                // Unique one-shot with REPLACE so the frequent force triggers collapse into a single
                // run (each trigger supersedes the previous pending one-shot) instead of piling up
                // dozens of concurrent decrypt passes, while never getting stuck behind a stale one.
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_ONESHOT,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<EventsWorker>()
                        .setConstraints(
                            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                        )
                        .build()
                )
            }
        }
    }
}
