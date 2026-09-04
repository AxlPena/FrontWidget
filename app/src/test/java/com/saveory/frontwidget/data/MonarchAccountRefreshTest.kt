package com.saveory.frontwidget.data

import com.saveory.frontwidget.data.MonarchGraphQlClient.AccountRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the "Refresh accounts on widget sync" selection (docs/weekly-spend-widget.md) against
 * this household's 2026-09-04 account list. The rule: refresh ALL active, visible accounts except
 * loans and investments — every card, checking, savings, PayPal, Personal Profile — NOT the old
 * last-4 1009/3006 allowlist. A mask set only narrows that when explicitly provided.
 */
class MonarchAccountRefreshTest {

    private fun acc(
        id: String,
        type: String,
        mask: String = "",
        displayName: String = id,
        subtype: String = "",
        hidden: Boolean = false,
    ) = AccountRow(id, mask, displayName, hidden, type, subtype)

    // The household from the doc: 10 spendable, the rest loans/investments/crypto/HSA to skip.
    private val household = listOf(
        acc("gold", "credit", mask = "1009"),
        acc("plat", "credit", mask = "3006"),
        acc("6891", "credit", mask = "6891"),
        acc("rei", "credit", mask = "6346"),
        acc("bilt", "credit", mask = "0290"),
        acc("paypal_credit", "credit", displayName = "PayPal Credit"),       // no numeric mask
        acc("paypal", "depository", displayName = "PayPal"),                 // no numeric mask
        acc("personal_profile", "depository", displayName = "Personal Profile"), // no mask
        acc("hysa", "depository", mask = "9908", displayName = "HYSA"),
        acc("cashback", "credit", mask = "6124", displayName = "Cash Back Plus"),
        // --- must be skipped ---
        acc("sofi", "loan", displayName = "SoFi"),
        acc("lexus", "loan", displayName = "Lexus auto loan"),
        acc("fidelity", "brokerage", displayName = "Fidelity"),
        acc("mitre_401k", "brokerage", subtype = "401k", displayName = "MITRE 401k"),
        acc("mitre_403b", "brokerage", subtype = "403b", displayName = "403b"),
        acc("capone_asp", "investment", displayName = "Capital One ASP"),
        acc("coinbase", "crypto", displayName = "Coinbase"),
        acc("hsa", "other", subtype = "hsa", displayName = "HSA"),
    )

    private val spendableIds = setOf(
        "gold", "plat", "6891", "rei", "bilt",
        "paypal_credit", "paypal", "personal_profile", "hysa", "cashback",
    )

    @Test
    fun defaultRefreshesEverySpendableAccountNotJust1009And3006() {
        val ids = MonarchGraphQlClient.selectRefreshAccountIds(household, emptySet())
        assertEquals("all 10 spendable accounts refresh by default", spendableIds, ids.toSet())
        // The doc's key correction: PayPal / Personal Profile have no last-4 but must still refresh.
        assertTrue(ids.contains("paypal"))
        assertTrue(ids.contains("personal_profile"))
        assertTrue(ids.contains("paypal_credit"))
    }

    @Test
    fun loansAndInvestmentsAreNeverRefreshed() {
        val ids = MonarchGraphQlClient.selectRefreshAccountIds(household, emptySet()).toSet()
        for (skip in listOf("sofi", "lexus", "fidelity", "mitre_401k", "mitre_403b",
            "capone_asp", "coinbase", "hsa")) {
            assertFalse("$skip must be skipped", ids.contains(skip))
        }
    }

    @Test
    fun explicitMasksNarrowToThoseCardsOnly() {
        val ids = MonarchGraphQlClient.selectRefreshAccountIds(household, setOf("1009", "3006"))
        assertEquals(setOf("gold", "plat"), ids.toSet())
    }

    @Test
    fun hiddenAccountIsExcludedEvenWhenSpendableType() {
        val hidden = listOf(acc("hidden_card", "credit", mask = "4444", hidden = true))
        assertTrue(MonarchGraphQlClient.selectRefreshAccountIds(hidden, emptySet()).isEmpty())
    }
}
