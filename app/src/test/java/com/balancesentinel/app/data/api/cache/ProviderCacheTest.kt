package com.balancesentinel.app.data.api.cache

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.api.UnifiedBalance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        ProviderCache(context).clearAll()
    }

    @After
    fun tearDown() {
        ProviderCache(context).clearAll()
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
        val blockingContext = BlockingProviderRemoveContext(
            context,
            "provider-clear",
            removeReady,
            resumeRemove
        )
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

    @Test
    fun `corrupt cleanup cannot delete a concurrent fresh cache write`() {
        val key = "${ProviderType.DEEPSEEK.id}_corrupt-account"
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE)
            .edit()
            .putString(key, "not-json")
            .commit()
        val removeReady = CountDownLatch(1)
        val resumeRemove = CountDownLatch(1)
        val blockingContext = BlockingProviderRemoveContext(
            context,
            "provider-get",
            removeReady,
            resumeRemove
        )
        val cache = ProviderCache(blockingContext)
        val balance = UnifiedBalance(
            ProviderType.DEEPSEEK,
            "corrupt-account",
            true,
            emptyList()
        )
        val getThread = Thread(
            { cache.get(ProviderType.DEEPSEEK, "corrupt-account") },
            "provider-get"
        )
        val putThread = Thread(
            {
                ProviderCache(blockingContext).put(
                    ProviderType.DEEPSEEK,
                    "corrupt-account",
                    balance,
                    ttl = 60_000L
                )
            },
            "provider-put"
        )

        getThread.start()
        assertTrue(removeReady.await(5, TimeUnit.SECONDS))
        putThread.start()
        putThread.join(1_000)
        resumeRemove.countDown()
        getThread.join(5_000)
        putThread.join(5_000)

        assertFalse(getThread.isAlive)
        assertFalse(putThread.isAlive)
        assertTrue(
            context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).contains(key)
        )
    }

    @Test
    fun `clear cannot be undone by an in flight persistent cache read`() {
        val accountId = "inflight-read-account"
        val key = "${ProviderType.DEEPSEEK.id}_$accountId"
        val balance = UnifiedBalance(ProviderType.DEEPSEEK, accountId, true, emptyList())
        val cached = ProviderCache.CachedBalance(
            balance = balance,
            cachedAt = System.currentTimeMillis(),
            ttl = 60_000L
        )
        context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE)
            .edit()
            .putString(key, Json.encodeToString(cached))
            .commit()
        val readReady = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)
        val blockingContext = BlockingProviderReadContext(context, readReady, resumeRead)
        val getThread = Thread(
            { ProviderCache(blockingContext).get(ProviderType.DEEPSEEK, accountId) },
            "provider-get"
        )
        val clearThread = Thread(
            { ProviderCache(blockingContext).clear(ProviderType.DEEPSEEK, accountId) },
            "provider-clear"
        )

        getThread.start()
        assertTrue(readReady.await(5, TimeUnit.SECONDS))
        clearThread.start()
        assertTrue(awaitCompletedOrSharedLock(clearThread))
        resumeRead.countDown()
        getThread.join(5_000)
        clearThread.join(5_000)

        assertFalse(getThread.isAlive)
        assertFalse(clearThread.isAlive)
        assertFalse(
            context.getSharedPreferences("provider_cache", Context.MODE_PRIVATE).contains(key)
        )
        assertNull(ProviderCache(context).get(ProviderType.DEEPSEEK, accountId))
    }

    private fun awaitCompletedOrSharedLock(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.TERMINATED || thread.state == Thread.State.BLOCKED) {
                return true
            }
            Thread.yield()
        }
        return false
    }

    private class BlockingProviderReadContext(
        base: Context,
        private val readReady: CountDownLatch,
        private val resumeRead: CountDownLatch
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences(name, mode)
            if (name != "provider_cache") return delegate

            return object : SharedPreferences by delegate {
                override fun getString(key: String?, defValue: String?): String? {
                    val value = delegate.getString(key, defValue)
                    if (Thread.currentThread().name == "provider-get") {
                        readReady.countDown()
                        check(resumeRead.await(5, TimeUnit.SECONDS))
                    }
                    return value
                }
            }
        }
    }

    private class BlockingProviderRemoveContext(
        base: Context,
        private val blockingThreadName: String,
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
                            if (Thread.currentThread().name == blockingThreadName) {
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
