package com.balancesentinel.app.widget

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SparklineDrawerTest {

    @Test
    fun `draw returns null for less than 2 values`() {
        assertNull(SparklineDrawer.draw(emptyList(), 100, 50, Color.BLUE, Color.CYAN))
        assertNull(SparklineDrawer.draw(listOf(1f), 100, 50, Color.BLUE, Color.CYAN))
    }

    @Test
    fun `draw returns null for zero or negative dimensions`() {
        val values = listOf(1f, 2f, 3f)
        assertNull(SparklineDrawer.draw(values, 0, 50, Color.BLUE, Color.CYAN))
        assertNull(SparklineDrawer.draw(values, 100, 0, Color.BLUE, Color.CYAN))
        assertNull(SparklineDrawer.draw(values, 100, -1, Color.BLUE, Color.CYAN))
    }

    @Test
    fun `draw returns valid bitmap for normal input`() {
        val values = listOf(10f, 20f, 15f, 30f, 25f)
        val bitmap = SparklineDrawer.draw(values, 200, 100, Color.BLUE, Color.CYAN)

        assertNotNull(bitmap)
        assertEquals(200, bitmap!!.width)
        assertEquals(100, bitmap.height)
    }

    @Test
    fun `draw returns valid bitmap for two values`() {
        val values = listOf(5f, 10f)
        val bitmap = SparklineDrawer.draw(values, 100, 50, Color.RED, Color.GREEN)

        assertNotNull(bitmap)
        assertEquals(100, bitmap!!.width)
        assertEquals(50, bitmap.height)
    }

    @Test
    fun `draw handles flat line when all values equal`() {
        val values = listOf(42f, 42f, 42f, 42f)
        val bitmap = SparklineDrawer.draw(values, 100, 60, Color.BLUE, Color.CYAN)

        assertNotNull(bitmap)
        assertEquals(100, bitmap!!.width)
    }

    @Test
    fun `draw handles negative values`() {
        val values = listOf(-10f, -5f, -8f, -2f)
        val bitmap = SparklineDrawer.draw(values, 100, 50, Color.BLUE, Color.CYAN)

        assertNotNull(bitmap)
    }

    @Test
    fun `draw handles large value range`() {
        val values = listOf(0f, 100000f, 50000f)
        val bitmap = SparklineDrawer.draw(values, 300, 100, Color.BLUE, Color.CYAN)

        assertNotNull(bitmap)
    }
}
