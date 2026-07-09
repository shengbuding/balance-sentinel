package com.balancesentinel.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FormatUtilsTest {

    // ═══════════════════════════════════════════════════════════
    // formatAmount
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `formatAmount formats integer string to 2 decimal places`() {
        assertEquals("10.00", FormatUtils.formatAmount("10"))
    }

    @Test
    fun `formatAmount formats decimal string to 2 decimal places`() {
        assertEquals("10.50", FormatUtils.formatAmount("10.5"))
    }

    @Test
    fun `formatAmount formats number with more than 2 decimals`() {
        assertEquals("3.14", FormatUtils.formatAmount("3.14159"))
    }

    @Test
    fun `formatAmount formats zero`() {
        assertEquals("0.00", FormatUtils.formatAmount("0"))
    }

    @Test
    fun `formatAmount formats negative number`() {
        assertEquals("-5.50", FormatUtils.formatAmount("-5.5"))
    }

    @Test
    fun `formatAmount returns original string on invalid input`() {
        assertEquals("abc", FormatUtils.formatAmount("abc"))
    }

    @Test
    fun `formatAmount returns original string on empty input`() {
        assertEquals("", FormatUtils.formatAmount(""))
    }

    @Test
    fun `formatAmount formats large number`() {
        assertEquals("1234567.89", FormatUtils.formatAmount("1234567.89"))
    }

    // ═══════════════════════════════════════════════════════════
    // currencySymbol
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `currencySymbol returns yen for CNY`() {
        assertEquals("¥", FormatUtils.currencySymbol("CNY"))
    }

    @Test
    fun `currencySymbol returns yen for lowercase cny`() {
        assertEquals("¥", FormatUtils.currencySymbol("cny"))
    }

    @Test
    fun `currencySymbol returns dollar for USD`() {
        assertEquals("$", FormatUtils.currencySymbol("USD"))
    }

    @Test
    fun `currencySymbol returns euro for EUR`() {
        assertEquals("€", FormatUtils.currencySymbol("EUR"))
    }

    @Test
    fun `currencySymbol returns original for unknown currency`() {
        assertEquals("GBP", FormatUtils.currencySymbol("GBP"))
    }

    // ═══════════════════════════════════════════════════════════
    // formatInterval
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `formatInterval formats seconds`() {
        assertEquals("30秒", FormatUtils.formatInterval(30))
    }

    @Test
    fun `formatInterval formats exact minutes`() {
        assertEquals("5分钟", FormatUtils.formatInterval(300))
    }

    @Test
    fun `formatInterval formats minutes and seconds`() {
        assertEquals("2分30秒", FormatUtils.formatInterval(150))
    }

    @Test
    fun `formatInterval formats 1 second`() {
        assertEquals("1秒", FormatUtils.formatInterval(1))
    }

    @Test
    fun `formatInterval formats 0 seconds`() {
        assertEquals("0秒", FormatUtils.formatInterval(0))
    }

    // ═══════════════════════════════════════════════════════════
    // formatFullTime
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `formatFullTime returns formatted timestamp`() {
        // 2026-07-09 12:00:00 UTC+8 = 1752038400000
        val result = FormatUtils.formatFullTime(1752038400000L)
        // Format is "MM-dd HH:mm" in local timezone
        assertTrue("Should contain '-' separator", result.contains("-"))
        assertTrue("Should contain ':' separator", result.contains(":"))
    }

    @Test
    fun `formatFullTime returns string for zero timestamp`() {
        val result = FormatUtils.formatFullTime(0L)
        assertTrue("Should not be empty", result.isNotEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // methodLabel (requires Context via Robolectric)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `methodLabel returns label for known methods`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val labels = listOf("alarm_clock", "exact", "inexact", "failed", "foreground_service")
        for (method in labels) {
            val label = FormatUtils.methodLabel(context, method)
            assertTrue("Label for '$method' should not be empty", label.isNotEmpty())
            assertNotEquals("Label should not equal raw method name", method, label)
        }
    }

    @Test
    fun `methodLabel returns fallback for unknown method`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = FormatUtils.methodLabel(context, "unknown_method")
        // Should return the method name itself or a fallback string
        assertTrue("Should not be empty", result.isNotEmpty())
    }

    @Test
    fun `methodLabel returns none label for empty string`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = FormatUtils.methodLabel(context, "")
        assertTrue("Should not be empty", result.isNotEmpty())
    }
}
