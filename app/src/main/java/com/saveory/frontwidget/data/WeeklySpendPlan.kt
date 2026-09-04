package com.saveory.frontwidget.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Pure on-device port of the MCP server's weekly-spend plan math
 * (`src/monarch_mcp_server/weekly_spend.py`: `leftover_monthly_new`, `leftover_forecast`,
 * `smart_forecast`, `recommended_weekly_spend`, `sync_recommendation`). No Android or Monarch
 * dependencies so it can be unit-tested on the JVM and produces the exact `recommended_cents`
 * the doc pins (Sep 3 -> 15290, etc.).
 *
 * The LIMIT half only. SPENT is fetched separately (MonarchGraphQlClient). Money flows as doubles
 * here to mirror the Python's `round(x, 2)` at each step; callers convert the final result to cents.
 *
 * Rounding matches Python 3's round-half-to-even via [Math.rint], since the Python source rounds
 * intermediate leftovers/inflows the same way.
 */
object WeeklySpendPlan {

    // --- Plan constants (verbatim from weekly_spend.py) ---------------------------------------
    const val INCOME_PAIR = 2593.75 + 1729.17            // 4322.92
    const val DEFAULT_CAP_MONTHLY = 700.0

    /**
     * Monarch planned expenses minus Groceries + Fun, plus leftovered subs
     * (rent/loans/auto/student/insurance/legal/gas/parking/maint + Streaming/GNC/Breaking Point/
     * AWS/Konami). PayPal min and Amex statements are NOT in here. Sums to 6720.17.
     */
    const val NEW_BILLS_MONTHLY =
        1850.0 + 150.0 + 1984.23 + 656.53 + 840.46 + 372.63 + 17.59 + 21.31 + 2.0 +
            278.0 + 150.0 + 73.0 + 28.78 + 8.99 + 22.4 + 251.95 + 10.0 + 2.3

    const val SEP_STATEMENTS = 598.77 + 21.31 + 527.51   // 1147.59
    const val PLAN_IT_FALL = 779.7 + 1428.48 + 544.1     // 2752.28 (Oct-Dec)
    const val PLAN_IT_WINTER = 544.1                     // Jan-Feb

    val PLAN_START: LocalDate = LocalDate.of(2026, 9, 1)

    private val PAYCHECK_AMOUNTS = doubleArrayOf(2593.75, 1729.17)
    private const val PAYCHECK_TOLERANCE = 1.0

    // Positive inflows in these (folded) categories are never "extra grocery money": transfers,
    // credit-card payments, receivable checks, and Mom Card. Ported from TRANSFER_ALIASES.
    private val TRANSFER_ALIASES = setOf(
        "transfer",
        "credit card payment",
        "balance adjustments",
        "check",
        "mom card transactions",
    )

    // Fun Budget / Other lines that must never enter extra income (or Spent / the $700 cap).
    private val WEEKLY_POT_EXCLUDE_ALIASES = setOf(
        "streaming", "subscriptions", "subscription", "games", "pets", "public transit",
        "taxi", "taxi & ride shares", "furniture", "furniture & housewares", "fun money",
        "shopping", "mom card", "mom card transactions",
    )

    // --- Small helpers matching the Python -----------------------------------------------------
    private fun round2(x: Double): Double = Math.rint(x * 100.0) / 100.0
    fun cents(x: Double): Long = Math.rint(x * 100.0).toLong()

    private fun monthLabel(year: Int, month: Int): String = "%04d-%02d".format(year, month)

    private fun addCalendarMonth(year: Int, month: Int, delta: Int = 1): Pair<Int, Int> {
        val index = year * 12 + (month - 1) + delta
        return index / 12 to (index % 12 + 1)
    }

    private fun daysInMonth(year: Int, month: Int): Int =
        LocalDate.of(year, month, 1).lengthOfMonth()

    fun mondaySunday(today: LocalDate): Pair<LocalDate, LocalDate> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday to monday.plusDays(6)
    }

    fun incomeForMonth(year: Int, month: Int): Double {
        val pairs = if (year == 2026 && month == 10) 3 else 2
        return pairs * INCOME_PAIR
    }

    fun statementsDue(year: Int, month: Int): Double = when {
        year == 2026 && month == 9 -> SEP_STATEMENTS
        year == 2026 && month in 10..12 -> PLAN_IT_FALL
        year == 2027 && month in 1..2 -> PLAN_IT_WINTER
        else -> 0.0
    }

    /**
     * Income minus leftovered bills minus statements minus posted lumps. PayPal min is omitted
     * (last month's leftover settling); Groceries + Fun are the cap, not leftover.
     */
    fun leftoverMonthlyNew(
        year: Int,
        month: Int,
        billsMonthly: Double = NEW_BILLS_MONTHLY,
        saveGoal: Double = 0.0,
        nonMonthlyActualMtd: Double = 0.0,
    ): Double = incomeForMonth(year, month) -
        billsMonthly -
        statementsDue(year, month) -
        saveGoal -
        maxOf(0.0, nonMonthlyActualMtd)

    fun weeklyFromMonthly(monthly: Double, today: LocalDate): Double {
        val days = today.lengthOfMonth()
        return monthly / (days / 7.0)
    }

    private fun weeksLeftInMonth(today: LocalDate): Double {
        val days = today.lengthOfMonth()
        val daysLeft = (days - today.dayOfMonth) + 1
        return daysLeft / 7.0
    }

    private fun expectedPaycheckCount(year: Int, month: Int): Int =
        if (year == 2026 && month == 10) 3 else 2

    private fun isPaycheckAmount(amount: Double): Boolean {
        val v = Math.abs(amount)
        return PAYCHECK_AMOUNTS.any { Math.abs(v - it) <= PAYCHECK_TOLERANCE }
    }

    /** A positive Monarch inflow, pre-folded category name, keyed by its posting month. */
    data class Inflow(val year: Int, val month: Int, val amount: Double, val categoryName: String)

    /**
     * Non-paycheck extra income per month (bonus, 4th pay). Mirrors `extra_income_from_transactions`:
     * a missing paycheck does NOT punch a hole (the expected pair/triple is always assumed), and
     * transfers, checks, credit-card payments, and Mom Card never count. Keyed "YYYY-MM" -> dollars.
     */
    fun extraIncomeByMonth(inflows: List<Inflow>): Map<String, Double> {
        data class Bucket(val pays: MutableList<Double> = mutableListOf(), var other: Double = 0.0)
        val buckets = LinkedHashMap<Pair<Int, Int>, Bucket>()
        for (inflow in inflows) {
            if (inflow.amount <= 0) continue
            val name = inflow.categoryName.trim().lowercase()
            if (name in WEEKLY_POT_EXCLUDE_ALIASES) continue
            if (name in TRANSFER_ALIASES) continue
            val bucket = buckets.getOrPut(inflow.year to inflow.month) { Bucket() }
            if (isPaycheckAmount(inflow.amount)) bucket.pays.add(inflow.amount) else bucket.other += inflow.amount
        }
        val extra = LinkedHashMap<String, Double>()
        for ((ym, bucket) in buckets) {
            val (year, month) = ym
            val pays = bucket.pays.map { Math.abs(it) }.sortedDescending()
            val allowed = expectedPaycheckCount(year, month)
            val extraPays = if (pays.size > allowed) pays.subList(allowed, pays.size).sum() else 0.0
            val total = round2(bucket.other + extraPays)
            if (total > 0) extra[monthLabel(year, month)] = total
        }
        return extra
    }

    // --- Forecast row --------------------------------------------------------------------------
    data class ForecastMonth(
        val year: Int,
        val month: Int,
        val label: String,
        var leftover: Double,
        var extraIncome: Double = 0.0,
        var inflow: Double = 0.0,
        val days: Int,
        var past: Boolean = false,
        var current: Boolean = false,
        var spent: Double = 0.0,
        var gfBudget: Double = 0.0,
        var available: Double = 0.0,
        var gap: Boolean = false,
    )

    data class Forecast(
        val months: List<ForecastMonth>,   // remaining (non-past) rows
        val walk: List<ForecastMonth>,      // all rows from plan start
        val pool: Double,
        val sustainableCapMonthly: Double,
        val capMonthly: Double,
        val autoCut: Boolean,
    )

    private fun monthIter(start: LocalDate, endYear: Int, endMonth: Int): List<Pair<Int, Int>> {
        var year = start.year
        var month = start.monthValue
        val out = mutableListOf<Pair<Int, Int>>()
        while (year * 12 + (month - 1) <= endYear * 12 + (endMonth - 1)) {
            out.add(year to month)
            val next = addCalendarMonth(year, month, 1)
            year = next.first; month = next.second
        }
        return out
    }

    /**
     * Leftover for this month plus the next two, then adjacent negative months appended so
     * September sees the December hole. Used only to find the forecast window's end month.
     */
    private fun leftoverForecastEnd(
        today: LocalDate,
        months: Int = 3,
        saveGoal: Double = 0.0,
    ): Pair<Int, Int> {
        val horizon = maxOf(1, months)
        data class Row(val y: Int, val m: Int, val leftover: Double, val gap: Boolean)
        val rows = mutableListOf<Row>()
        for (offset in 0 until horizon) {
            val (y, m) = addCalendarMonth(today.year, today.monthValue, offset)
            val leftover = round2(leftoverMonthlyNew(y, m, saveGoal = saveGoal))
            rows.add(Row(y, m, leftover, leftover < 0))
        }
        var extra = 0
        while (extra < 3 && rows.isNotEmpty() && rows.last().gap) {
            val (y, m) = addCalendarMonth(rows.last().y, rows.last().m, 1)
            val leftover = round2(leftoverMonthlyNew(y, m, saveGoal = saveGoal))
            if (leftover >= 0) break
            rows.add(Row(y, m, leftover, true))
            extra++
        }
        return rows.last().y to rows.last().m
    }

    /**
     * Walk leftover from plan start, carry unused cash, fund later holes. Mirrors
     * `smart_forecast`. Extra income is added on the month it posted; past months without a
     * recorded actual assume min(cap, max(0, inflow)).
     */
    fun smartForecast(
        today: LocalDate,
        months: Int = 3,
        capMonthly: Double = DEFAULT_CAP_MONTHLY,
        saveGoal: Double = 0.0,
        nonMonthlyActualMtd: Double = 0.0,
        extraIncome: Double = 0.0,
        extraByMonth: Map<String, Double> = emptyMap(),
        spentByMonth: Map<String, Double> = emptyMap(),
        lumpsByMonth: Map<String, Double> = emptyMap(),
    ): Forecast {
        val extras = HashMap(extraByMonth)
        val lumps = HashMap(lumpsByMonth)
        val thisKey = monthLabel(today.year, today.monthValue)
        extras[thisKey] = round2((extras[thisKey] ?: 0.0) + maxOf(0.0, extraIncome))
        lumps[thisKey] = round2((lumps[thisKey] ?: 0.0) + maxOf(0.0, nonMonthlyActualMtd))

        val (endYear, endMonth) = leftoverForecastEnd(today, months, saveGoal)
        val start = if (today >= PLAN_START) PLAN_START else today.withDayOfMonth(1)

        val walk = mutableListOf<ForecastMonth>()
        for ((year, month) in monthIter(start, endYear, endMonth)) {
            val key = monthLabel(year, month)
            val lump = lumps[key] ?: 0.0
            val leftover = round2(
                leftoverMonthlyNew(year, month, saveGoal = saveGoal, nonMonthlyActualMtd = lump)
            )
            val extra = round2(maxOf(0.0, extras[key] ?: 0.0))
            val inflow = round2(leftover + extra)
            val isPast = (year * 12 + month) < (today.year * 12 + today.monthValue)
            val isCurrent = year == today.year && month == today.monthValue
            val row = ForecastMonth(
                year = year, month = month, label = key,
                leftover = leftover, extraIncome = extra, inflow = inflow,
                days = daysInMonth(year, month), past = isPast, current = isCurrent,
            )
            row.spent = when {
                isPast -> {
                    val assumed = round2(minOf(capMonthly, maxOf(0.0, inflow)))
                    round2(maxOf(0.0, spentByMonth[key] ?: assumed))
                }
                isCurrent -> round2(maxOf(0.0, spentByMonth[key] ?: 0.0))
                else -> 0.0
            }
            walk.add(row)
        }

        val pastNet = round2(walk.filter { it.past }.sumOf { it.inflow - it.spent })
        val remainingCandidates = walk.filter { !it.past }
        var lastGap: Int? = null
        remainingCandidates.forEachIndexed { index, row -> if (row.leftover < 0) lastGap = index }
        val fund = if (lastGap != null) remainingCandidates.subList(0, lastGap!! + 1)
        else remainingCandidates
        val currentSpent = fund.firstOrNull { it.current }?.spent ?: 0.0
        val remainingInflow = round2(fund.sumOf { it.inflow })
        val pool = round2(pastNet + remainingInflow - currentSpent)
        val count = maxOf(1, fund.size)
        var sustainable = if (pool <= 0) 0.0 else minOf(capMonthly, pool / count)
        sustainable = round2(maxOf(0.0, sustainable))

        val fundLabels = fund.map { it.label }.toHashSet()
        val remainingRows = mutableListOf<ForecastMonth>()
        for (row in walk) {
            if (row.past) {
                row.gfBudget = row.spent
                row.available = row.inflow
                row.gap = false
                continue
            }
            if (row.label in fundLabels) {
                row.gfBudget = sustainable
                row.available = round2(maxOf(row.inflow, sustainable))
                row.gap = row.leftover < 0 && sustainable <= 0
            } else {
                row.gfBudget = round2(minOf(capMonthly, maxOf(0.0, row.inflow)))
                row.available = row.inflow
                row.gap = row.leftover < 0
            }
            remainingRows.add(row)
        }

        return Forecast(
            months = remainingRows,
            walk = walk,
            pool = pool,
            sustainableCapMonthly = sustainable,
            capMonthly = round2(capMonthly),
            autoCut = sustainable + 0.005 < capMonthly,
        )
    }

    data class Recommendation(
        val recommended: Double,
        val recommendedCents: Long,
        val limit: Double,
        val limitCents: Long,
        val override: Boolean,
        val autoAdjusted: Boolean,
        val capMonthly: Double,        // this month's (possibly cut) G+F budget
        val capMonthlyUncut: Double,
        val splitWeek: Boolean,
    )

    private fun recommendedWeekly(
        leftoverMonthly: Double,
        capMonthly: Double,
        spentMtdThroughLastSunday: Double,
        today: LocalDate,
    ): Double {
        val capWeekly = weeklyFromMonthly(capMonthly, today)
        val leftoverWeekly = weeklyFromMonthly(leftoverMonthly, today)
        val monthlyPot = minOf(capMonthly, leftoverMonthly)
        val weeksLeft = weeksLeftInMonth(today)
        val room = monthlyPot - spentMtdThroughLastSunday
        val share = if (weeksLeft > 0) room / weeksLeft else 0.0
        return round2(maxOf(0.0, minOf(capWeekly, leftoverWeekly, share)))
    }

    private fun monthGfBudget(forecast: Forecast, year: Int, month: Int): Double {
        val key = monthLabel(year, month)
        forecast.walk.firstOrNull { it.label == key }?.let { return it.gfBudget }
        forecast.months.firstOrNull { it.label == key }?.let { return it.gfBudget }
        return 0.0
    }

    private fun effectiveLimit(recommended: Double, limit: Double?, override: Boolean): Double =
        if (override && limit != null) round2(maxOf(0.0, limit))
        else round2(maxOf(0.0, recommended))

    /**
     * On every widget sync: forecast with carry, then set recommended/limit. Limit follows
     * recommended unless the user set [override] on the widget for this week.
     */
    fun syncRecommendation(
        today: LocalDate,
        spentMtdThroughLastSunday: Double = 0.0,
        capMonthly: Double = DEFAULT_CAP_MONTHLY,
        saveGoal: Double = 0.0,
        nonMonthlyActualMtd: Double = 0.0,
        extraIncome: Double = 0.0,
        extraByMonth: Map<String, Double> = emptyMap(),
        spentByMonth: Map<String, Double> = emptyMap(),
        lumpsByMonth: Map<String, Double> = emptyMap(),
        limit: Double? = null,
        override: Boolean = false,
    ): Recommendation {
        val spentMap = HashMap(spentByMonth)
        val thisKey = monthLabel(today.year, today.monthValue)
        spentMap[thisKey] = round2(maxOf(0.0, spentMtdThroughLastSunday))

        val forecast = smartForecast(
            today,
            capMonthly = capMonthly,
            saveGoal = saveGoal,
            extraIncome = extraIncome,
            extraByMonth = extraByMonth,
            spentByMonth = spentMap,
            lumpsByMonth = lumpsByMonth,
            nonMonthlyActualMtd = nonMonthlyActualMtd,
        )
        val thisMonth = forecast.months.first()
        val leftoverForClamp = maxOf(thisMonth.inflow, thisMonth.gfBudget)
        var recommended = recommendedWeekly(
            leftoverMonthly = leftoverForClamp,
            capMonthly = thisMonth.gfBudget,
            spentMtdThroughLastSunday = spentMtdThroughLastSunday,
            today = today,
        )

        // Split-week: day-weight both months' weekly G+F budgets. August (pre plan start) is
        // ignored - a week whose Monday is before PLAN_START uses this month only.
        val (monday, sunday) = mondaySunday(today)
        var splitWeek = false
        if (monday >= PLAN_START && (monday.year != sunday.year || monday.monthValue != sunday.monthValue)) {
            val daysA = (0 until 7).count { monday.plusDays(it.toLong()).monthValue == monday.monthValue }
            val gfA = monthGfBudget(forecast, monday.year, monday.monthValue)
            val gfB = monthGfBudget(forecast, sunday.year, sunday.monthValue)
            val weeklyA = weeklyFromMonthly(gfA, monday)
            val weeklyB = weeklyFromMonthly(gfB, sunday)
            if (weeklyA > 0 && weeklyB > 0) {
                val blended = (daysA * weeklyA + (7 - daysA) * weeklyB) / 7.0
                recommended = round2(maxOf(0.0, minOf(recommended, blended)))
                splitWeek = true
            }
        }

        val ring = effectiveLimit(recommended, limit, override)
        return Recommendation(
            recommended = recommended,
            recommendedCents = cents(recommended),
            limit = ring,
            limitCents = cents(ring),
            override = override,
            autoAdjusted = forecast.autoCut && !override,
            capMonthly = thisMonth.gfBudget,
            capMonthlyUncut = round2(capMonthly),
            splitWeek = splitWeek,
        )
    }
}
