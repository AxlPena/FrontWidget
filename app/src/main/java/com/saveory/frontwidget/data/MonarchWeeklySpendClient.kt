package com.saveory.frontwidget.data

import android.content.Context
import android.util.Log
import com.saveory.frontwidget.FrontWidget
import com.saveory.frontwidget.WeeklySpendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * On-device Monarch "Spent" sync. Uses the phone's stored Monarch session ([MonarchSessionStore]) to
 * call Monarch's GraphQL API directly ([MonarchGraphQlClient]) — the laptop tunnel / `monarch_sync_url`
 * bridge is gone (see docs/weekly-spend-widget.md, "Unofficial API — can we skip cookies?").
 *
 * The worker/tap/boot/onResume triggers and the widget prefs contract are unchanged: this writes
 * `spent_cents`, `auth_ok`, `pending_included`, and `as_of_ms`, then [FrontWidget.forceRefresh]es.
 *
 *  - No stored session      -> auth_ok=false (ring shows "Sign in"); last spent left untouched.
 *  - Session rejected (401)  -> auth_ok=false; the next login re-arms it. Never retried into a loop.
 *  - Network/server hiccup   -> TransientError so WorkManager retries with backoff.
 */
object MonarchWeeklySpendClient {

    private const val TAG = "WeeklySpendSync"
    private val ZONE: ZoneId = ZoneId.of("America/New_York")

    // "Refresh accounts on widget sync" gates (docs/weekly-spend-widget.md):
    //  - PERIODIC (15-min loop): queue a refresh only if the cards' last refresh is >4h old.
    //  - TAP / syncNow / app resume: queue a refresh (debounced so rapid taps / a pending follow-up
    //    don't re-queue), then schedule a one-shot follow-up fetch ~60s later for the new pending rows.
    //  - FOLLOWUP: never refresh — just re-read whatever Monarch has now.
    private const val STALE_REFRESH_MS = 4L * 60 * 60 * 1000
    private const val TAP_REFRESH_DEBOUNCE_MS = 45_000L

    /** Where a sync came from — decides whether (and how) to queue a bank refresh. */
    enum class Trigger { TAP, PERIODIC, FOLLOWUP }

    sealed interface SyncResult {
        /** No Monarch session yet; recorded auth_ok=false. */
        object NotConfigured : SyncResult

        /** Completed. [authOk] false means the session was rejected; [spentCents] null then. */
        data class Ok(val spentCents: Long?, val authOk: Boolean) : SyncResult

        /** Network / server hiccup; caller should retry. Spent left untouched. */
        data class TransientError(val message: String) : SyncResult
    }

    /**
     * Fetches this week's spend (or the week containing [weekStart], "YYYY-MM-DD") and writes it into
     * prefs. Depending on [trigger] it may first QUEUE a non-blocking bank refresh for the food/Fun
     * cards (never waiting on it) and schedule a follow-up fetch. Blocking I/O — runs on
     * [Dispatchers.IO].
     */
    suspend fun sync(
        context: Context,
        weekStart: String? = null,
        trigger: Trigger = Trigger.PERIODIC
    ): SyncResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

        val session = MonarchSessionStore.load(app)
        if (session == null) {
            // Not signed in: record signed-out so the ring reads "Sign in" rather than a stale
            // number. Don't wipe the last known spent value.
            prefs.edit().putBoolean(WeeklySpendRepository.KEY_AUTH_OK, false).apply()
            FrontWidget.forceRefresh(app)
            Log.d(TAG, "No Monarch session; auth_ok=false")
            return@withContext SyncResult.NotConfigured
        }

        val anchor = weekStart?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now(ZONE)
        val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val includePending = prefs.getBoolean(WeeklySpendRepository.KEY_PENDING_INCLUDED, true)
        val previousSpent = prefs.getLong(WeeklySpendRepository.KEY_SPENT_CENTS, Long.MIN_VALUE)

        try {
            // 1. Queue a bank refresh (never wait on it) when the trigger + staleness gate allow.
            val refreshQueued = maybeQueueRefresh(prefs, session, trigger)

            // 2. Read whatever Monarch already has (last-known; new pending won't be here yet).
            val result = MonarchGraphQlClient.fetchWeeklySpent(
                session = session,
                weekStart = monday.toString(),
                weekEnd = sunday.toString(),
                includePending = includePending
            )
            val newSpent = result.spentCents.coerceAtLeast(0L)
            prefs.edit()
                .putBoolean(WeeklySpendRepository.KEY_AUTH_OK, true)
                .putBoolean(WeeklySpendRepository.KEY_PENDING_INCLUDED, includePending)
                // as_of = when THIS process last read Monarch, not when the bank finished syncing.
                .putLong(WeeklySpendRepository.KEY_AS_OF_MS, System.currentTimeMillis())
                .putLong(WeeklySpendRepository.KEY_SPENT_CENTS, newSpent)
                .apply()

            // 2b. Plan inputs for the on-device forecast: per-month G+F pool (plan start -> last
            // Sunday) and extra non-paycheck income (plan start -> today). These drive the LIMIT
            // (leftover carry across months); a failure here must NOT disturb the Spent write above.
            maybeWritePlanInputs(prefs, session, anchor, monday, includePending)

            // 3. After a tap that actually queued a refresh, sum again ~60s later for the new pending.
            if (trigger == Trigger.TAP && refreshQueued) {
                WeeklySpendWorker.scheduleFollowUp(app)
            }

            // 4. Only redraw when the number changed on a follow-up (avoids a no-op flash).
            val spentUnchanged = newSpent == previousSpent
            if (trigger == Trigger.FOLLOWUP && spentUnchanged) {
                Log.d(TAG, "Follow-up: spent unchanged ($newSpent); skipping forceRefresh")
            } else {
                FrontWidget.forceRefresh(app)
            }
            Log.d(
                TAG,
                "Synced[$trigger]: spent_cents=$newSpent count=${result.count} " +
                    "cats=${result.resolvedCategories} pending=$includePending refreshed=$refreshQueued"
            )
            SyncResult.Ok(result.spentCents, true)
        } catch (e: MonarchApi.AuthException) {
            // Session expired / rejected. Flip to Sign-in; do NOT retry (that would loop on 401).
            prefs.edit().putBoolean(WeeklySpendRepository.KEY_AUTH_OK, false).apply()
            FrontWidget.forceRefresh(app)
            Log.w(TAG, "Monarch session rejected: ${e.message}")
            SyncResult.Ok(null, false)
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: ${e.message}")
            SyncResult.TransientError(e.message ?: "network error")
        }
    }

    /**
     * Reads and stores the [WeeklySpendPlan] forecast inputs (per-month G+F pool + extra income).
     * The G+F pool runs plan start -> last Sunday ([monday] minus a day); income runs plan start ->
     * [anchor] (today) so a fresh deposit counts on the same sync. No-op before plan start. Any read
     * failure is logged and swallowed so the already-written Spent value stands.
     */
    private fun maybeWritePlanInputs(
        prefs: android.content.SharedPreferences,
        session: MonarchSessionStore.Session,
        anchor: LocalDate,
        monday: LocalDate,
        includePending: Boolean
    ) {
        val planStart = WeeklySpendPlan.PLAN_START
        if (anchor.isBefore(planStart)) return
        val lastSunday = monday.minusDays(1)
        try {
            val inputs = MonarchGraphQlClient.fetchPlanInputs(
                session = session,
                planStart = planStart.toString(),
                poolEnd = lastSunday.toString(),
                incomeEnd = anchor.toString(),
                includePending = includePending
            )
            val currentMonthLabel = "%04d-%02d".format(anchor.year, anchor.monthValue)
            val spentMtd = inputs.spentByMonthCents[currentMonthLabel] ?: 0L
            prefs.edit()
                .putString(WeeklySpendRepository.KEY_SPENT_BY_MONTH_JSON, centsMapToJson(inputs.spentByMonthCents))
                .putString(WeeklySpendRepository.KEY_EXTRA_BY_MONTH_JSON, centsMapToJson(inputs.extraByMonthCents))
                .putLong(WeeklySpendRepository.KEY_SPENT_MTD_CENTS, spentMtd)
                .apply()
            Log.d(
                TAG,
                "Plan inputs: pool=${inputs.spentByMonthCents} extra=${inputs.extraByMonthCents} mtd=$spentMtd"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Plan-input read failed (limit keeps last values): ${e.message}")
        }
    }

    private fun centsMapToJson(map: Map<String, Long>): String {
        val obj = org.json.JSONObject()
        for ((label, cents) in map) obj.put(label, cents)
        return obj.toString()
    }

    /**
     * Queues a non-blocking refresh for the food/Fun cards when the trigger + staleness gate allow,
     * and records when. Returns true only if Monarch accepted the queue. A [MonarchApi.AuthException]
     * propagates (the session is bad); any other refresh failure is swallowed so the fetch still runs
     * ("refresh mutation fails -> still fetch transactions").
     */
    private fun maybeQueueRefresh(
        prefs: android.content.SharedPreferences,
        session: MonarchSessionStore.Session,
        trigger: Trigger
    ): Boolean {
        val masks = prefs.getString(
            WeeklySpendRepository.KEY_REFRESH_ACCOUNT_MASKS,
            WeeklySpendRepository.DEFAULT_REFRESH_MASKS
        ).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (masks.isEmpty()) return false

        val lastRefresh = prefs.getLong(WeeklySpendRepository.KEY_LAST_REFRESH_MS, 0L)
        val now = System.currentTimeMillis()
        val sinceLast = now - lastRefresh
        val want = when (trigger) {
            Trigger.FOLLOWUP -> false
            // Debounce so a tap plus its own follow-up (or two quick taps) don't re-queue a refresh
            // that's effectively already in progress.
            Trigger.TAP -> sinceLast >= TAP_REFRESH_DEBOUNCE_MS
            Trigger.PERIODIC -> sinceLast >= STALE_REFRESH_MS
        }
        if (!want) return false

        return try {
            val outcome = MonarchGraphQlClient.requestAccountsRefresh(session, masks)
            if (outcome.queued) {
                prefs.edit().putLong(WeeklySpendRepository.KEY_LAST_REFRESH_MS, now).apply()
                Log.d(TAG, "Queued account refresh for ${outcome.accountCount} card(s) [$trigger]")
            }
            outcome.queued
        } catch (e: MonarchApi.AuthException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Account refresh failed (ignored, still fetching): ${e.message}")
            false
        }
    }
}
