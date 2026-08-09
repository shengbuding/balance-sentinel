package com.balancesentinel.app.data.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

/**
 * Runs a synchronous OkHttp call on the IO dispatcher and bridges coroutine
 * cancellation to the underlying call. The response is always closed by this
 * helper, including cancellation and transformation failures.
 */
suspend fun <T> Call.Factory.executeCancellable(
    request: Request,
    transform: (Response) -> T
): T {
    val cancellationCause = AtomicReference<Throwable?>()
    return try {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val call = newCall(request)
                continuation.invokeOnCancellation { cause ->
                    cancellationCause.set(cause)
                    call.cancel()
                }
                try {
                    call.execute().use { response ->
                        val value = transform(response)
                        if (continuation.isActive) {
                            continuation.resume(value)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (continuation.isActive) continuation.resumeWithException(cancelled)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw (cancellationCause.get() as? kotlinx.coroutines.CancellationException) ?: cancelled
    }
}
