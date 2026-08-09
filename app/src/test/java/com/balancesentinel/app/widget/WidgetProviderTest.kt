package com.balancesentinel.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.R
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.AccountRefreshResult
import com.balancesentinel.app.data.refresh.AccountStoreRead
import com.balancesentinel.app.data.refresh.RefreshBatchResult
import com.balancesentinel.app.data.refresh.RefreshGateway
import com.balancesentinel.app.data.refresh.RefreshTrigger
import com.balancesentinel.app.data.refresh.deriveRefreshBatchAggregate
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.testing.MutableSettingsRepository
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
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetProviderTest {

    private lateinit var context: Context
    private lateinit var database: WalletDatabase
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
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        WalletDatabaseProvider.installForTests(database)
        SettingsRepositoryProvider.factory = { MutableSettingsRepository() }
        BalanceWidgetDataStore.clearAll(context)
        WidgetConfigStore.clearAll(context)
    }

    @After
    fun tearDown() {
        StaticWidgetProvider.accountStateLoaderOverride = null
        WidgetRefreshReceiver.resetTestOverrides()
        WidgetRefreshStatusStore.clearForTests(context)
        SettingsRepositoryProvider.resetForTests()
        WalletDatabaseProvider.clearForTests()
        BalanceWidgetDataStore.clearAll(context)
        WidgetConfigStore.clearAll(context)
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
    fun `provider entrypoint renders only balances from loaded accounts`() {
        StaticWidgetProvider.accountStateLoaderOverride = {
            AccountLoadState.Ready(listOf(account("active")))
        }
        val manager = AppWidgetManager.getInstance(context)
        val widgetId = Shadows.shadowOf(manager).createWidget(
            StaticWidgetProvider_2x1::class.java,
            R.layout.widget_balance_compact
        )
        WidgetConfigStore.saveConfig(context, widgetId, WidgetConfig.TOTAL_ACCOUNT_ID, "CNY")
        BalanceWidgetDataStore.saveAccountBalance(
            context, "active", "Active", "25.00", "CNY", true, "", ""
        )
        BalanceWidgetDataStore.saveAccountBalance(
            context, "deleted", "Deleted", "75.00", "CNY", true, "", ""
        )
        val provider = StaticWidgetProvider_2x1()
        val pending = attachPendingResult(provider)

        provider.onUpdate(context, manager, intArrayOf(widgetId))
        val rendered = awaitWidgetBalance(manager, widgetId) { it.contains("25.00") }
        pending.future.get(2, TimeUnit.SECONDS)
        assertFalse("deleted balance must not affect the rendered aggregate", rendered.contains("100.00"))
    }

    @Test
    fun `configured stale account is not rendered as available`() {
        StaticWidgetProvider.accountStateLoaderOverride = {
            AccountLoadState.Ready(listOf(account("active")))
        }
        val manager = AppWidgetManager.getInstance(context)
        val widgetId = Shadows.shadowOf(manager).createWidget(
            StaticWidgetProvider_2x1::class.java,
            R.layout.widget_balance_compact
        )
        WidgetConfigStore.saveConfig(context, widgetId, "active", "CNY")
        BalanceWidgetDataStore.saveAccountBalance(
            context, "active", "Active", "25.00", "CNY", true, "", ""
        )
        BalanceWidgetDataStore.markAccountStale(context, "active", "timeout")
        val provider = StaticWidgetProvider_2x1()
        val pending = attachPendingResult(provider)

        provider.onUpdate(context, manager, intArrayOf(widgetId))
        val rendered = awaitWidgetStatus(manager, widgetId)
        pending.future.get(2, TimeUnit.SECONDS)

        assertEquals(context.getString(R.string.widget_status_insufficient), rendered)
    }

    @Test
    fun `receiver entrypoint returns while refresh is suspended and finishes afterward`() {
        StaticWidgetProvider.accountStateLoaderOverride = { AccountLoadState.Ready(emptyList()) }
        val manager = AppWidgetManager.getInstance(context)
        Shadows.shadowOf(manager).createWidget(
            StaticWidgetProvider_2x1::class.java,
            R.layout.widget_balance_compact
        )
        val started = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        var observedTrigger: RefreshTrigger? = null
        WidgetRefreshReceiver.refreshGatewayProvider = {
            gateway { trigger ->
                observedTrigger = trigger
                started.countDown()
                release.await()
                emptyList()
            }
        }
        val receiver = WidgetRefreshReceiver()
        val pending = attachPendingResult(receiver)

        receiver.onReceive(context, WidgetRefreshIntents.manual(context))

        assertTrue("refresh must start through onReceive", started.await(1, TimeUnit.SECONDS))
        assertEquals(RefreshTrigger.WIDGET, observedTrigger)
        assertFalse("onReceive must leave the async result open while refresh is suspended", pending.future.isDone)
        release.complete(Unit)
        pending.future.get(2, TimeUnit.SECONDS)
        assertTrue(pending.future.isDone)
    }

    @Test
    fun `receiver entrypoint finishes its async result when refresh fails`() {
        StaticWidgetProvider.accountStateLoaderOverride = { AccountLoadState.Ready(emptyList()) }
        val manager = AppWidgetManager.getInstance(context)
        Shadows.shadowOf(manager).createWidget(
            StaticWidgetProvider_2x1::class.java,
            R.layout.widget_balance_compact
        )
        val attempted = CountDownLatch(1)
        WidgetRefreshReceiver.refreshGatewayProvider = {
            gateway {
                attempted.countDown()
                error("refresh failed")
            }
        }
        val receiver = WidgetRefreshReceiver()
        val pending = attachPendingResult(receiver)

        receiver.onReceive(context, WidgetRefreshIntents.manual(context))

        assertTrue("refresh must be attempted through onReceive", attempted.await(1, TimeUnit.SECONDS))
        pending.future.get(2, TimeUnit.SECONDS)
        assertTrue(pending.future.isDone)
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

    private fun gateway(
        refreshAll: suspend (RefreshTrigger) -> List<AccountRefreshResult>
    ): RefreshGateway = object : RefreshGateway {
        override suspend fun refreshAccount(
            accountId: String,
            trigger: RefreshTrigger
        ): AccountRefreshResult = error("not used")

        override suspend fun refreshAll(trigger: RefreshTrigger): RefreshBatchResult =
            refreshAll.invoke(trigger).let { results ->
                RefreshBatchResult("test-run", results, deriveRefreshBatchAggregate(results))
            }

        override fun invalidate(accountId: String) = Unit

        override suspend fun readAccountSnapshot(): AccountStoreRead = AccountStoreRead.Missing
    }

    private fun attachPendingResult(receiver: BroadcastReceiver): ShadowBroadcastPendingResult {
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create",
            Int::class.javaPrimitiveType,
            String::class.java,
            Bundle::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val pending = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
        ReflectionHelpers.setField(receiver, "mPendingResult", pending)
        return Shadows.shadowOf(pending)
    }

    private fun awaitWidgetBalance(
        manager: AppWidgetManager,
        widgetId: Int,
        predicate: (String) -> Boolean
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var rendered = ""
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            rendered = Shadows.shadowOf(manager).getViewFor(widgetId)
                ?.findViewById<TextView>(R.id.widget_balance)
                ?.text
                ?.toString()
                .orEmpty()
            if (predicate(rendered)) return rendered
            Thread.sleep(10)
        }
        fail("widget balance did not match; last rendered value was '$rendered'")
        return rendered
    }

    private fun awaitWidgetStatus(
        manager: AppWidgetManager,
        widgetId: Int
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var rendered = ""
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            rendered = Shadows.shadowOf(manager).getViewFor(widgetId)
                ?.findViewById<TextView>(R.id.widget_status)
                ?.text
                ?.toString()
                .orEmpty()
            if (rendered.isNotEmpty() && rendered != "--") return rendered
            Thread.sleep(10)
        }
        fail("widget status did not render; last value was '$rendered'")
        return rendered
    }

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
