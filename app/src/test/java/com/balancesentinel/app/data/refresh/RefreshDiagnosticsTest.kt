package com.balancesentinel.app.data.refresh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RefreshDiagnosticsTest {
    @Before
    fun setUp() {
        RefreshDiagnostics.resetForTests()
    }

    @After
    fun tearDown() {
        RefreshDiagnostics.resetForTests()
    }

    @Test
    fun `active stage reports elapsed time and terminal event clears it`() {
        RefreshDiagnostics.record(
            stage = RefreshDiagnosticStage.FETCH_STARTED,
            runId = "run-1",
            accountId = "account-1",
            trigger = RefreshTrigger.MANUAL_ALL,
            generation = 4L,
            timestamp = 100L
        )
        RefreshDiagnostics.record(
            stage = RefreshDiagnosticStage.FETCH_RETURNED,
            runId = "run-1",
            accountId = "account-1",
            timestamp = 160L,
            detail = "SUCCESS"
        )

        val (active, events) = RefreshDiagnostics.snapshot()
        assertEquals(1, active.size)
        assertEquals(RefreshDiagnosticStage.FETCH_RETURNED, active.single().stage)
        assertEquals(60L, active.single().previousStageElapsedMs)
        assertEquals(2, events.size)
        val report = RefreshDiagnostics.toReportText(now = 200L)
        assertTrue(report.contains("stage=FETCH_RETURNED"))
        assertTrue(report.contains("ageMs=40"))
        assertTrue(report.contains("trigger=MANUAL_ALL"))

        RefreshDiagnostics.record(
            stage = RefreshDiagnosticStage.ACCOUNT_COMPLETED,
            runId = "run-1",
            accountId = "account-1",
            timestamp = 210L,
            terminal = true
        )

        assertTrue(RefreshDiagnostics.snapshot().first.isEmpty())
    }

    @Test
    fun `event history is bounded`() {
        repeat(405) { index ->
            RefreshDiagnostics.record(
                stage = RefreshDiagnosticStage.RUN_CREATED,
                runId = "run-$index",
                timestamp = index.toLong()
            )
        }

        val events = RefreshDiagnostics.snapshot().second
        assertEquals(400, events.size)
        assertEquals(6L, events.first().sequence)
        assertEquals(405L, events.last().sequence)
    }
}
