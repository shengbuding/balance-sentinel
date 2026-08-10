package com.balancesentinel.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.repository.STARTUP_GRACE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForegroundServiceStarterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 5_000_000L

    @Test
    fun `successful start marks request before foreground service launch`() {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<ServiceStartDiagnostic>()
        val retries = mutableListOf<Long>()
        val starter = starter(
            marker = { _, requestedAt -> events += "mark:$requestedAt" },
            launcher = { _, intent ->
                events += "launch:${intent.component?.className}"
            },
            retry = { _, retryAt -> retries += retryAt },
            diagnostic = { _, value -> diagnostics += value }
        )

        val result = starter.start(context)

        assertEquals(ServiceStartResult.Started, result)
        assertEquals(
            listOf("mark:$now", "launch:${BalanceRefreshService::class.java.name}"),
            events
        )
        assertTrue(retries.isEmpty())
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `not allowed start returns deferred and schedules earliest post startup grace retry`() {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<ServiceStartDiagnostic>()
        val retries = mutableListOf<Long>()
        val starter = starter(
            marker = { _, requestedAt -> events += "mark:$requestedAt" },
            launcher = { _, _ ->
                events += "launch"
                throw ForegroundServiceStartNotAllowedException("Token=secret")
            },
            retry = { _, retryAt ->
                events += "retry:$retryAt"
                retries += retryAt
            },
            diagnostic = { _, value ->
                events += "diagnostic:${value.reason}"
                diagnostics += value
            }
        )

        val result = starter.start(context)
        val retryAt = now + STARTUP_GRACE_MS + 1L

        assertEquals(
            ServiceStartResult.Deferred(
                retryAt,
                ForegroundServiceStarter.REASON_START_NOT_ALLOWED
            ),
            result
        )
        assertEquals(listOf(retryAt), retries)
        assertEquals(
            listOf(ServiceStartDiagnostic(ServiceStartDiagnosticReason.START_NOT_ALLOWED, retryAt)),
            diagnostics
        )
        assertEquals(
            listOf("mark:$now", "launch", "retry:$retryAt", "diagnostic:START_NOT_ALLOWED"),
            events
        )
        assertFalse(result.toString().contains("secret", ignoreCase = true))
    }

    @Test
    fun `security rejection returns bounded failed result without retry`() {
        val diagnostics = mutableListOf<ServiceStartDiagnostic>()
        val retries = mutableListOf<Long>()
        var markedAt = 0L
        val starter = starter(
            marker = { _, requestedAt -> markedAt = requestedAt },
            launcher = { _, _ -> throw SecurityException("Cookie=session-secret") },
            retry = { _, retryAt -> retries += retryAt },
            diagnostic = { _, value -> diagnostics += value }
        )

        val result = starter.start(context)

        assertEquals(
            ServiceStartResult.Failed(ForegroundServiceStarter.REASON_SECURITY_REJECTED),
            result
        )
        assertEquals(now, markedAt)
        assertTrue(retries.isEmpty())
        assertEquals(
            listOf(ServiceStartDiagnostic(ServiceStartDiagnosticReason.SECURITY_REJECTED)),
            diagnostics
        )
        assertFalse(result.toString().contains("session-secret"))
    }

    @Test
    fun `unexpected runtime failure uses stable diagnostic without exception message`() {
        val diagnostics = mutableListOf<ServiceStartDiagnostic>()
        val starter = starter(
            launcher = { _, _ -> throw IllegalStateException("API Key=top-secret") },
            diagnostic = { _, value -> diagnostics += value }
        )

        val result = starter.start(context)

        assertEquals(
            ServiceStartResult.Failed(ForegroundServiceStarter.REASON_START_FAILED),
            result
        )
        assertEquals(
            listOf(ServiceStartDiagnostic(ServiceStartDiagnosticReason.START_FAILED)),
            diagnostics
        )
        assertFalse(result.toString().contains("top-secret"))
        assertTrue((result as ServiceStartResult.Failed).reason.length <= 64)
    }

    private fun starter(
        marker: ServiceStartRequestMarker = ServiceStartRequestMarker { _, _ -> Unit },
        launcher: ForegroundServiceLauncher,
        retry: ServiceStartRetryScheduler = ServiceStartRetryScheduler { _, _ -> Unit },
        diagnostic: ServiceStartDiagnosticSink = ServiceStartDiagnosticSink { _, _ -> Unit },
        fallback: ForegroundServiceFallbackScheduler = ForegroundServiceFallbackScheduler { }
    ) = ForegroundServiceStarter(
        now = { now },
        requestMarker = marker,
        launcher = launcher,
        retryScheduler = retry,
        diagnosticSink = diagnostic,
        fallbackScheduler = fallback
    )
}
