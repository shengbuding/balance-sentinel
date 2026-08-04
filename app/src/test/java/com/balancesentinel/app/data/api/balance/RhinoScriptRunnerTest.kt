package com.balancesentinel.app.data.api.balance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mozilla.javascript.Context

class RhinoScriptRunnerTest {

    // Mutation caught: omitting the instruction threshold or wall-clock cancellation.
    @Test(timeout = 3_000)
    fun `configuration infinite loop hits wall clock deadline`() {
        val failure = assertThrows(ScriptDeadlineExceeded::class.java) {
            RhinoScriptRunner().run(timeoutMillis = 100, phase = "configuration") { context ->
                val scope = context.initSafeStandardObjects()
                context.evaluateString(scope, "while (true) {}", "usage-script", 1, null)
            }
        }

        assertEquals("configuration", failure.phase)
    }

    // Mutation caught: running untrusted Rhino work inline on the caller thread.
    @Test
    fun `finite script runs on a dedicated worker`() {
        val callerThread = Thread.currentThread().id

        val workerThread = RhinoScriptRunner().run(500, "configuration") {
            Thread.currentThread().id
        }

        assertNotEquals(callerThread, workerThread)
    }

    // Mutation caught: leaving Rhino at its default dialect, which rejects saved-script let syntax.
    @Test
    fun `runner executes saved script let syntax under explicit dialect`() {
        val value = RhinoScriptRunner().run(500, "configuration") { context ->
            val scope = context.initSafeStandardObjects()
            Context.toNumber(
                context.evaluateString(
                    scope,
                    "let answer = 40; answer + 2;",
                    "usage-script",
                    1,
                    null
                )
            )
        }

        assertEquals(42.0, value, 0.0)
    }
}
