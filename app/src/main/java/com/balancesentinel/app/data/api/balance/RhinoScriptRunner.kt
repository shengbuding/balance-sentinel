package com.balancesentinel.app.data.api.balance

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ScriptDeadlineExceeded(
    val phase: String
) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

class RhinoScriptRunner {
    fun <T> run(
        timeoutMillis: Long,
        phase: String,
        block: (Context) -> T
    ): T {
        require(timeoutMillis > 0) { "Script timeout must be positive" }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "usage-script-$phase").apply { isDaemon = true }
        }
        val factory = DeadlineContextFactory(phase)
        val deadline = deadlineAfter(timeoutMillis)
        val future = executor.submit(Callable { factory.run(deadline, block) })
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw ScriptDeadlineExceeded(phase)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            executor.shutdownNow()
            try {
                executor.awaitTermination(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val duration = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
    }

    private class DeadlineContextFactory(
        private val phase: String
    ) : ContextFactory() {
        private val deadlineNanos = ThreadLocal<Long>()

        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            instructionObserverThreshold = INSTRUCTION_THRESHOLD
            setClassShutter(ClassShutter { false })
        }

        override fun observeInstructionCount(cx: Context, count: Int) {
            val deadline = deadlineNanos.get() ?: Long.MIN_VALUE
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) {
                throw ScriptDeadlineExceeded(phase)
            }
        }

        fun <T> run(deadline: Long, block: (Context) -> T): T {
            deadlineNanos.set(deadline)
            val context = enterContext()
            return try {
                block(context)
            } finally {
                Context.exit()
                deadlineNanos.remove()
            }
        }
    }

    private companion object {
        const val INSTRUCTION_THRESHOLD = 10_000
        const val TERMINATION_WAIT_MILLIS = 200L
    }
}
