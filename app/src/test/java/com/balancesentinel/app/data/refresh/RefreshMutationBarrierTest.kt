package com.balancesentinel.app.data.refresh

import java.lang.reflect.Field
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshMutationBarrierTest {
    @Test
    fun `nested account mutation reuses refresh permit`() {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<String> {
            runBlocking {
                RefreshMutationBarrier.withRefreshCommitSuspend {
                    RefreshMutationBarrier.withAccountMutation(null) { "completed" }
                }
            }
        }
        var timedOut = false
        try {
            val result = try {
                future.get(300, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                timedOut = true
                null
            }
            assertFalse("nested barrier acquisition must not deadlock", timedOut)
            assertEquals("completed", result)
        } finally {
            if (timedOut) {
                future.cancel(true)
                releasePermitForCleanup()
            }
            executor.shutdownNow()
        }
    }

    @Test
    fun `waiting account mutation responds to cancellation`() {
        val executor = Executors.newFixedThreadPool(2)
        val holderEntered = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holder = executor.submit {
            runBlocking {
                RefreshMutationBarrier.withRefreshCommitSuspend {
                    holderEntered.countDown()
                    releaseHolder.await()
                }
            }
        }
        assertTrue(holderEntered.await(1, TimeUnit.SECONDS))

        val waiter = executor.submit {
            runBlocking {
                withTimeout(100) {
                    RefreshMutationBarrier.withAccountMutation(null) { "unreachable" }
                }
            }
        }
        var timedOut = false
        try {
            val failure = try {
                waiter.get(300, TimeUnit.MILLISECONDS)
                null
            } catch (error: ExecutionException) {
                error.cause
            } catch (_: TimeoutException) {
                timedOut = true
                null
            }
            assertFalse("account mutation permit acquisition must be cancellable", timedOut)
            assertTrue(failure is TimeoutCancellationException)
        } finally {
            releaseHolder.countDown()
            if (timedOut) {
                waiter.cancel(true)
                releasePermitForCleanup()
            }
            holder.get(1, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun releasePermitForCleanup() {
        val field: Field = RefreshMutationBarrier::class.java.getDeclaredField("permit")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val permit = field.get(null) as java.util.concurrent.Semaphore
        permit.release()
    }
}
