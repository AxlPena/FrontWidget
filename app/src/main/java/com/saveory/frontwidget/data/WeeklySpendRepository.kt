package com.saveory.frontwidget.data

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The "this week" Groceries + Fun spend snapshot the widget renders.
 *
 * All money is in whole cents (Long) so the UI never does float math on currency. [limitCents] is
 * the spendable pot for the Monday-Sunday week containing today; [spentCents] is what a future
 * Monarch sync (or manual entry) has written; [remainingCents] is clamped at 0 and [over] carries
 * the "spent more than allowed" signal explicitly (so the UI never relies on colour alone).
 */
data class WeeklySpend(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val limitCents: Long,
    val spentCents: Long,
    val remainingCents: Long,
    val over: Boolean,
    val capWeeklyCents: Long,
    val leftoverWeeklyCents: Long,
    val asOfMs: Long,
    val pendingIncluded: Boolean,
    val authOk: Boolean,
    // The plan-computed limit before any manual override — the smart-forecast recommendation.
    // [limitCents] equals this unless the user typed an override for this week (then [overridden]
    // is true). [autoAdjusted] is true when the forecast cut the cap to fund a later leftover hole
    // (Nov/Dec) and no override is masking it — the ring is showing less than the full $700 pace.
    val recommendedCents: Long,
    val overridden: Boolean,
    val autoAdjusted: Boolean
)

/**
 * Computes the weekly Groceries + Fun spend viewer entirely on-device.
 *
 * Monarch has no weekly-budget object, and the desktop MCP session is not on the phone (see
 * docs/weekly-spend-widget.md), so the spendable LIMIT is derived here from the plan overlay
 * constants, while SPENT is read from prefs as a single value a later Monarch sync writes. The
 * limit formula matches the plan exactly:
 *
 *   WeeklyLimit = max(0, min(GroceriesFunWeekly, LeftoverWeekly))
 *
 * with day-weighted blending across month boundaries for split weeks:
 *
 *   weekly = sum over the 7 days of (that day's month weekly value) / 7
 *
 * Verified against the spec's month table (Sep 175 / Oct 169 / Nov 0 / Dec 0 / Jan 169 / Feb 188).
 */
class WeeklySpendRepository(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
    }

    fun getThisWeek(now: LocalDate = LocalDate.now(ZONE)): WeeklySpend {
        val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekEnd = weekStart.plusDays(6)

        // User-adjustable plan knobs (dollars stored as cents). A future "set limit" flow writes
        // these; the forecast recomputes from them so a cap change can't leave a stale limit.
        val capMonthly = prefs.getLong(KEY_CAP_MONTHLY_CENTS, DEFAULT_CAP_MONTHLY_CENTS) / 100.0
        val saveGoal = prefs.getLong(KEY_EXTRA_SAVE_CENTS, 0L) / 100.0

        // Plan inputs a Monarch sync writes (empty until the first sync with history): G+F already
        // spent per past month (the pool), G+F month-to-date through last Sunday (Room), and extra
        // non-paycheck income per month. Absent everything, this is the on-budget baseline.
        val spentByMonth = readCentsMap(KEY_SPENT_BY_MONTH_JSON)
        val extraByMonth = readCentsMap(KEY_EXTRA_BY_MONTH_JSON)
        val spentMtd = prefs.getLong(KEY_SPENT_MTD_CENTS, 0L).coerceAtLeast(0L) / 100.0

        // Manual override wins for THIS week only (keyed by week start, so it auto-expires next
        // Monday and the ring recasts from the plan). Recommended/forecast still update under it.
        val overrideCents = prefs.getLong(KEY_LIMIT_OVERRIDE_CENTS, -1L)
        val overridden = overrideCents >= 0L &&
            prefs.getString(KEY_LIMIT_OVERRIDE_WEEK, null) == weekStart.toString()

        val rec = WeeklySpendPlan.syncRecommendation(
            today = now,
            spentMtdThroughLastSunday = spentMtd,
            capMonthly = capMonthly,
            saveGoal = saveGoal,
            extraByMonth = extraByMonth,
            spentByMonth = spentByMonth,
            limit = if (overridden) overrideCents / 100.0 else null,
            override = overridden,
        )

        val spentCents = prefs.getLong(KEY_SPENT_CENTS, 0L).coerceAtLeast(0L)
        val over = spentCents > rec.limitCents
        val remainingCents = (rec.limitCents - spentCents).coerceAtLeast(0L)
        val capWeekly = WeeklySpendPlan.weeklyFromMonthly(rec.capMonthly, now)

        return WeeklySpend(
            weekStart = weekStart,
            weekEnd = weekEnd,
            limitCents = rec.limitCents,
            spentCents = spentCents,
            remainingCents = remainingCents,
            over = over,
            capWeeklyCents = WeeklySpendPlan.cents(capWeekly),
            leftoverWeeklyCents = rec.recommendedCents,
            asOfMs = prefs.getLong(KEY_AS_OF_MS, 0L),
            pendingIncluded = prefs.getBoolean(KEY_PENDING_INCLUDED, true),
            // From the weekly_spend tool's `auth_ok`: false means there's no valid Monarch session
            // (spent is then unknown, not zero). Defaults true so a device that has never synced
            // reads as "not synced yet" rather than "signed out".
            authOk = prefs.getBoolean(KEY_AUTH_OK, true),
            recommendedCents = rec.recommendedCents,
            overridden = overridden,
            autoAdjusted = rec.autoAdjusted
        )
    }

    /** Reads a prefs-stored JSON object of "YYYY-MM" -> cents into a "YYYY-MM" -> dollars map. */
    private fun readCentsMap(key: String): Map<String, Double> {
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val out = LinkedHashMap<String, Double>()
            for (label in obj.keys()) out[label] = obj.getLong(label) / 100.0
            out as Map<String, Double>
        }.getOrDefault(emptyMap())
    }

    /**
     * Sets a manual limit for the current week only. Stored against this week's Monday so it stops
     * applying next Monday (the ring then recasts from the plan). Pass the amount in whole cents.
     */
    fun setWeeklyLimitOverrideCents(cents: Long, now: LocalDate = LocalDate.now(ZONE)) {
        val weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        prefs.edit()
            .putLong(KEY_LIMIT_OVERRIDE_CENTS, cents.coerceAtLeast(0L))
            .putString(KEY_LIMIT_OVERRIDE_WEEK, weekStart.toString())
            .apply()
    }

    /** Clears the manual override so the limit reverts to the plan-computed recommendation. */
    fun clearWeeklyLimitOverride() {
        prefs.edit()
            .remove(KEY_LIMIT_OVERRIDE_CENTS)
            .remove(KEY_LIMIT_OVERRIDE_WEEK)
            .apply()
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("America/New_York")

        // Prefs keys written by the Monarch sync (the `weekly_spend` MCP tool's output pushed onto
        // the device): spent_cents -> KEY_SPENT_CENTS, as_of_ms -> KEY_AS_OF_MS,
        // pending_included -> KEY_PENDING_INCLUDED, auth_ok -> KEY_AUTH_OK. After writing them the
        // sync calls FrontWidget.forceRefresh. The two plan knobs back a later "set weekly limit" flow.
        const val KEY_SPENT_CENTS = "weekly_spent_cents"
        const val KEY_AS_OF_MS = "weekly_spent_as_of_ms"
        const val KEY_PENDING_INCLUDED = "weekly_pending_included"
        const val KEY_AUTH_OK = "weekly_auth_ok"
        const val KEY_CAP_MONTHLY_CENTS = "weekly_cap_monthly_cents"
        const val KEY_EXTRA_SAVE_CENTS = "weekly_extra_save_cents"
        const val DEFAULT_CAP_MONTHLY_CENTS = 70_000L            // $700 Groceries + Fun ($450 + 5x$50)

        // Plan inputs a Monarch sync writes so the on-device forecast (WeeklySpendPlan) can carry
        // leftover across months. All JSON objects are "YYYY-MM" -> cents.
        //  - KEY_SPENT_BY_MONTH_JSON: G+F already spent per month, plan start -> last Sunday (the pool).
        //  - KEY_EXTRA_BY_MONTH_JSON: extra non-paycheck income per month (bonus / 4th pay).
        //  - KEY_SPENT_MTD_CENTS: current-month G+F from the 1st through last Sunday only (Room). Not
        //    this week's in-progress spend, so a 15-minute poll never recasts the Monday-frozen limit.
        const val KEY_SPENT_BY_MONTH_JSON = "weekly_spent_by_month_json"
        const val KEY_EXTRA_BY_MONTH_JSON = "weekly_extra_by_month_json"
        const val KEY_SPENT_MTD_CENTS = "weekly_spent_mtd_cents"

        // Manual weekly-limit override (this week only). KEY_LIMIT_OVERRIDE_WEEK holds the ISO Monday
        // it applies to, so it lapses automatically next week.
        const val KEY_LIMIT_OVERRIDE_CENTS = "weekly_limit_override_cents"
        const val KEY_LIMIT_OVERRIDE_WEEK = "weekly_limit_override_week"

        // "Refresh accounts on widget sync" (docs/weekly-spend-widget.md): the sync QUEUES a
        // non-blocking bank refresh for only the food/Fun cards, then reads whatever Monarch already
        // has. KEY_LAST_REFRESH_MS is when we last queued a refresh (drives the 4h periodic gate and
        // the tap debounce). KEY_REFRESH_ACCOUNT_MASKS is a comma-separated set of card last-4s to
        // refresh — resolved to account ids by mask at fetch time (never hard-code Monarch ids).
        const val KEY_LAST_REFRESH_MS = "weekly_last_refresh_ms"
        const val KEY_REFRESH_ACCOUNT_MASKS = "weekly_refresh_account_masks"
        const val DEFAULT_REFRESH_MASKS = "1009,3006"            // Gold ·1009, Platinum ·3006

        // Endpoint the on-device sync GETs to fetch the `weekly_spend` result (JSON with
        // spent_cents / as_of_ms / auth_ok / pending_included). Blank until a phone-side Monarch
        // bridge is configured; while blank the sync worker is a safe no-op and leaves prefs alone.
        const val KEY_SYNC_URL = "monarch_sync_url"
    }
}
