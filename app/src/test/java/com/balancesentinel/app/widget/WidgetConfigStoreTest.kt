package com.balancesentinel.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetConfigStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetConfigStore.clearAll(context)
    }

    @After
    fun tearDown() {
        WidgetConfigStore.clearAll(context)
    }

    @Test
    fun `getConfig returns null when no config exists`() {
        assertNull(WidgetConfigStore.getConfig(context, 1))
    }

    @Test
    fun `saveConfig and getConfig roundtrip`() {
        WidgetConfigStore.saveConfig(context, 1, "acc123", "CNY")
        val config = WidgetConfigStore.getConfig(context, 1)
        assertNotNull(config)
        assertEquals("acc123", config!!.accountId)
        assertEquals("CNY", config.currency)
    }

    @Test
    fun `saveConfig overwrites existing config`() {
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")
        WidgetConfigStore.saveConfig(context, 1, "acc2", "USD")
        val config = WidgetConfigStore.getConfig(context, 1)
        assertEquals("acc2", config!!.accountId)
        assertEquals("USD", config.currency)
    }

    @Test
    fun `multiple widgets can have different configs`() {
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")
        WidgetConfigStore.saveConfig(context, 2, "acc2", "USD")
        WidgetConfigStore.saveConfig(context, 3, "acc3", "EUR")

        assertEquals("acc1", WidgetConfigStore.getConfig(context, 1)!!.accountId)
        assertEquals("acc2", WidgetConfigStore.getConfig(context, 2)!!.accountId)
        assertEquals("acc3", WidgetConfigStore.getConfig(context, 3)!!.accountId)
    }

    @Test
    fun `removeConfig deletes specific widget config`() {
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")
        WidgetConfigStore.saveConfig(context, 2, "acc2", "USD")

        WidgetConfigStore.removeConfig(context, 1)

        assertNull(WidgetConfigStore.getConfig(context, 1))
        assertNotNull(WidgetConfigStore.getConfig(context, 2))
    }

    @Test
    fun `removeConfig on non-existent widget does not throw`() {
        WidgetConfigStore.removeConfig(context, 999)
        // Should not throw
    }

    @Test
    fun `clearAll removes all configs`() {
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")
        WidgetConfigStore.saveConfig(context, 2, "acc2", "USD")
        WidgetConfigStore.saveConfig(context, 3, "acc3", "EUR")

        WidgetConfigStore.clearAll(context)

        assertNull(WidgetConfigStore.getConfig(context, 1))
        assertNull(WidgetConfigStore.getConfig(context, 2))
        assertNull(WidgetConfigStore.getConfig(context, 3))
    }

    @Test
    fun `WidgetConfig TOTAL_ACCOUNT_ID constant`() {
        assertEquals("__total__", WidgetConfig.TOTAL_ACCOUNT_ID)
    }

    @Test
    fun `corrupted data returns empty map gracefully`() {
        // Write invalid JSON directly
        val prefs = context.getSharedPreferences("widget_configs", Context.MODE_PRIVATE)
        prefs.edit().putString("configs", "not-valid-json").apply()

        // Should not crash, returns null
        assertNull(WidgetConfigStore.getConfig(context, 1))
    }
}
