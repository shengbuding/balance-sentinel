package com.balancesentinel.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InsightsChartLayoutTest {
    @Test
    fun `subscription axis places one hundred at top and zero at bottom`() {
        assertEquals(20f, quotaChartY(100f, top = 20f, bottom = 220f), 0.0001f)
        assertEquals(220f, quotaChartY(0f, top = 20f, bottom = 220f), 0.0001f)
        assertEquals(120f, quotaChartY(50f, top = 20f, bottom = 220f), 0.0001f)
    }

    @Test
    fun `subscription chart converts used percentage to remaining percentage`() {
        assertEquals(100f, quotaChartRemainingPercent(0f), 0.0001f)
        assertEquals(75f, quotaChartRemainingPercent(25f), 0.0001f)
        assertEquals(0f, quotaChartRemainingPercent(100f), 0.0001f)
    }

    @Test
    fun `subscription time ticks include start middle and end`() {
        assertEquals(listOf(0, 2, 4), quotaChartTickIndices(5))
        assertEquals(listOf(0, 1), quotaChartTickIndices(2))
    }

    @Test
    fun `latest refreshed account is shown only in all-account view`() {
        assertTrue(shouldShowQuotaLatestRefreshAccount(null))
        assertFalse(shouldShowQuotaLatestRefreshAccount("account-1"))
    }

    @Test
    fun `subscription chart prefers live then historical timestamp`() {
        assertEquals(300L, quotaChartCurrentTimestamp(300L, 200L, 100L))
        assertEquals(200L, quotaChartCurrentTimestamp(null, 200L, 100L))
        assertEquals(100L, quotaChartCurrentTimestamp(null, null, 100L))
    }

    @Test
    fun `positive balance axis starts at zero`() {
        val axis = insightsChartAxis(listOf(60f, 80f, 100f))
        assertEquals(0f, axis.min, 0.0001f)
        assertTrue(axis.max > 100f)
    }

    @Test
    fun `negative values keep a negative lower bound`() {
        val axis = insightsChartAxis(listOf(-20f, 5f))
        assertEquals(-20f, axis.min, 0.0001f)
        assertTrue(axis.max > 5f)
    }

    @Test
    fun `minimum label is below its guide and labels do not overlap`() {
        val placements = layoutInsightsChartLabels(
            requests = listOf(
                InsightsChartLabelRequest("max", 60f, preferBelow = false, priority = 0),
                InsightsChartLabelRequest("current", 96f, preferBelow = false, priority = 1),
                InsightsChartLabelRequest("min", 132f, preferBelow = true, priority = 2)
            ),
            top = 20f,
            bottom = 190f
        )
        assertEquals(3, placements.size)
        val minimum = placements.single { it.id == "min" }
        assertTrue(minimum.baseline > minimum.lineY)
        placements.forEachIndexed { index, left ->
            placements.drop(index + 1).forEach { right ->
                assertTrue(abs(left.baseline - right.baseline) >= 42f)
            }
        }
    }

    @Test
    fun `duplicate guide lines collapse to one label`() {
        val placements = layoutInsightsChartLabels(
            requests = listOf(
                InsightsChartLabelRequest("max", 80f, preferBelow = false, priority = 0),
                InsightsChartLabelRequest("current", 80f, preferBelow = false, priority = 1),
                InsightsChartLabelRequest("min", 80f, preferBelow = true, priority = 2)
            ),
            top = 20f,
            bottom = 190f
        )
        assertEquals(1, placements.size)
        assertEquals("max", placements.single().id)
    }
}
