package com.balancesentinel.app.data.repository

import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal object DataMutationCoordinator {
    /**
     * A semaphore is deliberately used instead of a ReentrantLock. The refresh
     * commit path suspends while Room performs its transaction, so ownership
     * must not be tied to the physical thread that acquired the permit.
     */
    private val permit = Semaphore(1, true)
    private val mutationDepth = ThreadLocal.withInitial { 0 }

    // Always acquire this coordinator before any store-specific lock.
    fun <T> withMutation(block: () -> T): T {
        if ((mutationDepth.get() ?: 0) > 0) return block()
        permit.acquire()
        val previousDepth = mutationDepth.get() ?: 0
        mutationDepth.set(previousDepth + 1)
        return try {
            block()
        } finally {
            mutationDepth.set(previousDepth)
            permit.release()
        }
    }

    suspend fun <T> withMutationSuspend(block: suspend () -> T): T {
        if (coroutineContext[PermitKey] != null || (mutationDepth.get() ?: 0) > 0) {
            return block()
        }
        return withContext(Dispatchers.IO + PermitElement) {
            runInterruptible { permit.acquire() }
            try {
                block()
            } finally {
                permit.release()
            }
        }
    }

    private object PermitKey : CoroutineContext.Key<PermitElement>

    /** Marks the current coroutine/thread as the permit owner for nested calls. */
    private object PermitElement : ThreadContextElement<Int> {
        override val key: CoroutineContext.Key<PermitElement> = PermitKey

        override fun updateThreadContext(context: CoroutineContext): Int {
            val previous = mutationDepth.get() ?: 0
            mutationDepth.set(previous + 1)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Int) {
            mutationDepth.set(oldState)
        }
    }
}
