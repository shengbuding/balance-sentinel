package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

object DailySummaryStore {

    private const val TAG = "DailySummaryStore"
    private const val PREFS_NAME = "daily_summaries"
    private const val KEY_SUMMARIES = "summaries"

    private val storeLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DailySummary.serializer())

    fun addSummaries(context: Context, summaries: List<DailySummary>): StoreWriteResult {
        if (summaries.isEmpty()) return StoreWriteResult.Written(0)
        return synchronized(storeLock) {
            try {
                val existing = getSummariesInternal(context).toMutableList()
                val existingKeys = existing.mapTo(mutableSetOf()) { it.key() }
                val toAdd = buildList {
                    summaries.forEach { candidate ->
                        val normalized = candidate.canonicalized()
                        if (existingKeys.add(normalized.key())) add(normalized)
                    }
                }
                if (toAdd.isEmpty()) return@synchronized StoreWriteResult.Written(0)
                existing.addAll(toAdd)
                existing.sortSummaries()
                if (!persistSummaries(context, existing)) return@synchronized failed("ADD_SUMMARIES")
                StoreWriteResult.Written(toAdd.size)
            } catch (_: Exception) {
                Logger.w(TAG, "ADD_SUMMARIES failed")
                failed("ADD_SUMMARIES")
            }
        }
    }

    fun replaceForDate(
        context: Context,
        date: String,
        summaries: List<DailySummary>
    ): StoreWriteResult {
        if (summaries.isEmpty()) return StoreWriteResult.Written(0)
        if (summaries.any { it.date != date }) {
            return StoreWriteResult.Failed("REPLACE_DATE", "Summary date mismatch")
        }
        return synchronized(storeLock) {
            try {
                val incomingByKey = linkedMapOf<SummaryKey, DailySummary>()
                summaries.forEach { candidate ->
                    val normalized = candidate.canonicalized()
                    incomingByKey[normalized.key()] = normalized
                }
                val incomingKeys = incomingByKey.keys
                val replacement = getSummariesInternal(context)
                    .filterNot { it.key() in incomingKeys }
                    .toMutableList()
                replacement.addAll(incomingByKey.values)
                replacement.sortSummaries()
                if (!persistSummaries(context, replacement)) {
                    return@synchronized failed("REPLACE_DATE")
                }
                StoreWriteResult.Written(incomingByKey.size)
            } catch (_: Exception) {
                Logger.w(TAG, "REPLACE_DATE failed")
                failed("REPLACE_DATE")
            }
        }
    }

    fun addSummary(context: Context, summary: DailySummary) {
        addSummaries(context, listOf(summary))
    }

    fun upsert(context: Context, summary: DailySummary) {
        addSummary(context, summary)
    }

    fun getSummaries(context: Context): List<DailySummary> = try {
        getSummariesInternal(context)
    } catch (_: Exception) {
        Logger.w(TAG, "READ_SUMMARIES failed")
        emptyList()
    }

    internal fun getSummariesStrict(context: Context): List<DailySummary> =
        synchronized(storeLock) { getSummariesInternal(context) }

    internal fun getSummariesForCleanup(context: Context): List<DailySummary> =
        synchronized(storeLock) { getSummariesInternal(context).toList() }

    fun getSummariesForCurrency(context: Context, currency: String): List<DailySummary> {
        val canonical = canonicalCurrency(currency)
        return getSummaries(context).filter { canonicalCurrency(it.currency) == canonical }
    }

    fun getAvailableCurrencies(context: Context): List<String> =
        getSummaries(context).map { canonicalCurrency(it.currency) }.distinct()

    fun getSummariesForAccount(context: Context, accountId: String): List<DailySummary> =
        getSummaries(context).filter { it.accountId == accountId }

    fun getSummariesForCurrencyAndAccount(
        context: Context,
        currency: String,
        accountId: String
    ): List<DailySummary> {
        val canonical = canonicalCurrency(currency)
        return getSummaries(context).filter {
            it.accountId == accountId && canonicalCurrency(it.currency) == canonical
        }
    }

    fun getAllAccountIds(context: Context): List<String> =
        getSummaries(context).map { it.accountId }.filter { it.isNotEmpty() }.distinct()

    fun getSummariesInRange(context: Context, from: String, to: String): List<DailySummary> =
        getSummaries(context).filter { it.date >= from && it.date <= to }

    fun hasSummaryForDate(
        context: Context,
        date: String,
        currency: String,
        accountId: String
    ): Boolean {
        val canonical = canonicalCurrency(currency)
        return getSummaries(context).any {
            it.date == date &&
                it.accountId == accountId &&
                canonicalCurrency(it.currency) == canonical
        }
    }

    fun ensureContinuity(
        context: Context,
        fromDate: String,
        toDate: String
    ): StoreWriteResult = ensureContinuity(
        context,
        fromDate,
        toDate,
        LocalDate.now(ZoneId.systemDefault())
    )

    fun ensureContinuity(
        context: Context,
        fromDate: String,
        toDate: String,
        today: LocalDate
    ): StoreWriteResult = synchronized(storeLock) {
        try {
            val from = LocalDate.parse(fromDate)
            val requestedEnd = LocalDate.parse(toDate)
            val end = minOf(requestedEnd, today.minusDays(1))
            if (end < from) return@synchronized StoreWriteResult.Written(0)

            val summaries = getSummariesInternal(context).toMutableList()
            val generated = mutableListOf<DailySummary>()
            val groups = summaries.groupBy {
                it.accountId to canonicalCurrency(it.currency)
            }
            groups.values.forEach { group ->
                val byDate = group.associateBy { LocalDate.parse(it.date) }
                val earliest = group
                    .filter { LocalDate.parse(it.date) >= from }
                    .minByOrNull { it.date }
                    ?: return@forEach
                var carryBalance = earliest.close
                var cursor = LocalDate.parse(earliest.date).plusDays(1)
                while (cursor <= end) {
                    val existing = byDate[cursor]
                    if (existing != null) {
                        carryBalance = existing.close
                    } else {
                        generated += DailySummary(
                            accountId = earliest.accountId,
                            date = cursor.toString(),
                            currency = canonicalCurrency(earliest.currency),
                            open = carryBalance,
                            close = carryBalance,
                            consumed = 0f,
                            toppedUp = 0f,
                            granted = 0f,
                            avgBalance = carryBalance,
                            sampleCount = 0,
                            toppedUpBalanceClose = 0f,
                            grantedBalanceClose = 0f,
                            generatedAt = System.currentTimeMillis()
                        )
                    }
                    cursor = cursor.plusDays(1)
                }
            }
            if (generated.isEmpty()) return@synchronized StoreWriteResult.Written(0)
            summaries.addAll(generated)
            summaries.sortSummaries()
            if (!persistSummaries(context, summaries)) {
                return@synchronized failed("ENSURE_CONTINUITY")
            }
            StoreWriteResult.Written(generated.size)
        } catch (_: Exception) {
            Logger.w(TAG, "ENSURE_CONTINUITY failed")
            failed("ENSURE_CONTINUITY")
        }
    }

    fun clear(context: Context) {
        synchronized(storeLock) {
            check(getPrefs(context).edit().remove(KEY_SUMMARIES).commit())
        }
    }

    fun removeByAccountId(context: Context, accountId: String) {
        synchronized(storeLock) {
            val remaining = getSummariesInternal(context).filter { it.accountId != accountId }
            check(persistSummaries(context, remaining))
        }
    }

    fun migrateAccountIds(context: Context, migrationMap: Map<String, String>) {
        if (migrationMap.isEmpty()) return
        synchronized(storeLock) {
            val existing = getSummariesInternal(context)
            val migrated = existing.map { summary ->
                migrationMap[summary.accountId]?.let { summary.copy(accountId = it) } ?: summary
            }
            if (migrated != existing) {
                check(persistSummaries(context, migrated))
                Logger.i(TAG, "Migrated ${migrationMap.size} account IDs in DailySummaryStore")
            }
        }
    }

    private fun getSummariesInternal(context: Context): List<DailySummary> {
        val raw = getPrefs(context).getString(KEY_SUMMARIES, null) ?: return emptyList()
        return json.decodeFromString(serializer, raw)
    }

    private fun persistSummaries(context: Context, summaries: List<DailySummary>): Boolean {
        val serialized = json.encodeToString(serializer, summaries)
        return getPrefs(context).edit().putString(KEY_SUMMARIES, serialized).commit()
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun DailySummary.canonicalized(): DailySummary {
        val canonical = canonicalCurrency(currency)
        return if (currency == canonical) this else copy(currency = canonical)
    }

    private fun DailySummary.key() = SummaryKey(
        date = date,
        accountId = accountId,
        currency = canonicalCurrency(currency)
    )

    private fun MutableList<DailySummary>.sortSummaries() {
        sortWith(compareBy<DailySummary>({ it.date }, { it.accountId }, { canonicalCurrency(it.currency) }))
    }

    private fun canonicalCurrency(currency: String): String = currency.uppercase(Locale.ROOT)

    private fun failed(operation: String) = StoreWriteResult.Failed(
        operation = operation,
        reason = "SharedPreferences commit failed"
    )

    private data class SummaryKey(
        val date: String,
        val accountId: String,
        val currency: String
    )
}
