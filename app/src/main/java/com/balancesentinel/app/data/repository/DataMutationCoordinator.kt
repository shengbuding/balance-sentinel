package com.balancesentinel.app.data.repository

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object DataMutationCoordinator {
    private val lock = ReentrantLock(true)

    // Always acquire this coordinator before any store-specific lock.
    fun <T> withMutation(block: () -> T): T = lock.withLock(block)

    suspend fun <T> withMutationSuspend(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            lock.lock()
            try {
                block()
            } finally {
                lock.unlock()
            }
        }
}
