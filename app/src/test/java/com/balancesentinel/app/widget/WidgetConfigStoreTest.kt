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
    fun `save and retrieve single config`() {
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")
        val config = WidgetConfigStore.getConfig(context, 1)
        assertNotNull(config)
        assertEquals("acc1", config!!.accountId)
        assertEquals("CNY", config.currency)
    }

    @Test
    fun `total balance option saves and retrieves correctly`() {
        WidgetConfigStore.saveConfig(context, 2, WidgetConfig.TOTAL_ACCOUNT_ID, "CNY")
        val config = WidgetConfigStore.getConfig(context, 2)
        assertNotNull(config)
        assertEquals(WidgetConfig.TOTAL_ACCOUNT_ID, config!!.accountId)
    }

    @Test
    fun `multiple widget instances do not interfere`() {
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")
        WidgetConfigStore.saveConfig(context, 2, "acc2", "USD")
        assertEquals("acc1", WidgetConfigStore.getConfig(context, 1)!!.accountId)
        assertEquals("acc2", WidgetConfigStore.getConfig(context, 2)!!.accountId)
    }

    @Test
    fun `remove config returns null after removal`() {
        WidgetConfigStore.saveConfig(context, 1, "acc1", "CNY")
        WidgetConfigStore.removeConfig(context, 1)
        assertNull(WidgetConfigStore.getConfig(context, 1))
    }

    @Test
    fun `unconfigured widget returns null`() {
        assertNull(WidgetConfigStore.getConfig(context, 999))
    }
}
