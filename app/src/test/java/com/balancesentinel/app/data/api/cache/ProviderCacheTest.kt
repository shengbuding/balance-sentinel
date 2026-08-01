package com.balancesentinel.app.data.api.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProviderCacheTest {

    private lateinit var context: Context
    private lateinit var first: ProviderCache
    private lateinit var second: ProviderCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).edit().clear().commit()
        first = ProviderCache(context)
        second = ProviderCache(context)
    }

    @After
    fun tearDown() {
        first.clearAll()
    }

    @Test
    fun `separate cache instances persist concurrent writes`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        pool.submit { start.await(); first.put(ProviderType.DEEPSEEK, "one", balance("one")) }
        pool.submit { start.await(); second.put(ProviderType.OPENAI, "two", balance("two")) }
        start.countDown()
        pool.shutdown()

        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))
        val reader = ProviderCache(context)
        assertNotNull(reader.get(ProviderType.DEEPSEEK, "one"))
        assertNotNull(reader.get(ProviderType.OPENAI, "two"))
    }

    @Test
    fun `clear from another instance synchronously removes persisted entry`() {
        first.put(ProviderType.DEEPSEEK, "account", balance("account"))

        second.clear(ProviderType.DEEPSEEK, "account")

        assertNull(ProviderCache(context).get(ProviderType.DEEPSEEK, "account"))
    }

    private fun balance(accountId: String) = UnifiedBalance(
        provider = ProviderType.DEEPSEEK,
        accountId = accountId,
        isAvailable = true,
        balances = emptyList()
    )
}
