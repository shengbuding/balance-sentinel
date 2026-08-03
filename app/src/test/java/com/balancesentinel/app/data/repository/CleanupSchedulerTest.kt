package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CleanupSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    @After
    fun tearDown() {
        RawRecordStore.clear(context)
        DailySummaryStore.clear(context)
    }

    // Mutation caught: skipping a pair merely because a summary key already exists.
    @Test
    fun `late record completely recomputes and replaces the retained pair summary`() = runTest {
        val early = record("acct", "USD", at("2026-07-31T13:00:00Z"), 10f)
        val late = record("acct", "USD", at("2026-07-31T23:00:00Z"), 7f)
        RawRecordStore.addRecords(context, listOf(early))

        CleanupScheduler.runCleanup(context, at("2026-08-01T12:00:00Z"), ZoneOffset.UTC)
        RawRecordStore.addRecords(context, listOf(late))

        val report = CleanupScheduler.runCleanup(
            context,
            at("2026-08-01T13:00:00Z"),
            ZoneOffset.UTC
        )

        val summaries = DailySummaryStore.getSummaries(context)
        assertEquals(1, summaries.size)
        val summary = summaries.single()
        assertEquals(2, summary.sampleCount)
        assertEquals(10f, summary.open)
        assertEquals(7f, summary.close)
        assertEquals(setOf("2026-07-31"), report.archivedDates)
        assertEquals(listOf(early, late), RawRecordStore.getAllRecords(context))
    }

    // Mutation caught: deleting failed-summary source or aborting all later dates.
    @Test
    fun `failed summary commit retains its date and a later date still completes`() = runTest {
        val first = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        val second = record("acct", "USD", at("2026-08-02T10:00:00Z"), 8f)
        RawRecordStore.addRecords(context, listOf(first, second))
        val fault = FaultPrefsContext(
            context,
            failCommit = { name, count -> name == DAILY_PREFS && count == 1 }
        )

        val report = CleanupScheduler.runCleanup(fault, NOW, ZoneOffset.UTC)

        assertEquals(listOf(first), RawRecordStore.getAllRecords(context))
        assertTrue(report.archivedDates.contains("2026-08-02"))
        assertTrue(report.failures.any {
            it.date == "2026-08-01" && it.stage == CleanupStage.WRITE_SUMMARY
        })
    }

    // Mutation caught: verifying only key and sample count before deleting source.
    @Test
    fun `full summary readback mismatch retains source and a later date still completes`() = runTest {
        val first = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        val second = record("acct", "USD", at("2026-08-02T10:00:00Z"), 8f)
        RawRecordStore.addRecords(context, listOf(first, second))
        var tampered = false
        val fault = FaultPrefsContext(
            context,
            transformRead = { name, _, raw ->
                if (name == DAILY_PREFS && !tampered && raw != null) {
                    tampered = true
                    tamperClosingValue(raw)
                } else {
                    raw
                }
            },
            transformReadsAfterCommitOnly = true
        )

        val report = CleanupScheduler.runCleanup(fault, NOW, ZoneOffset.UTC)

        assertEquals(listOf(first), RawRecordStore.getAllRecords(context))
        assertTrue(report.archivedDates.contains("2026-08-02"))
        assertTrue(report.failures.any {
            it.date == "2026-08-01" && it.stage == CleanupStage.VERIFY_SUMMARY
        })
    }

    // Mutation caught: swallowing a per-date source read failure or aborting later dates.
    @Test
    fun `source read failure is bounded and does not abort a later date`() = runTest {
        val first = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        val second = record("acct", "USD", at("2026-08-02T10:00:00Z"), 8f)
        RawRecordStore.addRecords(context, listOf(first, second))
        val secret = "Token=should-never-appear"
        val fault = FaultPrefsContext(
            context,
            failRead = { name, count ->
                if (name == RAW_PREFS && count == 2) IllegalStateException(secret) else null
            }
        )

        val report = CleanupScheduler.runCleanup(fault, NOW, ZoneOffset.UTC)

        assertTrue(report.archivedDates.contains("2026-08-02"))
        val failure = report.failures.single { it.stage == CleanupStage.READ_SOURCE }
        assertEquals("2026-08-01", failure.date)
        assertFalse(failure.reason.contains(secret))
        assertTrue(failure.reason.length <= 160)
        assertEquals(listOf(first), RawRecordStore.getAllRecords(context))
    }

    // Mutation caught: treating failed source deletion as success or aborting later dates.
    @Test
    fun `delete failure retains its snapshot and a later date still completes`() = runTest {
        val first = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        val second = record("acct", "USD", at("2026-08-02T10:00:00Z"), 8f)
        RawRecordStore.addRecords(context, listOf(first, second))
        val fault = FaultPrefsContext(
            context,
            failCommit = { name, count -> name == RAW_PREFS && count == 1 }
        )

        val report = CleanupScheduler.runCleanup(fault, NOW, ZoneOffset.UTC)

        assertEquals(listOf(first), RawRecordStore.getAllRecords(context))
        assertTrue(report.archivedDates.contains("2026-08-02"))
        assertTrue(report.failures.any {
            it.date == "2026-08-01" && it.stage == CleanupStage.DELETE_SOURCE
        })
    }

    // Mutation caught: deleting by date after a successful write instead of the immutable snapshot.
    @Test
    fun `record arriving after summary commit survives exact snapshot deletion`() = runTest {
        val source = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        val arriving = record("acct", "CNY", at("2026-08-01T10:00:01Z"), 11f)
        RawRecordStore.addRecords(context, listOf(source))
        var inserted = false
        val fault = FaultPrefsContext(
            context,
            afterCommit = { name, count ->
                if (!inserted && name == DAILY_PREFS && count == 1) {
                    inserted = true
                    RawRecordStore.addRecords(context, listOf(arriving))
                }
            }
        )

        val report = CleanupScheduler.runCleanup(fault, NOW, ZoneOffset.UTC)

        assertEquals(listOf(arriving), RawRecordStore.getAllRecords(context))
        assertEquals(1, report.deletedRecordCount)
        assertEquals(1, report.retainedRecordCount)
    }

    // Mutation caught: assuming commit=false means an exact deletion was not applied.
    @Test
    fun `applied deletion reported as failed restores snapshot without losing a late arrival`() = runTest {
        val source = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        val arriving = record("acct", "CNY", at("2026-08-01T10:00:01Z"), 11f)
        RawRecordStore.addRecords(context, listOf(source))
        var inserted = false
        val fault = FaultPrefsContext(
            context,
            applyThenFailCommit = { name, count -> name == RAW_PREFS && count == 1 },
            afterCommit = { name, count ->
                if (!inserted && name == RAW_PREFS && count == 1) {
                    inserted = true
                    RawRecordStore.addRecords(context, listOf(arriving))
                }
            }
        )

        val report = CleanupScheduler.runCleanup(fault, NOW, ZoneOffset.UTC)

        assertEquals(listOf(source, arriving), RawRecordStore.getAllRecords(context))
        assertEquals(0, report.deletedRecordCount)
        assertEquals(2, report.retainedRecordCount)
        assertTrue(report.failures.any {
            it.date == "2026-08-01" && it.stage == CleanupStage.DELETE_SOURCE
        })
    }

    // Mutation caught: deleting only the old members of a mixed-age snapshot.
    @Test
    fun `deletion waits until every record in the snapshot is older than 24 hours`() = runTest {
        val old = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        val young = record("acct", "CNY", at("2026-08-01T13:00:00Z"), 11f)
        RawRecordStore.addRecords(context, listOf(old, young))

        val report = CleanupScheduler.runCleanup(
            context,
            at("2026-08-02T12:00:00Z"),
            ZoneOffset.UTC
        )

        assertEquals(listOf(old, young), RawRecordStore.getAllRecords(context))
        assertEquals(0, report.deletedRecordCount)
        assertEquals(setOf("2026-08-01"), report.archivedDates)
    }

    // Mutation caught: using the host zone or case-sensitive currency groups.
    @Test
    fun `one injected zone classifies both sides of midnight and canonicalizes currency`() = runTest {
        val zone = ZoneId.of("America/Los_Angeles")
        val beforeMidnightLower = record("acct", "usd", at("2026-08-02T06:58:00Z"), 10f)
        val beforeMidnightUpper = record("acct", "USD", at("2026-08-02T06:59:00Z"), 9f)
        val afterMidnight = record("acct", "USD", at("2026-08-02T07:01:00Z"), 8f)
        RawRecordStore.addRecords(
            context,
            listOf(beforeMidnightLower, beforeMidnightUpper, afterMidnight)
        )

        CleanupScheduler.runCleanup(
            context,
            at("2026-08-02T08:30:00Z"),
            zone
        )

        val summaries = DailySummaryStore.getSummaries(context)
        assertEquals(1, summaries.size)
        val summary = summaries.single()
        assertEquals("2026-08-01", summary.date)
        assertEquals("USD", summary.currency)
        assertEquals(2, summary.sampleCount)
        assertEquals(3, RawRecordStore.getAllRecords(context).size)
    }

    // Mutation caught: running continuity before archival or swallowing its durable failure.
    @Test
    fun `continuity runs after archive attempts and reports its failed write`() = runTest {
        val source = record("acct", "CNY", at("2026-08-01T10:00:00Z"), 12f)
        RawRecordStore.addRecords(context, listOf(source))
        val events = mutableListOf<String>()
        val fault = FaultPrefsContext(
            context,
            events = events,
            failCommit = { name, count -> name == DAILY_PREFS && count == 2 }
        )

        val report = CleanupScheduler.runCleanup(fault, NOW, ZoneOffset.UTC)

        assertEquals(
            listOf("commit:$DAILY_PREFS", "commit:$RAW_PREFS", "commit:$DAILY_PREFS"),
            events
        )
        assertTrue(RawRecordStore.getAllRecords(context).isEmpty())
        assertTrue(report.archivedDates.contains("2026-08-01"))
        val failure = report.failures.single()
        assertEquals("2026-08-02", failure.date)
        assertEquals(CleanupStage.WRITE_SUMMARY, failure.stage)
        assertTrue(failure.reason.startsWith("ENSURE_CONTINUITY:"))
    }

    private fun record(
        accountId: String,
        currency: String,
        timestamp: Long,
        balance: Float
    ) = RawRecord(
        accountId = accountId,
        timestamp = timestamp,
        currency = currency,
        totalBalance = balance,
        grantedBalance = 0f,
        toppedUpBalance = balance
    )

    private fun at(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun tamperClosingValue(raw: String): String {
        val serializer = ListSerializer(DailySummary.serializer())
        val summaries = JSON.decodeFromString(serializer, raw)
        return JSON.encodeToString(
            serializer,
            summaries.mapIndexed { index, summary ->
                if (index == 0) summary.copy(close = summary.close + 1f) else summary
            }
        )
    }

    private class FaultPrefsContext(
        base: Context,
        private val events: MutableList<String> = mutableListOf(),
        private val failCommit: (String, Int) -> Boolean = { _, _ -> false },
        private val applyThenFailCommit: (String, Int) -> Boolean = { _, _ -> false },
        private val failRead: (String, Int) -> Throwable? = { _, _ -> null },
        private val transformRead: (String, Int, String?) -> String? = { _, _, raw -> raw },
        private val transformReadsAfterCommitOnly: Boolean = false,
        private val afterCommit: (String, Int) -> Unit = { _, _ -> }
    ) : ContextWrapper(base) {
        private val commitCounts = mutableMapOf<String, Int>()
        private val readCounts = mutableMapOf<String, Int>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            return object : SharedPreferences by delegate {
                override fun getString(key: String?, defValue: String?): String? {
                    val count = readCounts.getOrDefault(name, 0) + 1
                    readCounts[name] = count
                    failRead(name, count)?.let { throw it }
                    val raw = delegate.getString(key, defValue)
                    val mayTransform = !transformReadsAfterCommitOnly ||
                        commitCounts.getOrDefault(name, 0) > 0
                    return if (mayTransform) transformRead(name, count, raw) else raw
                }

                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String?,
                            value: String?
                        ): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }

                        override fun remove(key: String?): SharedPreferences.Editor {
                            editor.remove(key)
                            return this
                        }

                        override fun clear(): SharedPreferences.Editor {
                            editor.clear()
                            return this
                        }

                        override fun commit(): Boolean {
                            val count = commitCounts.getOrDefault(name, 0) + 1
                            commitCounts[name] = count
                            events += "commit:$name"
                            if (failCommit(name, count)) return false
                            if (applyThenFailCommit(name, count)) {
                                val committed = editor.commit()
                                if (committed) afterCommit(name, count)
                                return false
                            }
                            val committed = editor.commit()
                            if (committed) afterCommit(name, count)
                            return committed
                        }

                        override fun apply() {
                            events += "apply:$name"
                            editor.apply()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val RAW_PREFS = "raw_records"
        const val DAILY_PREFS = "daily_summaries"
        val NOW: Long = Instant.parse("2026-08-03T12:00:00Z").toEpochMilli()
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
