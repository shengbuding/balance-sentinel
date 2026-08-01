package com.balancesentinel.app.data.api.cache

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProviderCacheTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `clear is immediately visible to every cache instance`() {
        val writer = ProviderCache(context)
        val reader = ProviderCache(context)
        val balance = UnifiedBalance(ProviderType.DEEPSEEK, "account-1", true, emptyList())

        writer.put(ProviderType.DEEPSEEK, "account-1", balance)
        reader.get(ProviderType.DEEPSEEK, "account-1")
        writer.clear(ProviderType.DEEPSEEK, "account-1")

        assertNull(reader.get(ProviderType.DEEPSEEK, "account-1"))
    }

    @Test
    fun `expired cleanup cannot delete a concurrent fresh cache write`() {
        val removeReady = CountDownLatch(1)
        val resumeRemove = CountDownLatch(1)
        val blockingContext = BlockingProviderRemoveContext(context, removeReady, resumeRemove)
        val cache = ProviderCache(blockingContext)
        val balance = UnifiedBalance(ProviderType.DEEPSEEK, "account-1", true, emptyList())
        cache.put(ProviderType.DEEPSEEK, "account-1", balance, ttl = -1L)
        val clearThread = Thread({ cache.clearExpired() }, "provider-clear")
        val putThread = Thread(
            {
                ProviderCache(blockingContext).put(
                    ProviderType.DEEPSEEK,
                    "account-1",
                    balance,
                    ttl = 60_000L
                )
            },
            "provider-put"
        )

        clearThread.start()
        assertTrue(removeReady.await(5, TimeUnit.SECONDS))
        putThread.start()
        putThread.join(1_000)
        resumeRemove.countDown()
        clearThread.join(5_000)
        putThread.join(5_000)

        assertFalse(clearThread.isAlive)
        assertFalse(putThread.isAlive)
        val key = "${ProviderType.DEEPSEEK.id}_account-1"
        assertTrue(
            context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).contains(key)
        )
    }

    private class BlockingProviderRemoveContext(
        base: Context,
        private val removeReady: CountDownLatch,
        private val resumeRemove: CountDownLatch
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences(name, mode)
            if (name != "provider_cache") return delegate

            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun remove(key: String?): SharedPreferences.Editor {
                            if (Thread.currentThread().name == "provider-clear") {
                                removeReady.countDown()
                                check(resumeRemove.await(5, TimeUnit.SECONDS))
                            }
                            editor.remove(key)
                            return this
                        }
                    }
                }
            }
        }
    }
}
