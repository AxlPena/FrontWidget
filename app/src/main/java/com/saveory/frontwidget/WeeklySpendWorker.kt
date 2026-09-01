package com.saveory.frontwidget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.saveory.frontwidget.data.MonarchWeeklySpendClient
import java.util.concurrent.TimeUnit

/**
 * Polls the Monarch "Spent" half of the weekly-spend widget. Runs every 15 minutes (WorkManager's
 * minimum periodic interval) and also on demand when the user taps the spend ring (see
 * [SyncSpendAction]). The actual fetch + prefs write + widget refresh lives in
 * [MonarchWeeklySpendClient]; this worker only schedules and maps the result to a retry policy.
 *
 * When no sync endpoint is configured the client is a no-op, so the periodic schedule is harmless
 * until a phone-side Monarch bridge URL exists.
 */
class WeeklySpendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        // The periodic poll carries no input data -> PERIODIC (4h refresh gate). syncNow/follow-up
        // pass their trigger so the client knows whether to queue a bank refresh.
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { runCatching { MonarchWeeklySpendClient.Trigger.valueOf(it) }.getOrNull() }
            ?: MonarchWeeklySpendClient.Trigger.PERIODIC
        when (MonarchWeeklySpendClient.sync(applicationContext, trigger = trigger)) {
            is MonarchWeeklySpendClient.SyncResult.TransientError -> Result.retry()
            else -> Result.success()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Weekly spend sync failed", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "WeeklySpendWorker"
        private const val UNIQUE_PERIODIC = "weekly_spend_sync"
        private const val UNIQUE_ONESHOT = "weekly_spend_sync_now"
        private const val UNIQUE_FOLLOWUP = "weekly_spend_sync_followup"
        private const val KEY_TRIGGER = "trigger"
        // New pending rows (e.g. Uber Eats) land shortly after the queued refresh; sum again once.
        private const val FOLLOWUP_DELAY_SEC = 60L

        private fun connected() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Schedules the every-15-minute poll. [force] also fires an immediate one-shot. */
        fun enqueue(context: Context, force: Boolean = false) {
            val periodic = PeriodicWorkRequestBuilder<WeeklySpendWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP so the many triggers (widget onUpdate, app open, boot) don't reset the
                // 15-minute cadence or cancel an in-flight run; syncNow handles "refresh now".
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
            if (force) syncNow(context)
        }

        /**
         * Immediate, expedited sync — used by the ring tap / app resume. Runs as TAP so the client
         * queues a bank refresh (debounced) and schedules the follow-up. Collapses rapid taps.
         */
        fun syncNow(context: Context) {
            val oneShot = OneTimeWorkRequestBuilder<WeeklySpendWorker>()
                .setConstraints(connected())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_TRIGGER to MonarchWeeklySpendClient.Trigger.TAP.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                oneShot
            )
        }

        /**
         * One-shot follow-up ~60s after a tap queued a bank refresh, to sum the new pending rows.
         * Runs as FOLLOWUP so it does NOT queue another refresh and only redraws if spent changed.
         */
        fun scheduleFollowUp(context: Context) {
            val followUp = OneTimeWorkRequestBuilder<WeeklySpendWorker>()
                .setConstraints(connected())
                .setInitialDelay(FOLLOWUP_DELAY_SEC, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_TRIGGER to MonarchWeeklySpendClient.Trigger.FOLLOWUP.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_FOLLOWUP,
                ExistingWorkPolicy.REPLACE,
                followUp
            )
        }
    }
}
