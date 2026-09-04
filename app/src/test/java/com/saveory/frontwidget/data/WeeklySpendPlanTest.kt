package com.saveory.frontwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Validates the Kotlin plan port against the exact cents the tracker doc pins
 * (docs/weekly-spend-tracker.md, "Confirm against these cents"). All cases are on-budget,
 * $0 extra save. If any of these fail, the budget math has drifted from the MCP source of truth.
 */
class WeeklySpendPlanTest {

    @Test
    fun billsSumMatchesSourceOfTruth() {
        assertEquals(6720.17, WeeklySpendPlan.NEW_BILLS_MONTHLY, 0.001)
    }

    @Test
    fun sep3OnBudget() {
        val rec = WeeklySpendPlan.syncRecommendation(today = LocalDate.of(2026, 9, 3))
        assertEquals(15290L, rec.recommendedCents)
        assertEquals(15290L, rec.limitCents)
    }

    @Test
    fun nov2NoExtraSepOctAssumedSpent() {
        val rec = WeeklySpendPlan.syncRecommendation(today = LocalDate.of(2026, 11, 2))
        assertEquals(14247L, rec.recommendedCents)
    }

    @Test
    fun oct23With2000ExtraIncome() {
        val rec = WeeklySpendPlan.syncRecommendation(
            today = LocalDate.of(2026, 10, 23),
            extraIncome = 2000.0,
        )
        assertEquals(15806L, rec.recommendedCents)
    }

    @Test
    fun nov2AfterOct2000Extra() {
        val rec = WeeklySpendPlan.syncRecommendation(
            today = LocalDate.of(2026, 11, 2),
            extraByMonth = mapOf("2026-10" to 2000.0),
        )
        assertEquals(16333L, rec.recommendedCents)
    }

    @Test
    fun overrideSep3() {
        val rec = WeeklySpendPlan.syncRecommendation(
            today = LocalDate.of(2026, 9, 3),
            limit = 200.0,
            override = true,
        )
        assertEquals(20000L, rec.limitCents)
        assertEquals(15290L, rec.recommendedCents)
    }
}
