package com.balancesentinel.app.ui.console

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.balancesentinel.app.BuildConfig
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.ConsoleUiState

/**
 * 控制台页面 - 统一处理登录和数据显示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    platform: ConsolePlatform,
    uiState: ConsoleUiState,
    onLoginSuccess: (cookies: Map<String, String>, localStorage: Map<String, String>, email: String?) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLoggedIn = uiState.isLoggedIn

    DebugLogger.log("[ConsoleScreen] Render: platform=${platform.name}, id=${platform.id}, isLoggedIn=$isLoggedIn, sessionCookies=${uiState.session?.cookies?.size ?: 0}")

    if (isLoggedIn) {
        ConsoleDashboard(
            platform = platform,
            session = uiState.session,
            onLogout = onLogout,
            onBack = onBack,
            modifier = modifier
        )
    } else {
        ConsoleLogin(
            platform = platform,
            onLoginSuccess = onLoginSuccess,
            onBack = onBack,
            modifier = modifier
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 登录页面
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleLogin(
    platform: ConsolePlatform,
    onLoginSuccess: (cookies: Map<String, String>, localStorage: Map<String, String>, email: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loginDetected by remember { mutableStateOf(false) }
    var cookiesSaved by remember { mutableStateOf(false) }
    var skipLogin by remember { mutableStateOf(false) }

    // 登录成功后立即保存 cookies 和 localStorage
    LaunchedEffect(loginDetected) {
        if (loginDetected && !cookiesSaved) {
            DebugLogger.log("[ConsoleLogin] Login detected, extracting cookies and localStorage")
            webView?.let { wv ->
                extractCookies(wv, platform) { cookies, localStorage, token, email ->
                    val allCookies = cookies.toMutableMap()
                    if (!token.isNullOrBlank()) {
                        allCookies["token"] = token
                    }
                    DebugLogger.log("[ConsoleLogin] Cookies saved: ${allCookies.size}, localStorage: ${localStorage.size}")
                    cookiesSaved = true
                    onLoginSuccess(allCookies, localStorage, email)
                }
            }
        }
    }

    // 跳过登录时直接进入控制台
    if (skipLogin) {
        ConsoleDashboard(
            platform = platform,
            session = null,
            onLogout = { skipLogin = false },
            onBack = onBack,
            modifier = modifier
        )
        return
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "登录 ${platform.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 跳过登录按钮
                    TextButton(onClick = { skipLogin = true }) {
                        Text("跳过登录")
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, "关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        configureWebView()

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                hasError = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                DebugLogger.log("[ConsoleLogin] Page finished: $url")

                                if (url != null && !loginDetected) {
                                    val isSuccess = platform.isLoginSuccess(url)
                                    DebugLogger.log("[ConsoleLogin] isLoginSuccess($url) = $isSuccess")

                                    if (isSuccess) {
                                        loginDetected = true
                                        DebugLogger.log("[${platform.name}] Login success detected: $url")
                                    }
                                }
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    hasError = true
                                    errorMessage = error?.description?.toString() ?: "加载失败"
                                    isLoading = false
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                        }

                        webChromeClient = WebChromeClient()
                        webView = this
                        loadUrl(platform.loginUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 加载指示器
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                )
            }

            // 登录成功提示
            if (loginDetected && !cookiesSaved) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "登录成功，正在保存...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 错误提示
            if (hasError) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "加载失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = {
                            hasError = false
                            loginDetected = false
                            webView?.reload()
                        }) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 控制台仪表盘
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleDashboard(
    platform: ConsolePlatform,
    session: com.balancesentinel.app.data.console.store.ConsoleSession?,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showDebugPanel by remember { mutableStateOf(false) }
    val apiLogs = remember { mutableStateListOf<ApiLogEntry>() }
    val context = LocalContext.current

    val cookies = session?.cookies ?: emptyMap()
    val localStorage = session?.localStorage ?: emptyMap()

    // 获取自定义平台的域名（用于 API 拦截）
    val platformDomains = remember {
        try {
            val dashboardHost = java.net.URL(platform.dashboardUrl).host
            val loginHost = java.net.URL(platform.loginUrl).host
            listOf(dashboardHost, loginHost).filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 退出时不清除数据，只返回
    val handleLogout = {
        onLogout()
    }

    // 返回时不清除数据，只返回
    val handleBack = {
        onBack()
    }

    // 处理系统返回键
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            handleBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${platform.name} 控制台",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (session?.email != null) {
                            Text(
                                text = session.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 调试按钮（仅 Debug 构建）
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = { showDebugPanel = !showDebugPanel }) {
                            Icon(CustomIcons.Analytics, "调试")
                        }
                    }
                    IconButton(onClick = {
                        webView?.reload()
                        apiLogs.clear()
                    }) {
                        Icon(CustomIcons.TrendingUp, "刷新")
                    }
                    IconButton(onClick = handleLogout) {
                        Icon(Icons.Filled.Close, "退出")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ConsoleWebView(
                    url = platform.dashboardUrl,
                    cookies = cookies,
                    localStorage = localStorage,
                    instanceId = platform.id,
                    onLoadingChange = { isLoading = it },
                    onApiRequest = { request ->
                        interceptApiRequest(request, apiLogs, platform.id, platformDomains)
                    },
                    webView = { webView = it }
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    )
                }

                if (showDebugPanel) {
                    ApiDebugPanel(
                        apiLogs = apiLogs.toList(),
                        onDismiss = { showDebugPanel = false },
                        onClear = {
                            apiLogs.clear()
                            DebugLogger.clear()
                        }
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// WebView 组件
// ═══════════════════════════════════════════════════════════

@Composable
private fun ConsoleWebView(
    url: String,
    cookies: Map<String, String> = emptyMap(),
    localStorage: Map<String, String> = emptyMap(),
    instanceId: String = "",
    onLoadingChange: (Boolean) -> Unit,
    onApiRequest: ((WebResourceRequest?) -> WebResourceResponse?)? = null,
    webView: (WebView) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            DebugLogger.log("[ConsoleWebView] Creating WebView for instance: $instanceId, url: $url")

            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                settings.userAgentString = DEFAULT_USER_AGENT

                // 启用 cookies
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                // 保存 cookies 和 localStorage 以便在页面加载后注入
                val cookiesToInject = cookies
                val localStorageToInject = localStorage

                // 获取当前域名
                val currentDomain = try {
                    java.net.URL(url).host
                } catch (e: Exception) {
                    ""
                }

                // 清除当前域名的 cookies
                if (currentDomain.isNotBlank()) {
                    val existingCookies = cookieManager.getCookie(currentDomain)
                    if (existingCookies != null) {
                        existingCookies.split(";").forEach { cookie ->
                            val parts = cookie.trim().split("=", limit = 2)
                            if (parts.isNotEmpty()) {
                                val cookieName = parts[0].trim()
                                cookieManager.setCookie(currentDomain, "$cookieName=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
                            }
                        }
                        cookieManager.flush()
                        DebugLogger.log("[ConsoleWebView] Cleared cookies for domain: $currentDomain")
                    }
                }

                // 设置当前实例的 cookies
                if (cookiesToInject.isNotEmpty() && currentDomain.isNotBlank()) {
                    try {
                        val cookieString = cookiesToInject.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        cookieManager.setCookie(currentDomain, cookieString)
                        cookieManager.flush()
                        DebugLogger.log("[ConsoleWebView] Set ${cookiesToInject.size} cookies for $currentDomain")
                    } catch (e: Exception) {
                        DebugLogger.log("[ConsoleWebView] Failed to set cookies: ${e.message}")
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChange(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChange(false)

                        // 页面加载完成后注入 localStorage
                        if (localStorageToInject.isNotEmpty()) {
                            val localStorageScript = buildString {
                                append("(function() {")
                                append("try {")
                                localStorageToInject.forEach { (key, value) ->
                                    val escapedValue = value.replace("\\", "\\\\").replace("'", "\\'")
                                    append("localStorage.setItem('$key', '$escapedValue');")
                                }
                                append("} catch(e) {}")
                                append("})()")
                            }
                            view?.evaluateJavascript(localStorageScript) { result ->
                                DebugLogger.log("[ConsoleWebView] Injected ${localStorageToInject.size} localStorage items")
                            }
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return onApiRequest?.invoke(request) ?: super.shouldInterceptRequest(view, request)
                    }
                }

                webChromeClient = WebChromeClient()
                webView(this)
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

private fun WebView.configureWebView() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        @Suppress("DEPRECATION")
        databaseEnabled = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        loadWithOverviewMode = true
        useWideViewPort = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    settings.userAgentString = DEFAULT_USER_AGENT

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureWebView, true)
    }
}

/**
 * 从 WebView 提取 Cookie 和 localStorage
 */
private fun extractCookies(
    webView: WebView?,
    platform: ConsolePlatform,
    callback: (cookies: Map<String, String>, localStorage: Map<String, String>, token: String?, email: String?) -> Unit
) {
    val url = webView?.url ?: run {
        callback(emptyMap(), emptyMap(), null, null)
        return
    }

    DebugLogger.log("[extractCookies] Extracting cookies from URL: $url")

    // 使用 CookieManager 提取 cookies
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()

    val cookieString = try {
        cookieManager.getCookie(url)
    } catch (e: Exception) {
        DebugLogger.log("[extractCookies] Failed to get cookie: ${e.message}")
        null
    }

    val cookies = if (!cookieString.isNullOrBlank()) {
        cookieString.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate {
                val parts = it.split("=", limit = 2)
                parts[0].trim() to parts[1].trim()
            }
    } else {
        emptyMap()
    }

    DebugLogger.log("[extractCookies] Extracted ${cookies.size} cookies")

    // 从 localStorage 提取所有数据
    webView.evaluateJavascript(
        """
        (function() {
            try {
                var result = {};
                for (var i = 0; i < localStorage.length; i++) {
                    var key = localStorage.key(i);
                    result[key] = localStorage.getItem(key);
                }
                return JSON.stringify(result);
            } catch(e) { return '{}'; }
        })()
        """.trimIndent()
    ) { localStorageResult ->
        val localStorage = try {
            val jsonStr = localStorageResult?.removeSurrounding("\"") ?: "{}"
            val jsonObject = org.json.JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            jsonObject.keys().forEach { key ->
                map[key] = jsonObject.getString(key)
            }
            map.toMap()
        } catch (e: Exception) {
            DebugLogger.log("[extractCookies] Failed to parse localStorage: ${e.message}")
            emptyMap()
        }

        // 提取 token
        val token = localStorage["userToken"] ?: localStorage["token"] ?: localStorage["access_token"]

        DebugLogger.log("[extractCookies] Extracted ${localStorage.size} localStorage items")
        callback(cookies, localStorage, token, null)
    }
}

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

// ═══════════════════════════════════════════════════════════
// API 拦截
// ═══════════════════════════════════════════════════════════

/** 允许拦截的域名白名单 */
private val ALLOWED_DOMAINS = listOf(
    "platform.deepseek.com",
    "api.deepseek.com",
    "platform.xiaomimimo.com",
    "api.xiaomimimo.com"
)

/**
 * 拦截 API 请求并获取完整响应
 * @return WebResourceResponse 或 null（交给默认处理）
 */
private fun interceptApiRequest(
    request: WebResourceRequest?,
    apiLogs: MutableList<ApiLogEntry>,
    tag: String,
    additionalDomains: List<String> = emptyList()
): WebResourceResponse? {
    val reqUrl = request?.url?.toString() ?: ""
    val method = request?.method ?: "GET"

    // 合并白名单域名
    val allAllowedDomains = ALLOWED_DOMAINS + additionalDomains

    // 检查是否是需要拦截的请求
    val isApiRequest = reqUrl.contains("/api/") || reqUrl.contains("/v1/") || reqUrl.contains("/v2/")
    val isAllowedDomain = allAllowedDomains.any { reqUrl.contains(it) }

    if (!isApiRequest || !isAllowedDomain) {
        return null
    }

    DebugLogger.log("[$tag] API: $method $reqUrl")

    return try {
        val connection = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        // 复制请求头
        request?.requestHeaders?.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        // 添加 Cookie
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(reqUrl)
        if (cookies != null) {
            connection.setRequestProperty("Cookie", cookies)
        }

        connection.connect()

        val statusCode = connection.responseCode
        val inputStream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        // 读取完整响应（处理 gzip）
        val responseBody = try {
            val encoding = connection.contentEncoding
            val bytes = if (encoding != null && encoding.contains("gzip", ignoreCase = true)) {
                java.util.zip.GZIPInputStream(inputStream).readBytes()
            } else {
                inputStream?.readBytes() ?: ByteArray(0)
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            inputStream?.readBytes()?.let { String(it, Charsets.UTF_8) } ?: ""
        }

        // 记录日志
        val entry = ApiLogEntry(
            url = reqUrl,
            method = method,
            statusCode = statusCode,
            responseBody = responseBody
        )
        synchronized(apiLogs) {
            apiLogs.removeAll { it.url == reqUrl }
            apiLogs.add(entry)
            if (apiLogs.size > 50) apiLogs.removeFirst()
        }

        val contentType = connection.contentType ?: "application/json"
        val encoding = connection.contentEncoding ?: "utf-8"

        WebResourceResponse(
            contentType,
            encoding,
            statusCode,
            connection.responseMessage,
            connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to it.value.joinToString(", ") },
            responseBody.byteInputStream()
        )
    } catch (e: Exception) {
        DebugLogger.log("[$tag] Error: ${e.message}")
        null
    }
}
