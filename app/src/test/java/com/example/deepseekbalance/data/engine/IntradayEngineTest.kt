package com.example.deepseekbalance.data.engine

import com.example.deepseekbalance.data.model.RawRecord
import org.junit.Assert.*
import org.junit.Test

class IntradayEngineTest {

    @Test
    fun `empty records returns zero output`() {
        val input = IntradayInput(emptyList(), "CNY", null)
        val output = IntradayEngine.compute(input)
        assertEquals(0, output.dataPointCount)
        assertEquals(0f, output.billReport.consumed)
        assertEquals(0f, output.billReport.toppedUp)
        assertEquals(0f, output.billReport.netChange)
    }

    @Test
    fun `single record returns one point with no top-up or consumption`() {
        val now = System.currentTimeMillis()
        val record = RawRecord("acc1", now, "CNY", 100f, 10f, 90f)
        val input = IntradayInput(listOf(record), "CNY", null)
        val output = IntradayEngine.compute(input)

        assertEquals(1, output.dataPointCount)
        assertEquals(100f, output.trendPoints[0].actualBalance)
        assertFalse(output.trendPoints[0].isTopUp)
        assertEquals(0f, output.billReport.consumed)
        assertEquals(0f, output.billReport.toppedUp)
    }

    @Test
    fun `filters records older than 24 hours`() {
        val now = System.currentTimeMillis()
        val old = RawRecord("acc1", now - 25 * 3600_000L, "CNY", 100f, 0f, 100f)
        val recent = RawRecord("acc1", now - 1 * 3600_000L, "CNY", 90f, 0f, 90f)
        val input = IntradayInput(listOf(old, recent), "CNY", null)
        val output = IntradayEngine.compute(input)

        assertEquals(1, output.dataPointCount)
        assertEquals(90f, output.trendPoints[0].actualBalance)
    }

    @Test
    fun `normal consumption between two refreshes`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            RawRecord("acc1", now - 3600_000L, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", now, "CNY", 85f, 0f, 85f)
        )
        val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

        assertEquals(2, output.dataPointCount)
        assertEquals(85f, output.trendPoints[1].actualBalance)
        assertFalse(output.trendPoints[1].isTopUp)
        assertEquals(15f, output.billReport.consumed, 0.01f)
        assertEquals(0f, output.billReport.toppedUp, 0.01f)
        assertEquals(-15f, output.billReport.netChange, 0.01f)
    }

    @Test
    fun `top-up detection with exact integer delta`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            RawRecord("acc1", now - 1800_000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", now, "CNY", 130f, 0f, 80f)  // toppedUpBalance +30
        )
        val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

        assertTrue(output.trendPoints[1].isTopUp)
        assertEquals(30f, output.trendPoints[1].topUpAmount, 0.01f)
        assertEquals(30f, output.billReport.toppedUp, 0.01f)
        assertEquals(0f, output.billReport.consumed, 0.01f)  // no real consumption
        assertEquals(30f, output.billReport.netChange, 0.01f)
    }

    @Test
    fun `top-up rejects non-integer delta`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", now, "CNY", 110f, 0f, 60.5f)  // toppedUpBalance +10.5, not integer
        )
        val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

        assertFalse(output.trendPoints[1].isTopUp)
        assertEquals(0f, output.billReport.toppedUp, 0.01f)
    }

    @Test
    fun `top-up rejects delta less than 1`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", now, "CNY", 100.5f, 0f, 50.5f)  // toppedUpBalance +0.5
        )
        val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

        assertFalse(output.trendPoints[1].isTopUp)
    }

    @Test
    fun `mixed consumption and top-up in same interval`() {
        val now = System.currentTimeMillis()
        // User consumed 20, then topped up 30, net balance +10
        val records = listOf(
            RawRecord("acc1", now - 3600_000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", now, "CNY", 110f, 0f, 80f)  // balance +10, toppedUp +30
        )
        val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

        assertTrue(output.trendPoints[1].isTopUp)
        assertEquals(30f, output.trendPoints[1].topUpAmount, 0.01f)
        assertEquals(20f, output.billReport.consumed, 0.01f)  // 30 - 10 = 20 real consumption
        assertEquals(30f, output.billReport.toppedUp, 0.01f)
        assertEquals(10f, output.billReport.netChange, 0.01f)
    }

    @Test
    fun `grant detection`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 50f),
            RawRecord("acc1", now, "CNY", 110f, 10f, 50f)  // grantedBalance +10
        )
        val output = IntradayEngine.compute(IntradayInput(records, "CNY", null))

        assertTrue(output.trendPoints[1].isGrant)
        assertEquals(10f, output.trendPoints[1].grantAmount, 0.01f)
        assertEquals(10f, output.billReport.granted, 0.01f)
    }

    @Test
    fun `currency filter applies correctly`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            RawRecord("acc1", now - 1000L, "CNY", 100f, 0f, 100f),
            RawRecord("acc1", now, "USD", 50f, 0f, 50f)
        )
        val output = IntradayEngine.compute(IntradayInput(records, "USD", null))

        assertEquals(1, output.dataPointCount)
        assertEquals(50f, output.trendPoints[0].actualBalance)
    }
}
