package com.balancesentinel.app.data.debug

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.Interceptor
import okhttp3.Response

class DebugInterceptor(
    private val accountId: String,
    private val accountLabel: String? = null,
    private val providerType: String? = null,
    private val baseUrl: String? = null,
    private val isCustomScript: Boolean = false,
    private val scriptPreview: String? = null,
    private val entrySink: (ApiDebugEntry) -> Unit = ApiDebugStore::addEntry,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = nowMillis()
        val capturedRequest = runCatching { DebugCapture.captureRequest(request.body) }
            .getOrElse { error ->
                SensitiveDataRedactor.redactCaptured(
                    "[REQUEST BODY UNAVAILABLE: ${error.javaClass.simpleName}: ${error.message.orEmpty()}]"
                )
            }
        val requestCapture = capturedRequest?.let { raw ->
            val redacted = SensitiveDataRedactor.redactCaptured(raw.text)
            redacted.copy(truncated = raw.truncated || redacted.truncated)
        }
        val common = EntryContext(
            url = SensitiveDataRedactor.redactUrl(request.url),
            method = request.method,
            requestHeaders = SensitiveDataRedactor.redactHeaders(request.headers),
            requestBody = requestCapture,
            endpoint = request.url.encodedPath
        )

        val response = try {
            chain.proceed(request)
        } catch (error: Exception) {
            val errorText = SensitiveDataRedactor.redactCaptured(errorSummary(error))
            val stack = SensitiveDataRedactor.redactCaptured(error.stackTraceToString())
            emit(
                common.entry(
                    statusCode = 0,
                    responseHeaders = emptyMap(),
                    responseBody = CapturedText("", false, 0),
                    timestamp = startTime,
                    duration = nowMillis() - startTime,
                    error = errorText,
                    exceptionType = error.javaClass.simpleName,
                    exceptionStack = stack
                )
            )
            throw error
        }

        val rawCapture = DebugCapture.captureResponse(response)
        val capturedResponse = SensitiveDataRedactor.redactCaptured(rawCapture.text)
        val responseCapture = capturedResponse.copy(truncated = rawCapture.truncated || capturedResponse.truncated)
        val errorCapture = if (response.isSuccessful) null else responseCapture
        emit(
            common.entry(
                statusCode = response.code,
                responseHeaders = SensitiveDataRedactor.redactHeaders(response.headers),
                responseBody = responseCapture,
                timestamp = startTime,
                duration = nowMillis() - startTime,
                error = errorCapture
            )
        )
        return response
    }

    private fun emit(entry: ApiDebugEntry) {
        entrySink(ApiDebugStore.sanitizedEntry(entry))
    }

    private fun errorSummary(error: Exception): String = when (error) {
        is ConnectException -> "Connection failed: ${error.message.orEmpty()}"
        is SocketTimeoutException -> "Connection timed out: ${error.message.orEmpty()}"
        is UnknownHostException -> "DNS lookup failed: ${error.message.orEmpty()}"
        is SSLException -> "SSL error: ${error.message.orEmpty()}"
        is IOException -> "Network error: ${error.message.orEmpty()}"
        else -> "Unexpected error: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }

    private inner class EntryContext(
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String>,
        val requestBody: CapturedText?,
        val endpoint: String
    ) {
        fun entry(
            statusCode: Int,
            responseHeaders: Map<String, String>,
            responseBody: CapturedText,
            timestamp: Long,
            duration: Long,
            error: CapturedText? = null,
            exceptionType: String? = null,
            exceptionStack: CapturedText? = null
        ) = ApiDebugEntry(
            accountId = accountId,
            url = url,
            method = method,
            requestHeaders = requestHeaders,
            requestBody = requestBody?.text,
            statusCode = statusCode,
            responseHeaders = responseHeaders,
            responseBody = responseBody.text,
            timestamp = timestamp,
            duration = duration,
            error = error?.text,
            accountLabel = accountLabel,
            providerType = providerType,
            baseUrl = baseUrl,
            endpoint = endpoint,
            isCustomScript = isCustomScript,
            scriptPreview = scriptPreview,
            exceptionType = exceptionType,
            exceptionStack = exceptionStack?.text,
            requestBodyTruncated = requestBody?.truncated == true,
            responseBodyTruncated = responseBody.truncated,
            errorTruncated = error?.truncated == true,
            exceptionStackTruncated = exceptionStack?.truncated == true
        )
    }
}
