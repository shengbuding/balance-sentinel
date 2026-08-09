package com.balancesentinel.app.data.api.balance

import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.RefreshFailure
import com.balancesentinel.app.data.debug.DebugCapturePolicy
import com.balancesentinel.app.data.debug.DebugClientInstaller
import com.balancesentinel.app.data.debug.DebugInterceptor
import com.balancesentinel.app.data.network.BoundedResponseReader
import com.balancesentinel.app.data.network.EncodedResponseLimitInterceptor
import com.balancesentinel.app.data.network.NetworkResponseException
import com.balancesentinel.app.data.network.ResponseBudget
import com.balancesentinel.app.data.network.executeCancellable
import com.balancesentinel.app.data.network.originalCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.Parser
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Token
import org.mozilla.javascript.ast.AstNode
import org.mozilla.javascript.ast.AstRoot
import org.mozilla.javascript.ast.ExpressionStatement
import org.mozilla.javascript.ast.FunctionNode
import org.mozilla.javascript.ast.KeywordLiteral
import org.mozilla.javascript.ast.Name
import org.mozilla.javascript.ast.NumberLiteral
import org.mozilla.javascript.ast.ObjectLiteral
import org.mozilla.javascript.ast.ObjectProperty
import org.mozilla.javascript.ast.ParenthesizedExpression
import org.mozilla.javascript.ast.StringLiteral
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object UsageScriptExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val runner = RhinoScriptRunner()
    private val systemResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() }

    suspend fun inspect(script: UsageScript, account: AccountInfo): ScriptInspection =
        withContext(Dispatchers.IO) {
            val baseUrl = effectiveBaseUrl(script, account)?.toHttpUrlOrNull()
                ?: return@withContext inspectionFailure(
                    RefreshFailure.ResponseSchemaFailure("Script base URL is invalid")
                )
            val timeoutMillis = timeoutMillis(script)
                ?: return@withContext inspectionFailure(
                    RefreshFailure.ResponseSchemaFailure("Script timeout is invalid")
                )
            val inspectionSource = replaceTemplateVariables(
                source = script.code,
                apiKey = INSPECTION_API_KEY,
                baseUrl = baseUrl.toString().trimEnd('/'),
                accessToken = INSPECTION_ACCESS_TOKEN,
                userId = INSPECTION_USER_ID
            )

            try {
                val request = evaluateConfiguration(inspectionSource, timeoutMillis)
                val requestUrl = request.url.toHttpUrlOrNull()
                val requiredOrigins = requestUrl?.let(WebOrigin::from)
                    ?.takeIf { it != WebOrigin.from(baseUrl) }
                    ?.let(::setOf)
                    .orEmpty()
                ScriptInspection(
                    request = request,
                    requiredExtraOrigins = requiredOrigins,
                    staticallyDeterminable = requestUrl != null && hasLiteralRequestUrl(script.code)
                )
            } catch (_: ScriptDeadlineExceeded) {
                inspectionFailure(RefreshFailure.ScriptTimeout("Script configuration timed out"))
            } catch (interrupted: InterruptedException) {
                throw CancellationException("Script inspection was cancelled").also {
                    it.initCause(interrupted)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                inspectionFailure(
                    RefreshFailure.ResponseSchemaFailure("Script configuration is invalid")
                )
            }
        }

    suspend fun execute(
        script: UsageScript,
        account: AccountInfo
    ): ScriptExecutionResult = execute(
        script = script,
        account = account,
        resolver = systemResolver,
        client = OkHttpClient(),
        connectionUrlOverride = null
    )

    internal suspend fun execute(
        script: UsageScript,
        account: AccountInfo,
        resolver: HostResolver,
        client: OkHttpClient,
        connectionUrlOverride: ((HttpUrl) -> HttpUrl)?,
        debuggable: Boolean = DebugCapturePolicy.enabled()
    ): ScriptExecutionResult = try {
        withContext(Dispatchers.IO) {
        if (!script.enabled || !account.usageScriptEnabled) {
            return@withContext failure(
                RefreshFailure.ScriptPolicyDenied("Custom balance script is disabled")
            )
        }
        val baseUrl = effectiveBaseUrl(script, account)?.toHttpUrlOrNull()
            ?: return@withContext failure(
                RefreshFailure.ResponseSchemaFailure("Script base URL is invalid")
            )
        val timeoutMillis = timeoutMillis(script)
            ?: return@withContext failure(
                RefreshFailure.ResponseSchemaFailure("Script timeout is invalid")
            )
        val source = replaceTemplateVariables(
            source = script.code,
            apiKey = script.apiKey ?: account.apiKey,
            baseUrl = baseUrl.toString().trimEnd('/'),
            accessToken = script.accessToken,
            userId = script.userId
        )
        val requestConfig = try {
            evaluateConfiguration(source, timeoutMillis)
        } catch (_: ScriptDeadlineExceeded) {
            return@withContext failure(RefreshFailure.ScriptTimeout("Script configuration timed out"))
        } catch (interrupted: InterruptedException) {
            throw CancellationException("Script execution was cancelled").also {
                it.initCause(interrupted)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext failure(
                RefreshFailure.ResponseSchemaFailure("Script configuration is invalid")
            )
        }

        val requestUrl = requestConfig.url.toHttpUrlOrNull()
            ?: return@withContext failure(
                RefreshFailure.ScriptPolicyDenied("Script request URL is invalid")
            )
        val policy = ScriptNetworkPolicy(
            baseUrl = baseUrl,
            authorizedOrigins = account.authorizedScriptOrigins.mapNotNull(::parseAuthorizedOrigin).toSet(),
            resolver = resolver
        )
        val diagnosticClient = DebugClientInstaller.install(
            client = client,
            debuggable = debuggable,
            interceptor = account.id.takeIf(String::isNotBlank)?.let { accountId ->
                DebugInterceptor(
                    accountId = accountId,
                    accountLabel = account.label,
                    providerType = account.providerType.displayName,
                    baseUrl = baseUrl.toString(),
                    isCustomScript = true,
                    scriptPreview = script.code
                )
            }
        )
        when (
            val response = sendHttpRequest(
                config = requestConfig,
                initialUrl = requestUrl,
                timeoutMillis = timeoutMillis,
                policy = policy,
                baseClient = diagnosticClient,
                connectionUrlOverride = connectionUrlOverride
            )
        ) {
            is HttpFetchResult.Failure -> response.result
            is HttpFetchResult.Success -> extract(source, response.body, timeoutMillis)
            is HttpFetchResult.Redirect -> failure(
                RefreshFailure.NetworkFailure("Script redirect resolution failed")
            )
        }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled.originalCancellation()
    }

    suspend fun extractForTest(
        script: UsageScript,
        account: AccountInfo,
        responseBody: String
    ): ScriptExecutionResult = withContext(Dispatchers.IO) {
        val baseUrl = effectiveBaseUrl(script, account)?.toHttpUrlOrNull()
            ?: return@withContext failure(
                RefreshFailure.ResponseSchemaFailure("Script base URL is invalid")
            )
        val timeoutMillis = timeoutMillis(script)
            ?: return@withContext failure(
                RefreshFailure.ResponseSchemaFailure("Script timeout is invalid")
            )
        val source = replaceTemplateVariables(
            source = script.code,
            apiKey = script.apiKey ?: account.apiKey,
            baseUrl = baseUrl.toString().trimEnd('/'),
            accessToken = script.accessToken,
            userId = script.userId
        )
        extract(source, responseBody, timeoutMillis)
    }

    fun validateScript(script: String): String? = try {
        evaluateConfiguration(script, DEFAULT_TIMEOUT_MILLIS)
        null
    } catch (_: ScriptDeadlineExceeded) {
        "Script configuration timed out"
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        "Script configuration is invalid"
    }

    private fun evaluateConfiguration(source: String, timeoutMillis: Long): RequestConfig =
        runner.run(timeoutMillis, CONFIGURATION_PHASE) { context ->
            val scope = createSandboxScope(context)
            val config = context.evaluateString(
                scope,
                preprocessScript(source),
                SCRIPT_SOURCE_NAME,
                1,
                null
            )
            ScriptableObject.putProperty(scope, INTERNAL_CONFIG, config)
            val serialized = context.evaluateString(
                scope,
                """
                    JSON.stringify({
                        request: $INTERNAL_CONFIG && $INTERNAL_CONFIG.request,
                        hasExtractor: typeof ($INTERNAL_CONFIG && $INTERNAL_CONFIG.extractor) === "function"
                    })
                """.trimIndent(),
                SCRIPT_SOURCE_NAME,
                1,
                null
            )
            parseRequestConfig(Context.toString(serialized))
        }

    private fun parseRequestConfig(serialized: String): RequestConfig {
        val config = json.parseToJsonElement(serialized).jsonObject
        if (config["hasExtractor"]?.jsonPrimitive?.booleanOrNull != true) {
            throw IllegalArgumentException("Missing extractor")
        }
        val request = config["request"]?.jsonObject
            ?: throw IllegalArgumentException("Missing request")
        val url = request["url"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing request URL")
        val method = request["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val headers = request["headers"]?.jsonObject?.mapValues { (_, value) ->
            value.jsonPrimitive.content
        }.orEmpty()
        val body = request["body"]?.jsonPrimitive?.contentOrNull
        return RequestConfig(url = url, method = method, headers = headers, body = body)
    }

    private fun extract(
        source: String,
        responseBody: String,
        timeoutMillis: Long
    ): ScriptExecutionResult = try {
        val serialized = runner.run(timeoutMillis, EXTRACTOR_PHASE) { context ->
            val scope = createSandboxScope(context)
            val config = context.evaluateString(
                scope,
                preprocessScript(source),
                SCRIPT_SOURCE_NAME,
                1,
                null
            )
            ScriptableObject.putProperty(scope, INTERNAL_CONFIG, config)
            ScriptableObject.putProperty(scope, INTERNAL_RESPONSE, responseBody)
            val result = context.evaluateString(
                scope,
                """
                    (function() {
                        var extractor = $INTERNAL_CONFIG && $INTERNAL_CONFIG.extractor;
                        if (typeof extractor !== "function") {
                            throw new Error("Missing extractor");
                        }
                        var response = JSON.parse($INTERNAL_RESPONSE);
                        return JSON.stringify(extractor(response));
                    })()
                """.trimIndent(),
                SCRIPT_SOURCE_NAME,
                1,
                null
            )
            Context.toString(result)
        }
        ScriptExecutionResult.Success(listOf(parseBalance(serialized)))
    } catch (_: ScriptDeadlineExceeded) {
        failure(RefreshFailure.ScriptTimeout("Script extractor timed out"))
    } catch (interrupted: InterruptedException) {
        throw CancellationException("Script extraction was cancelled").also {
            it.initCause(interrupted)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure(RefreshFailure.ResponseSchemaFailure("Script response schema is invalid"))
    }

    private fun parseBalance(serialized: String): BalanceData {
        val result = json.parseToJsonElement(serialized).jsonObject
        val remaining = result["remaining"]?.jsonPrimitive?.doubleOrNull
            ?: result["balance"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("Missing remaining balance")
        if (!remaining.isFinite()) throw IllegalArgumentException("Non-finite remaining balance")
        val total = result["total"]?.jsonPrimitive?.doubleOrNull
        val used = result["used"]?.jsonPrimitive?.doubleOrNull
        if (total?.isFinite() == false || used?.isFinite() == false) {
            throw IllegalArgumentException("Non-finite balance value")
        }
        val isValid = result["isValid"]?.jsonPrimitive?.booleanOrNull
            ?: result["is_valid"]?.jsonPrimitive?.booleanOrNull
            ?: result["is_valid"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        return BalanceData(
            planName = result["plan_name"]?.jsonPrimitive?.contentOrNull,
            remaining = remaining,
            total = total,
            used = used,
            unit = result["unit"]?.jsonPrimitive?.contentOrNull ?: "CNY",
            isValid = isValid ?: true,
            invalidMessage = result["invalid_message"]?.jsonPrimitive?.contentOrNull
        )
    }

    private suspend fun sendHttpRequest(
        config: RequestConfig,
        initialUrl: HttpUrl,
        timeoutMillis: Long,
        policy: ScriptNetworkPolicy,
        baseClient: OkHttpClient,
        connectionUrlOverride: ((HttpUrl) -> HttpUrl)?
    ): HttpFetchResult {
        var logicalUrl = initialUrl
        var redirectsFollowed = 0
        while (true) {
            val destination = policy.resolve(logicalUrl)
            if (!destination.decision.isAllowed) {
                return HttpFetchResult.Failure(
                    failure(
                        RefreshFailure.ScriptPolicyDenied(
                            destination.decision.reason ?: "Script request was denied"
                        )
                    )
                )
            }
            val connectionUrl = try {
                connectionUrlOverride?.invoke(logicalUrl) ?: logicalUrl
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return networkFailure()
            }
            val request = try {
                buildRequest(config, connectionUrl)
            } catch (_: IllegalArgumentException) {
                return HttpFetchResult.Failure(
                    failure(RefreshFailure.ResponseSchemaFailure("Script request is invalid"))
                )
            }
            val requestClient = baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .addNetworkInterceptor(EncodedResponseLimitInterceptor(ResponseBudget.SCRIPT))
                .apply {
                    if (connectionUrlOverride == null) {
                        dns(pinnedDns(logicalUrl.host, destination.addresses))
                    }
                }
                .build()
            val result = try {
                requestClient.executeCancellable(request) { response ->
                    if (response.code in 300..399) {
                        try {
                            response.body?.let { body ->
                                BoundedResponseReader(
                                    ResponseBudget.SCRIPT.maxDecodedBytes,
                                    "script-redirect"
                                ).readBytes(body)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            return@executeCancellable networkFailure(error)
                        }
                        if (redirectsFollowed >= MAX_REDIRECTS) {
                            return@executeCancellable HttpFetchResult.Failure(
                                failure(
                                    RefreshFailure.ScriptPolicyDenied("Script redirect limit exceeded")
                                )
                            )
                        }
                        val location = response.header("Location")
                            ?: return@executeCancellable networkFailure()
                        val redirectTarget = logicalUrl.resolve(location)
                            ?: return@executeCancellable HttpFetchResult.Failure(
                                failure(
                                    RefreshFailure.ScriptPolicyDenied("Script redirect URL is invalid")
                                )
                            )
                        HttpFetchResult.Redirect(redirectTarget)
                    } else {
                        val body = try {
                            response.body?.let { body ->
                                BoundedResponseReader(
                                    ResponseBudget.SCRIPT.maxDecodedBytes,
                                    "script-response"
                                ).readText(
                                    body,
                                    expectedContentType = if (response.isSuccessful) {
                                        "application/json"
                                    } else {
                                        null
                                    }
                                )
                            }.orEmpty()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            return@executeCancellable networkFailure(error)
                        }
                        if (!response.isSuccessful) {
                            return@executeCancellable networkFailure(
                                NetworkResponseException.httpStatus(
                                    endpoint = "script-response",
                                    statusCode = response.code,
                                    limitedBody = body
                                )
                            )
                        }
                        if (body.isBlank()) return@executeCancellable networkFailure()
                        HttpFetchResult.Success(body)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return networkFailure(error)
            }
            when (result) {
                is HttpFetchResult.Failure -> return result
                is HttpFetchResult.Success -> return result
                is HttpFetchResult.Redirect -> {
                    redirectsFollowed++
                    logicalUrl = result.target
                }
            }
        }
    }

    private fun pinnedDns(host: String, addresses: List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!hostname.equals(host, ignoreCase = true)) {
                throw UnknownHostException("Unexpected script connection host")
            }
            return addresses
        }
    }

    private fun buildRequest(config: RequestConfig, url: HttpUrl): Request {
        val builder = Request.Builder().url(url)
        config.headers.forEach { (name, value) -> builder.header(name, value) }
        val jsonBody = config.body.orEmpty().toRequestBody(JSON_MEDIA_TYPE)
        when (config.method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(jsonBody)
            "PUT" -> builder.put(jsonBody)
            "DELETE" -> if (config.body == null) builder.delete() else builder.delete(jsonBody)
            else -> throw IllegalArgumentException("Unsupported request method")
        }
        return builder.build()
    }

    private fun createSandboxScope(context: Context): ScriptableObject {
        val scope = context.initSafeStandardObjects()
        DANGEROUS_GLOBALS.forEach(scope::delete)
        return scope
    }

    private fun replaceTemplateVariables(
        source: String,
        apiKey: String,
        baseUrl: String,
        accessToken: String?,
        userId: String?
    ): String = source
        .replace("{{apiKey}}", escapeJsString(apiKey))
        .replace("{{baseUrl}}", escapeJsString(baseUrl))
        .let { value ->
            accessToken?.let { value.replace("{{accessToken}}", escapeJsString(it)) } ?: value
        }
        .let { value -> userId?.let { value.replace("{{userId}}", escapeJsString(it)) } ?: value }

    private fun escapeJsString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace("\b", "\\b")
        .replace("\u000c", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\u0000", "\\0")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    private fun effectiveBaseUrl(script: UsageScript, account: AccountInfo): String? =
        script.baseUrl ?: account.extraSettings["baseUrl"]

    private fun timeoutMillis(script: UsageScript): Long? {
        if (script.timeout <= 0) return null
        return if (script.timeout > MAX_TIMEOUT_MILLIS / 1_000L) {
            MAX_TIMEOUT_MILLIS
        } else {
            script.timeout * 1_000L
        }
    }

    private fun parseAuthorizedOrigin(value: String): WebOrigin? {
        val url = value.trim().toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return runCatching { WebOrigin.from(url) }.getOrNull()
    }

    private fun preprocessScript(source: String): String = SavedScriptCompatibility.rewrite(source)

    private fun hasLiteralRequestUrl(source: String): Boolean {
        val root = try {
            val compatibleSource = preprocessScript(source)
            val environs = CompilerEnvirons().apply {
                languageVersion = Context.VERSION_ES6
            }
            Parser(environs).parse(compatibleSource, SCRIPT_SOURCE_NAME, 1)
        } catch (_: Exception) {
            return false
        }
        val statement = root.singleStatement() as? ExpressionStatement ?: return false
        val config = statement.expression.withoutParentheses() as? ObjectLiteral ?: return false
        if (!config.hasOnlyOrdinaryProperties(CONFIGURATION_PROPERTY_NAMES)) return false

        val requestProperties = config.elements.filter { it.staticName() == "request" }
        if (requestProperties.size != 1) return false
        val request = requestProperties.single().right.withoutParentheses() as? ObjectLiteral
            ?: return false
        if (!request.hasOnlyOrdinaryProperties(REQUEST_PROPERTY_NAMES)) return false

        val urlProperties = request.elements.filter { it.staticName() == "url" }
        if (urlProperties.size != 1) return false
        val url = urlProperties.single().right.withoutParentheses() as? StringLiteral ?: return false
        if (url.value.hasOriginAffectingPlaceholder()) return false

        listOf("method", "body").forEach { name ->
            val property = request.elements.singleOrNull { it.staticName() == name }
            if (property != null && !property.right.isSerializationSafeLiteral()) return false
        }
        val headersProperty = request.elements.singleOrNull { it.staticName() == "headers" }
        if (headersProperty != null) {
            val headers = headersProperty.right.withoutParentheses() as? ObjectLiteral ?: return false
            if (!headers.hasOnlyOrdinaryProperties() ||
                headers.elements.any { !it.right.isSerializationSafeLiteral() }
            ) {
                return false
            }
        }

        val extractorProperties = config.elements.filter { it.staticName() == "extractor" }
        if (extractorProperties.size != 1) return false
        if (extractorProperties.single().right.withoutParentheses() !is FunctionNode) return false
        return true
    }

    private fun AstRoot.singleStatement(): AstNode? =
        iterator().asSequence().filterIsInstance<AstNode>().singleOrNull()

    private tailrec fun AstNode.withoutParentheses(): AstNode =
        if (this is ParenthesizedExpression) expression.withoutParentheses() else this

    private fun ObjectProperty.staticName(): String? = when (val key = left.withoutParentheses()) {
        is Name -> key.identifier
        is StringLiteral -> key.value
        else -> null
    }

    private fun ObjectLiteral.hasOnlyOrdinaryProperties(
        allowedNames: Set<String>? = null
    ): Boolean {
        val names = mutableSetOf<String>()
        for (property in elements) {
            if (property.isMethod) return false
            val name = property.staticName() ?: return false
            if (name in META_OBJECT_PROPERTY_NAMES || !names.add(name)) return false
            if (allowedNames != null && name !in allowedNames) return false
        }
        return true
    }

    private fun AstNode.isSerializationSafeLiteral(): Boolean = when (val node = withoutParentheses()) {
        is StringLiteral, is NumberLiteral -> true
        is KeywordLiteral -> node.type in SERIALIZATION_SAFE_KEYWORD_TOKENS
        else -> false
    }

    private fun String.hasOriginAffectingPlaceholder(): Boolean {
        val authorityStart = indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
        val authorityEnd = listOf('/', '?', '#')
            .map { delimiter -> indexOf(delimiter, authorityStart) }
            .filter { it >= 0 }
            .minOrNull()
            ?: length
        val origin = substring(0, authorityEnd)
        return URL_PLACEHOLDERS.any(origin::contains)
    }

    private fun inspectionFailure(failure: RefreshFailure) = ScriptInspection(
        request = null,
        requiredExtraOrigins = emptySet(),
        staticallyDeterminable = false,
        failure = failure
    )

    private fun failure(failure: RefreshFailure) = ScriptExecutionResult.Failure(failure)

    private fun networkFailure(cause: Throwable? = null) = HttpFetchResult.Failure(
        failure(RefreshFailure.NetworkFailure("Script network request failed", cause = cause))
    )

    private sealed interface HttpFetchResult {
        data class Success(val body: String) : HttpFetchResult
        data class Failure(val result: ScriptExecutionResult.Failure) : HttpFetchResult
        data class Redirect(val target: HttpUrl) : HttpFetchResult
    }

    private const val CONFIGURATION_PHASE = "configuration"
    private const val EXTRACTOR_PHASE = "extractor"
    private const val SCRIPT_SOURCE_NAME = "usage-script"
    private const val INTERNAL_CONFIG = "__usage_config"
    private const val INTERNAL_RESPONSE = "__usage_response"
    private const val INSPECTION_API_KEY = "__INSPECTION_API_KEY__"
    private const val INSPECTION_ACCESS_TOKEN = "__INSPECTION_ACCESS_TOKEN__"
    private const val INSPECTION_USER_ID = "__INSPECTION_USER_ID__"
    private const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    private const val MAX_TIMEOUT_MILLIS = Int.MAX_VALUE.toLong()
    private const val MAX_REDIRECTS = 5
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val CONFIGURATION_PROPERTY_NAMES = setOf("request", "extractor")
    private val REQUEST_PROPERTY_NAMES = setOf("url", "method", "headers", "body")
    private val SERIALIZATION_SAFE_KEYWORD_TOKENS = setOf(Token.NULL, Token.TRUE, Token.FALSE)
    private val META_OBJECT_PROPERTY_NAMES = setOf(
        "__defineGetter__",
        "__defineSetter__",
        "__lookupGetter__",
        "__lookupSetter__",
        "__proto__",
        "constructor",
        "hasOwnProperty",
        "isPrototypeOf",
        "propertyIsEnumerable",
        "prototype",
        "toJSON",
        "toLocaleString",
        "toString",
        "valueOf"
    )
    private val URL_PLACEHOLDERS = listOf(
        "{{apiKey}}",
        "{{baseUrl}}",
        "{{accessToken}}",
        "{{userId}}"
    )
    private val DANGEROUS_GLOBALS = listOf(
        "Packages",
        "java",
        "javax",
        "android",
        "com",
        "org",
        "net",
        "edu",
        "javafx",
        "sun",
        "jdk",
        "dalvik",
        "JavaAdapter"
    )
}
