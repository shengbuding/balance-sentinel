package com.balancesentinel.app.data.api.balance

import org.mozilla.javascript.Context

class ScriptDeadlineExceeded(
    val phase: String
) : RuntimeException()

class RhinoScriptRunner {
    fun <T> run(
        @Suppress("UNUSED_PARAMETER") timeoutMillis: Long,
        @Suppress("UNUSED_PARAMETER") phase: String,
        @Suppress("UNUSED_PARAMETER") block: (Context) -> T
    ): T = throw IllegalStateException("Rhino runner is not implemented")
}
