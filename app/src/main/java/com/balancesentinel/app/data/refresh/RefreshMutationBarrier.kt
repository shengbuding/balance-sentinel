package com.balancesentinel.app.data.refresh

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared lock joining Room account mutations to refresh persistence. */
object RefreshMutationBarrier {
    private val lock = ReentrantReadWriteLock(true)
    private val invalidators = ConcurrentHashMap<String, MutableSet<() -> Unit>>()

    fun register(accountId: String, invalidate: () -> Unit) {
        invalidators.computeIfAbsent(accountId) { ConcurrentHashMap.newKeySet() }.add(invalidate)
    }

    fun <T> withRefreshCommit(block: () -> T): T = lock.read(block)

    suspend fun <T> withAccountMutation(
        accountId: String?,
        invalidate: (String) -> Unit = {},
        block: suspend () -> T
    ): T =
        withContext(Dispatchers.IO) {
            accountId?.let { id ->
                invalidators[id]?.toList()?.forEach { invalidateRegistered -> invalidateRegistered() }
                invalidate(id)
            }
            lock.writeLock().lock()
            try {
                block()
            } finally {
                lock.writeLock().unlock()
            }
        }
}
