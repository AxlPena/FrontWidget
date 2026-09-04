package com.saveory.frontwidget.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "Spent" half of the widget contract, computed on-device against Monarch's GraphQL API — a
 * direct port of the MCP server's `weekly_spend` card (see docs/weekly-spend-widget.md). It does NOT
 * compute the limit; [WeeklySpendRepository] still owns leftover/cap math.
 *
 * Steps (same as the tool):
 *  1. GetCategories -> resolve the eight Groceries + Fun pot lines BY NAME (never hard-code IDs;
 *     the IDs in the doc are one household only).
 *  2. GetTransactionsList for this Monday-Sunday, filtered to those category ids, pending-in.
 *  3. spent_cents = round(-sum(amount) * 100), skipping hidden rows (and pending when excluded).
 */
object MonarchGraphQlClient {

    private const val TAG = "MonarchGraphQl"

    data class SpentResult(val spentCents: Long, val count: Int, val resolvedCategories: Int)

    // slot -> accepted names (casefolded). Ported verbatim from weekly_spend.py GROCERY_FUN_ALIASES
    // so the phone and the server resolve the same pot.
    private val ALIASES: Map<String, List<String>> = mapOf(
        "groceries" to listOf(
            "groceries", "grocery", "groceries & restaurants", "groceries and restaurants"
        ),
        "restaurants" to listOf(
            "restaurants", "restaurants & bars", "restaurants and bars", "dining", "dining out"
        ),
        "coffee" to listOf("coffee", "coffee shops", "coffee shops & tea"),
        "entertainment" to listOf(
            "entertainment", "entertainment & recreation", "entertainment and recreation", "recreation"
        ),
        "travel" to listOf("travel", "travel & vacation", "travel and vacation", "vacation"),
        "clothing" to listOf("clothing", "clothes", "apparel"),
        "personal" to listOf("personal", "personal care"),
        "electronics" to listOf("electronics")
    )

    private val ALL_ALIASES: Set<String> = ALIASES.values.flatten().toSet()

    private const val CATEGORIES_QUERY = """
        query GetCategories {
          categories {
            id
            name
            group { id name type }
          }
        }
    """

    private const val TRANSACTIONS_QUERY = """
        query GetTransactionsList(${'$'}offset: Int, ${'$'}limit: Int, ${'$'}filters: TransactionFilterInput, ${'$'}orderBy: TransactionOrdering) {
          allTransactions(filters: ${'$'}filters) {
            totalCount
            results(offset: ${'$'}offset, limit: ${'$'}limit, orderBy: ${'$'}orderBy) {
              id
              amount
              pending
              date
              hideFromReports
              category { id name }
            }
          }
        }
    """

    private const val MAX_ROWS = 500

    private const val ACCOUNTS_QUERY = """
        query GetAccounts {
          accounts {
            id
            displayName
            mask
          }
        }
    """

    // Non-blocking bank refresh (Plaid/MX). Mirrors monarchmoneycommunity.request_accounts_refresh:
    // it QUEUES the pull and returns immediately — new pending rows are NOT in this round-trip.
    private const val REFRESH_MUTATION = """
        mutation Common_ForceRefreshAccountsMutation(${'$'}input: ForceRefreshAccountsInput!) {
          forceRefreshAccounts(input: ${'$'}input) {
            success
          }
        }
    """

    /** Outcome of a queued account refresh: whether Monarch accepted it and how many cards it hit. */
    data class RefreshOutcome(val queued: Boolean, val accountCount: Int)

    /**
     * QUEUES a non-blocking bank refresh for only the accounts whose last-4 is in [masks] (the
     * food/Fun cards — Gold ·1009, Platinum ·3006, optional grocery debit). Never waits on
     * hasSyncInProgress (that can take minutes and blows the worker timeout). Throws
     * [MonarchApi.AuthException] if the session is rejected; other failures are the caller's to
     * ignore per the spec ("refresh mutation fails -> still fetch transactions").
     */
    fun requestAccountsRefresh(
        session: MonarchSessionStore.Session,
        masks: Set<String>
    ): RefreshOutcome {
        val wanted = masks.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return RefreshOutcome(false, 0)

        val accData = MonarchApi.graphql(session, "GetAccounts", ACCOUNTS_QUERY, JSONObject())
        val accounts = accData.optJSONArray("accounts") ?: return RefreshOutcome(false, 0)

        val ids = mutableListOf<String>()
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val id = a.optString("id").takeIf { it.isNotBlank() } ?: continue
            // Prefer the account's own mask; fall back to trailing digits of the display name.
            val last4 = a.optString("mask").ifBlank {
                a.optString("displayName").filter { it.isDigit() }.takeLast(4)
            }
            if (last4.isNotEmpty() && last4 in wanted) ids.add(id)
        }
        if (ids.isEmpty()) {
            Log.d(TAG, "No accounts matched refresh masks $wanted; nothing queued")
            return RefreshOutcome(false, 0)
        }

        val idArray = JSONArray()
        ids.forEach { idArray.put(it) }
        val variables = JSONObject().put("input", JSONObject().put("accountIds", idArray))
        val data = MonarchApi.graphql(
            session, "Common_ForceRefreshAccountsMutation", REFRESH_MUTATION, variables
        )
        val ok = data.optJSONObject("forceRefreshAccounts")?.optBoolean("success", false) ?: false
        return RefreshOutcome(ok, ids.size)
    }

    /**
     * Fetches this week's Groceries + Fun spend. [weekStart]/[weekEnd] are "YYYY-MM-DD" (Mon/Sun in
     * America/New_York). Throws [MonarchApi.AuthException] when the session is rejected.
     */
    fun fetchWeeklySpent(
        session: MonarchSessionStore.Session,
        weekStart: String,
        weekEnd: String,
        includePending: Boolean
    ): SpentResult {
        val catData = MonarchApi.graphql(session, "GetCategories", CATEGORIES_QUERY, JSONObject())
        val ids = resolvePotCategoryIds(catData.optJSONArray("categories"))
        if (ids.isEmpty()) {
            // No pot categories resolved: do NOT sum every expense (an empty category filter returns
            // all transactions). Report 0, matching weekly_spend.py's out-of-scope behaviour.
            Log.w(TAG, "No Groceries+Fun categories resolved; reporting spent=0")
            return SpentResult(0L, 0, 0)
        }

        val categoryArray = JSONArray()
        ids.forEach { categoryArray.put(it) }
        val filters = JSONObject()
            .put("search", "")
            .put("categories", categoryArray)
            .put("accounts", JSONArray())
            .put("tags", JSONArray())
            .put("startDate", weekStart)
            .put("endDate", weekEnd)
        val variables = JSONObject()
            .put("offset", 0)
            .put("limit", MAX_ROWS)
            .put("orderBy", "date")
            .put("filters", filters)

        val txData = MonarchApi.graphql(session, "GetTransactionsList", TRANSACTIONS_QUERY, variables)
        val results = txData.optJSONObject("allTransactions")?.optJSONArray("results") ?: JSONArray()

        val allowed = ids.toHashSet()
        var spent = 0.0
        var count = 0
        for (i in 0 until results.length()) {
            val txn = results.optJSONObject(i) ?: continue
            val categoryId = txn.optJSONObject("category")?.optString("id")
            // Belt-and-suspenders: the server already filtered by category, but re-check so a stray
            // row never leaks into the sum.
            if (categoryId == null || categoryId !in allowed) continue
            if (txn.optBoolean("hideFromReports", false)) continue
            if (!includePending && txn.optBoolean("pending", false)) continue
            // Expenses are negative in Monarch; negate so grocery -$42.50 -> $42.50 and an in-scope
            // refund (+) lowers spent.
            spent += -txn.optDouble("amount", 0.0)
            count++
        }

        val cents = Math.round(spent * 100.0).coerceAtLeast(0L)
        return SpentResult(cents, count, ids.size)
    }

    /** Per-month plan inputs for the on-device forecast. Keys are "YYYY-MM"; values are cents. */
    data class PlanInputs(
        val spentByMonthCents: Map<String, Long>,
        val extraByMonthCents: Map<String, Long>,
    )

    /**
     * Reads the history the [WeeklySpendPlan] forecast needs to carry leftover across months:
     *  - Groceries + Fun already spent per month from [planStart] through [poolEnd] (last Sunday):
     *    the pool, and the current month's slice is Room (spent MTD through last Sunday).
     *  - Extra non-paycheck income per month from [planStart] through [incomeEnd] (today), so a
     *    $2,000 deposit raises later weeks on the same sync it posts.
     *
     * Dates are "YYYY-MM-DD" (Mon/Sun in America/New_York). Throws [MonarchApi.AuthException] when
     * the session is rejected; other read failures are the caller's to swallow (the Spent sync is
     * the primary product and must still complete).
     */
    fun fetchPlanInputs(
        session: MonarchSessionStore.Session,
        planStart: String,
        poolEnd: String,
        incomeEnd: String,
        includePending: Boolean,
    ): PlanInputs {
        val catData = MonarchApi.graphql(session, "GetCategories", CATEGORIES_QUERY, JSONObject())
        val categories = catData.optJSONArray("categories")
        val potIds = resolvePotCategoryIds(categories)

        // Groceries + Fun spent per month (plan start -> last Sunday). Empty pot, or a pool window
        // that ends before plan start (this week's last Sunday was in August), -> no pool.
        val spentByMonth = LinkedHashMap<String, Double>()
        if (potIds.isNotEmpty() && poolEnd >= planStart) {
            val allowed = potIds.toHashSet()
            for (txn in fetchTransactionsPaged(session, planStart, poolEnd, potIds)) {
                val categoryId = txn.optJSONObject("category")?.optString("id")
                if (categoryId == null || categoryId !in allowed) continue
                if (txn.optBoolean("hideFromReports", false)) continue
                if (!includePending && txn.optBoolean("pending", false)) continue
                val label = monthLabelOf(txn.optString("date")) ?: continue
                spentByMonth[label] = (spentByMonth[label] ?: 0.0) + -txn.optDouble("amount", 0.0)
            }
        }

        // Extra non-paycheck income per month (plan start -> today). Scope to INCOME-GROUP
        // categories only (mirrors the MCP's _income_category_ids): scanning every positive row
        // would let transfers, refunds, and credit-card statement credits leak in as "extra cash".
        val incomeIds = resolveIncomeCategoryIds(categories)
        val inflows = ArrayList<WeeklySpendPlan.Inflow>()
        if (incomeIds.isNotEmpty() && incomeEnd >= planStart) {
            for (txn in fetchTransactionsPaged(session, planStart, incomeEnd, incomeIds)) {
                if (txn.optBoolean("hideFromReports", false)) continue
                val amount = txn.optDouble("amount", 0.0)
                if (amount <= 0.0) continue
                val date = txn.optString("date")
                if (date.length < 7) continue
                val year = date.substring(0, 4).toIntOrNull() ?: continue
                val month = date.substring(5, 7).toIntOrNull() ?: continue
                val name = txn.optJSONObject("category")?.optString("name").orEmpty()
                inflows.add(WeeklySpendPlan.Inflow(year, month, amount, name))
            }
        }
        val extraByMonth = WeeklySpendPlan.extraIncomeByMonth(inflows)

        return PlanInputs(
            spentByMonthCents = spentByMonth.mapValues { Math.round(it.value * 100.0).coerceAtLeast(0L) },
            extraByMonthCents = extraByMonth.mapValues { Math.round(it.value * 100.0).coerceAtLeast(0L) },
        )
    }

    private fun monthLabelOf(date: String?): String? {
        if (date == null || date.length < 7) return null
        return date.substring(0, 7)
    }

    /**
     * Fetches every transaction in [startDate]..[endDate] (optionally filtered to [categoryIds]),
     * paging through `allTransactions.results` until `totalCount` is covered. A null/empty
     * [categoryIds] intentionally returns ALL categories (used for the income scan).
     */
    private fun fetchTransactionsPaged(
        session: MonarchSessionStore.Session,
        startDate: String,
        endDate: String,
        categoryIds: List<String>?,
    ): List<JSONObject> {
        val categoryArray = JSONArray()
        categoryIds?.forEach { categoryArray.put(it) }
        val out = ArrayList<JSONObject>()
        var offset = 0
        var guard = 0
        while (guard++ < 40) {
            val filters = JSONObject()
                .put("search", "")
                .put("categories", categoryArray)
                .put("accounts", JSONArray())
                .put("tags", JSONArray())
                .put("startDate", startDate)
                .put("endDate", endDate)
            val variables = JSONObject()
                .put("offset", offset)
                .put("limit", MAX_ROWS)
                .put("orderBy", "date")
                .put("filters", filters)
            val data = MonarchApi.graphql(session, "GetTransactionsList", TRANSACTIONS_QUERY, variables)
            val all = data.optJSONObject("allTransactions")
            val results = all?.optJSONArray("results") ?: JSONArray()
            val total = all?.optInt("totalCount", 0) ?: 0
            for (i in 0 until results.length()) results.optJSONObject(i)?.let { out.add(it) }
            offset += results.length()
            if (results.length() == 0 || offset >= total) break
        }
        return out
    }

    private fun resolvePotCategoryIds(categories: JSONArray?): List<String> {
        if (categories == null) return emptyList()
        val ids = LinkedHashSet<String>()
        for (i in 0 until categories.length()) {
            val cat = categories.optJSONObject(i) ?: continue
            val id = cat.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = cat.optString("name").trim().lowercase()
            if (name in ALL_ALIASES) ids.add(id)
        }
        return ids.toList()
    }

    /**
     * Category ids whose group is Income (type or name == "income"). Ported from the MCP's
     * `_income_category_ids` so the extra-income scan sees only real income lines, not every
     * positive transaction (transfers, refunds, statement credits).
     */
    private fun resolveIncomeCategoryIds(categories: JSONArray?): List<String> {
        if (categories == null) return emptyList()
        val ids = LinkedHashSet<String>()
        for (i in 0 until categories.length()) {
            val cat = categories.optJSONObject(i) ?: continue
            val id = cat.optString("id").takeIf { it.isNotBlank() } ?: continue
            val group = cat.optJSONObject("group")
            val gtype = group?.optString("type").orEmpty().trim().lowercase()
            val gname = group?.optString("name").orEmpty().trim().lowercase()
            if (gtype == "income" || gname == "income") ids.add(id)
        }
        return ids.toList()
    }
}
