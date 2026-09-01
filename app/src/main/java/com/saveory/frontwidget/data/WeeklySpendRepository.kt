package com.saveory.frontwidget.data

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
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
    // The plan-computed limit before any manual override — max(0, min(cap, leftover)). [limitCents]
    // equals this unless the user typed an override for this week (then [overridden] is true).
    val recommendedCents: Long,
    val overridden: Boolean
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
        // these; leftover math recomputes from them so a cap change can't leave a stale limit.
        val capMonthly = prefs.getLong(KEY_CAP_MONTHLY_CENTS, DEFAULT_CAP_MONTHLY_CENTS) / 100.0
        val saveGoal = prefs.getLong(KEY_EXTRA_SAVE_CENTS, 0L) / 100.0

        // Day-weight each month's weekly leftover and cap across the 7 days of this week, so a week
        // that straddles a month boundary blends both months (non-split weeks reduce to one month).
        var sumLeftover = 0.0
        var sumCap = 0.0
        var day = weekStart
        while (!day.isAfter(weekEnd)) {
            val ym = YearMonth.from(day).coerceIn(HORIZON_START, HORIZON_END)
            val plan = monthPlan(ym)
            val days = ym.lengthOfMonth()
            val leftoverMonthly =
                plan.income - FIXED_MONTHLY - PAYPAL_MIN - plan.planIt - saveGoal - plan.oneTime
            sumLeftover += leftoverMonthly * 7.0 / days
            sumCap += capMonthly * 7.0 / days
            day = day.plusDays(1)
        }
        val leftoverWeekly = sumLeftover / 7.0
        val capWeekly = sumCap / 7.0
        val recommendedCents = Math.round(maxOf(0.0, minOf(capWeekly, leftoverWeekly)) * 100.0)

        // Manual override wins for THIS week only (keyed by week start, so it auto-expires next
        // Monday and the ring recasts from the plan). See docs/weekly-spend-widget.md.
        val overrideCents = prefs.getLong(KEY_LIMIT_OVERRIDE_CENTS, -1L)
        val overridden = overrideCents >= 0L &&
            prefs.getString(KEY_LIMIT_OVERRIDE_WEEK, null) == weekStart.toString()
        val limitCents = if (overridden) overrideCents else recommendedCents

        val spentCents = prefs.getLong(KEY_SPENT_CENTS, 0L).coerceAtLeast(0L)
        val over = spentCents > limitCents
        val remainingCents = (limitCents - spentCents).coerceAtLeast(0L)

        return WeeklySpend(
            weekStart = weekStart,
            weekEnd = weekEnd,
            limitCents = limitCents,
            spentCents = spentCents,
            remainingCents = remainingCents,
            over = over,
            capWeeklyCents = Math.round(capWeekly * 100.0),
            leftoverWeeklyCents = Math.round(leftoverWeekly * 100.0),
            asOfMs = prefs.getLong(KEY_AS_OF_MS, 0L),
            pendingIncluded = prefs.getBoolean(KEY_PENDING_INCLUDED, true),
            // From the weekly_spend tool's `auth_ok`: false means there's no valid Monarch session
            // (spent is then unknown, not zero). Defaults true so a device that has never synced
            // reads as "not synced yet" rather than "signed out".
            authOk = prefs.getBoolean(KEY_AUTH_OK, true),
            recommendedCents = recommendedCents,
            overridden = overridden
        )
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

    private data class MonthData(val income: Double, val planIt: Double, val oneTime: Double)

    /**
     * Plan-overlay figures per month (the numbers Monarch cannot supply). October 2026 has three
     * pay pairs; Plan It starts Oct 1 and steps down in Jan; the Sep Amex/·6891 catch-up is a
     * September-only one-off. Months outside the six-month horizon are clamped to the nearest edge.
     */
    private fun monthPlan(ym: YearMonth): MonthData = when (ym) {
        YearMonth.of(2026, 9) -> MonthData(income = INCOME_TWO_PAIRS, planIt = 0.0, oneTime = SEP_AMEX)
        YearMonth.of(2026, 10) -> MonthData(income = INCOME_THREE_PAIRS, planIt = PLAN_IT_FALL, oneTime = 0.0)
        YearMonth.of(2026, 11) -> MonthData(income = INCOME_TWO_PAIRS, planIt = PLAN_IT_FALL, oneTime = 0.0)
        YearMonth.of(2026, 12) -> MonthData(income = INCOME_TWO_PAIRS, planIt = PLAN_IT_FALL, oneTime = 0.0)
        YearMonth.of(2027, 1) -> MonthData(income = INCOME_TWO_PAIRS, planIt = PLAN_IT_WINTER, oneTime = 0.0)
        YearMonth.of(2027, 2) -> MonthData(income = INCOME_TWO_PAIRS, planIt = PLAN_IT_WINTER, oneTime = 0.0)
        else -> MonthData(income = INCOME_TWO_PAIRS, planIt = 0.0, oneTime = 0.0)
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("America/New_York")
        private val HORIZON_START = YearMonth.of(2026, 9)
        private val HORIZON_END = YearMonth.of(2027, 2)

        // Plan overlay constants (monthly, dollars) — see docs/weekly-spend-widget.md.
        private const val FIXED_MONTHLY = 6394.00
        private const val PAYPAL_MIN = 300.00
        private const val INCOME_PAIR = 4322.92
        private const val INCOME_TWO_PAIRS = INCOME_PAIR * 2      // 8645.84
        private const val INCOME_THREE_PAIRS = INCOME_PAIR * 3    // 12968.76 (Oct 2026)
        private const val PLAN_IT_FALL = 2752.28                  // Oct-Dec
        private const val PLAN_IT_WINTER = 544.10                 // Jan-Feb
        private const val SEP_AMEX = 1147.59                      // September only

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
        const val DEFAULT_CAP_MONTHLY_CENTS = 75_000L            // $750 Groceries + Fun

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
