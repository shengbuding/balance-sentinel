package com.balancesentinel.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.AccountLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `widget visibility excludes deleted persisted balances`() {
        val balances = listOf(balance("active"), balance("deleted"))
        val state = AccountLoadState.Ready(listOf(account("active")))

        assertEquals(listOf("active"), WidgetBalanceVisibility.filter(state, balances).map { it.accountId })
    }

    @Test
    fun `widget visibility fails closed for corrupt account state`() {
        val state = AccountLoadState.Corrupt(DataCorruptionException("corrupt"))

        assertTrue(WidgetBalanceVisibility.filter(state, listOf(balance("stale"))).isEmpty())
    }

    @Test
    fun `widget visibility retains normal balances for ready accounts`() {
        val state = AccountLoadState.Ready(listOf(account("active")))

        assertEquals(25.0, WidgetBalanceVisibility.filter(state, listOf(balance("active")))
            .single().totalBalance.toDouble(), 0.0)
    }

    @Test
    fun `suspending widget dispatch returns before refresh and finishes afterward`() = runTest {
        val release = CompletableDeferred<Unit>()
        var finished = false

        WidgetRefreshCoroutineDispatcher(this).dispatch(
            action = { release.await() },
            finish = { finished = true }
        )

        assertFalse(finished)
        release.complete(Unit)
        runCurrent()
        assertTrue(finished)
    }

    @Test
    fun `suspending widget dispatch finishes when refresh fails`() = runTest {
        var finished = false

        WidgetRefreshCoroutineDispatcher(this).dispatch(
            action = { error("refresh failed") },
            finish = { finished = true }
        )
        runCurrent()

        assertTrue(finished)
    }

    @Test
    fun `suspending widget dispatch finishes when scope is cancelled`() = runTest {
        val release = CompletableDeferred<Unit>()
        var finished = false
        val childScope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())

        WidgetRefreshCoroutineDispatcher(childScope).dispatch(
            action = { release.await() },
            finish = { finished = true }
        )
        runCurrent()
        childScope.cancel()
        runCurrent()

        assertTrue(finished)
    }

    private fun account(id: String) = AccountInfo(id, id, "key-$id", ProviderType.DEEPSEEK, revision = 1)
    private fun balance(id: String) = AccountBalance(id, id, "25", "CNY", true, "", "", 1L)

    // Finding 5 RED: WidgetRefreshDispatcher must guarantee finish callback
    // on both success and failure. On current inert shell (empty dispatch()),
    // these tests FAIL because action/finish are never called.

    @Test
    fun `dispatcher invokes action and calls finish on success`() {
        var actionExecuted = false
        var finishCalled = false

        WidgetRefreshDispatcher(
            action = { actionExecuted = true },
            finish = { finishCalled = true }
        ).dispatch()

        assertTrue("action must execute", actionExecuted)
        assertTrue("finish must be called on success", finishCalled)
    }

    @Test
    fun `dispatcher calls finish even when action throws`() {
        var finishCalled = false

        try {
            WidgetRefreshDispatcher(
                action = { throw RuntimeException("refresh failed") },
                finish = { finishCalled = true }
            ).dispatch()
        } catch (_: RuntimeException) {}

        assertTrue("finish must be called even on failure", finishCalled)
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
