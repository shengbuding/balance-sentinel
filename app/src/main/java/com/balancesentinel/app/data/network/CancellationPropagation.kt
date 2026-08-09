package com.balancesentinel.app.data.network

import kotlinx.coroutines.CancellationException

/** Returns the caller-provided cancellation cause when coroutine wrappers add one. */
fun CancellationException.originalCancellation(): CancellationException {
    var current: Throwable = this
    var original: CancellationException = this
    while (true) {
        val cause = current.cause as? CancellationException ?: break
        original = cause
        current = cause
    }
    return original
}
