package com.balancesentinel.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetProviderTest {

    private lateinit var context: Context
    private val providerClasses = listOf(
        StaticWidgetProvider_2x1::class.java,
        StaticWidgetProvider_2x2::class.java,
        StaticWidgetProvider_3x1::class.java,
        StaticWidgetProvider_4x2::class.java,
        StaticWidgetProvider_5x1::class.java
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BalanceWidgetDataStore.clearAll(context)
    }

    @After
    fun tearDown() {
        BalanceWidgetDataStore.clearAll(context)
    }

    @Test
    fun `each provider instantiates without crashing`() {
        for (clazz in providerClasses) {
            val provider = clazz.getDeclaredConstructor().newInstance()
            assertNotNull("${clazz.simpleName} should instantiate", provider)
        }
    }

    @Test
    @org.junit.Ignore("Robolectric: AndroidKeyStore not available — requires instrumentation test")
    fun `widget renders without crash when no data`() {
        val provider = StaticWidgetProvider_2x1()
        val manager = AppWidgetManager.getInstance(context)
        try {
            provider.onUpdate(context, manager, intArrayOf(1))
        } catch (e: Exception) {
            fail("onUpdate with no data threw: ${e.message}")
        }
    }

    @Test
    @org.junit.Ignore("Robolectric: AndroidKeyStore not available — requires instrumentation test")
    fun `widget renders without crash when data exists`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "Test", "123.45", "CNY", true, "100.00", "20.00"
        )
        val provider = StaticWidgetProvider_2x1()
        val manager = AppWidgetManager.getInstance(context)
        try {
            provider.onUpdate(context, manager, intArrayOf(1))
        } catch (e: Exception) {
            fail("onUpdate with data threw: ${e.message}")
        }
    }

    @Test
    @org.junit.Ignore("Robolectric: AndroidKeyStore not available — requires instrumentation test")
    fun `all five providers handle onUpdate without crashing`() {
        BalanceWidgetDataStore.saveAccountBalance(
            context, "acc1", "Test", "100.00", "CNY", true, "0", "0"
        )
        for (clazz in providerClasses) {
            val provider = clazz.getDeclaredConstructor().newInstance()
            val manager = AppWidgetManager.getInstance(context)
            try {
                provider.onUpdate(context, manager, intArrayOf(1))
            } catch (e: Exception) {
                fail("${clazz.simpleName} crashed: ${e.message}")
            }
        }
    }
}
