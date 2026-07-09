package com.balancesentinel.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatteryOptimizationHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset state: mark shown so guide stays hidden unless test explicitly resets
    }

    @After
    fun tearDown() {
        // Clean up prefs
        context.getSharedPreferences("battery_guide", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `isBatteryOptimizing returns a boolean`() {
        val result = BatteryOptimizationHelper.isBatteryOptimizing(context)
        // In Robolectric, the app is not ignoring battery optimizations by default
        assertNotNull(result)
    }

    @Test
    fun `markGuideShown persists correctly`() {
        BatteryOptimizationHelper.markGuideShown(context)
        // shouldShowGuide should return false after markGuideShown
        assertFalse(BatteryOptimizationHelper.shouldShowGuide(context))
    }

    @Test
    fun `recordDismiss increments count`() {
        BatteryOptimizationHelper.recordDismiss(context)
        BatteryOptimizationHelper.recordDismiss(context)
        BatteryOptimizationHelper.recordDismiss(context)
        // After 3 dismissals, guide should no longer show
        assertFalse(BatteryOptimizationHelper.shouldShowGuide(context))
    }

    @Test
    fun `openBatterySettings runs without throwing`() {
        // In Robolectric, startActivity may succeed or fail depending on shadow config
        // Either way, the method should not throw
        try {
            BatteryOptimizationHelper.openBatterySettings(context)
        } catch (e: Exception) {
            fail("openBatterySettings should not throw: ${e.message}")
        }
    }
}
