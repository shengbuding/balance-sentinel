package com.balancesentinel.app.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataMutationCoordinatorTest {
    @Test
    fun `suspending mutation remains usable after repeated thread switches`() = runBlocking {
        repeat(50) {
            withTimeout(2_000) {
                DataMutationCoordinator.withMutationSuspend {
                    delay(1)
                    withContext(Dispatchers.Default) { delay(1) }
                }
            }
        }

        val result = withTimeout(2_000) {
            DataMutationCoordinator.withMutationSuspend { "available" }
        }

        assertEquals("available", result)
    }

    @Test
    fun `nested synchronous mutation reuses suspend permit`() = runBlocking {
        val result = withTimeout(2_000) {
            DataMutationCoordinator.withMutationSuspend {
                DataMutationCoordinator.withMutation { "nested" }
            }
        }

        assertEquals("nested", result)
    }

    @Test
    fun `waiting suspend mutation responds to cancellation`() = runBlocking {
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            DataMutationCoordinator.withMutationSuspend {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }
        holderEntered.await()

        val failure = runCatching {
            withTimeout(100) {
                DataMutationCoordinator.withMutationSuspend { "unreachable" }
            }
        }.exceptionOrNull()

        releaseHolder.complete(Unit)
        holder.await()
        assertTrue(failure is TimeoutCancellationException)
    }
}
