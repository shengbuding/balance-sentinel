package com.balancesentinel.app.data.refresh

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.BalanceEntry
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import com.balancesentinel.app.data.api.cache.ProviderCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RefreshResultCommitterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProviderCache(context).clearAll()
    }

    @After
    fun tearDown() {
        ProviderCache(context).clearAll()
    }

    // Mutation caught: publishing a new in-memory cache value before its durable commit succeeds.
    @Test
    fun `provider cache persistence failure preserves the previously committed balance`() {
        val committed = balance(5.0)
        ProviderCache(context).put(ProviderType.DEEPSEEK, ACCOUNT_ID, committed)
        val failingCache = ProviderCache(FailingProviderCacheContext(context))

        assertThrows(IllegalStateException::class.java) {
            failingCache.put(ProviderType.DEEPSEEK, ACCOUNT_ID, balance(99.0))
        }

        val retained = ProviderCache(context).get(ProviderType.DEEPSEEK, ACCOUNT_ID)
        assertEquals(5.0, retained!!.balances.single().totalBalance, 0.0)
    }

    private fun balance(amount: Double) = UnifiedBalance(
        provider = ProviderType.DEEPSEEK,
        accountId = ACCOUNT_ID,
        isAvailable = true,
        balances = listOf(BalanceEntry("CNY", amount))
    )

    private class FailingProviderCacheContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = baseContext.getSharedPreferences(name, mode)
            if (name != "provider_cache") return delegate

            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(
                            key: String?,
                            value: String?
                        ): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }

                        override fun commit(): Boolean = false
                    }
                }
            }
        }
    }

    private companion object {
        const val ACCOUNT_ID = "acct"
    }
}
