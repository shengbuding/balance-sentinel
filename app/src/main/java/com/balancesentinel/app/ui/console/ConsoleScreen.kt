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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.balancesentinel.app.BuildConfig
import com.balancesentinel.app.data.console.DebugLogger

/**
 * 通用控制台页面 - 支持登录和数据显示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    platform: ConsolePlatform,
    isLoggedIn: Boolean,
    userEmail: String?,
    onLoginSuccess: (cookies: Map<String, String>, email: String?) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 如果已登录，显示控制台；否则显示登录页
    if (isLoggedIn) {
        ConsoleDashboard(
            platform = platform,
            userEmail = userEmail,
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
    onLoginSuccess: (cookies: Map<String, String>, email: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loginDetected by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    // 登录成功后倒计时
    LaunchedEffect(loginDetected) {
        if (loginDetected) {
            countdown = 3
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                countdown--
            }
            webView?.let { wv ->
                extractCookies(wv) { cookies, token, email ->
                    val allCookies = cookies.toMutableMap()
                    if (!token.isNullOrBlank()) {
                        allCookies["token"] = token
                    }
                    DebugLogger.log("[${platform.name}] Login success, cookies: ${allCookies.size}")
                    onLoginSuccess(allCookies, email)
                }
            }
        }
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
                                if (url != null && !loginDetected && platform.isLoginSuccess(url)) {
                                    loginDetected = true
                                    DebugLogger.log("[${platform.name}] Login success detected: $url")
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

            // 倒计时提示
            if (loginDetected && countdown > 0) {
                CountdownCard(
                    countdown = countdown,
                    onSkip = {
                        webView?.let { wv ->
                            extractCookies(wv) { cookies, token, email ->
                                val allCookies = cookies.toMutableMap()
                                if (!token.isNullOrBlank()) allCookies["token"] = token
                                onLoginSuccess(allCookies, email)
                            }
                        }
                    }
                )
            }

            // 错误提示
            if (hasError) {
                ErrorCard(
                    message = errorMessage,
                    onRetry = {
                        hasError = false
                        loginDetected = false
                        webView?.reload()
                    }
                )
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
    userEmail: String?,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showDebugPanel by remember { mutableStateOf(false) }
    val apiLogs = remember { mutableStateListOf<ApiLogEntry>() }

    Scaffold(
        topBar = {
            ConsoleTopBar(
                title = "${platform.name} 控制台",
                subtitle = userEmail,
                onBack = onBack,
                onToggleDebug = { showDebugPanel = !showDebugPanel },
                onRefresh = {
                    webView?.reload()
                    apiLogs.clear()
                },
                onLogout = onLogout
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ConsoleWebView(
                url = platform.dashboardUrl,
                onUrlChange = { currentUrl = it },
                onLoadingChange = { isLoading = it },
                onApiRequest = { request ->
                    interceptApiRequest(request, apiLogs, platform.id)
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

// ═══════════════════════════════════════════════════════════
// 通用 UI 组件
// ═══════════════════════════════════════════════════════════

@Composable
private fun CountdownCard(
    countdown: Int,
    onSkip: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = MaterialTheme.shapes.medium
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
                text = "登录成功！${countdown}秒后返回...",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            TextButton(onClick = onSkip) {
                Text("立即返回")
            }
        }
    }
}

@Composable
private fun BoxScope.ErrorCard(
    message: String,
    onRetry: () -> Unit
) {
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
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetry) {
                Text("重试")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

/**
 * 配置 WebView 通用设置
 */
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
 * 从 WebView 提取 Cookie 和 localStorage token
 */
private fun extractCookies(
    webView: WebView?,
    callback: (cookies: Map<String, String>, token: String?, email: String?) -> Unit
) {
    val url = webView?.url ?: run {
        callback(emptyMap(), null, null)
        return
    }

    // 安全校验：只从目标平台域名提取 Cookie
    val allowedDomains = listOf("platform.deepseek.com", "platform.xiaomimimo.com")
    if (allowedDomains.none { url.contains(it) }) {
        DebugLogger.log("[Console] Skipping cookie extraction for non-platform URL: $url")
        callback(emptyMap(), null, null)
        return
    }

    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()
    val cookieString = try {
        cookieManager.getCookie(url)
    } catch (e: Exception) {
        DebugLogger.log("[Console] Failed to get cookie: ${e.message}")
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

    DebugLogger.log("[Console] Cookies: ${cookies.keys.joinToString()}")

    // 从 localStorage 提取 token
    try {
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    // 尝试各种可能的 token key
                    var keys = ['userToken', 'token', 'access_token', 'ds_user_token'];
                    for (var i = 0; i < keys.length; i++) {
                        var val = localStorage.getItem(keys[i]);
                        if (val) {
                            try {
                                var obj = JSON.parse(val);
                                if (obj.value) return obj.value;
                            } catch(e) {
                                if (val.length > 20) return val;
                            }
                        }
                    }
                    return '';
                } catch(e) { return ''; }
            })()
            """.trimIndent()
        ) { result ->
            val token = try {
                result?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                DebugLogger.log("[Console] Failed to parse token: ${e.message}")
                null
            }
            DebugLogger.log("[Console] Token: ${token?.take(30)}")
            callback(cookies, token, null)
        }
    } catch (e: Exception) {
        DebugLogger.log("[Console] Failed to evaluateJavascript: ${e.message}")
        callback(cookies, null, null)
    }
}
