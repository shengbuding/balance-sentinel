package com.balancesentinel.app.ui.console

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.balancesentinel.app.data.console.ConsoleCookieInjector
import com.balancesentinel.app.data.console.ConsoleCookieSink
import com.balancesentinel.app.data.console.DebugLogger
import com.balancesentinel.app.data.console.ConsoleExternalNavigator
import com.balancesentinel.app.data.console.ConsoleNavigationHandler
import com.balancesentinel.app.data.console.ConsoleOriginPolicy
import com.balancesentinel.app.data.debug.ApiDebugEntry
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.debug.DebugCapture
import com.balancesentinel.app.data.debug.DebugCapturePolicy
import com.balancesentinel.app.data.debug.SensitiveDataRedactor
import com.balancesentinel.app.data.network.BoundedResponseReader
import com.balancesentinel.app.data.network.NetworkResponseException
import com.balancesentinel.app.data.network.ResponseBudget
import com.balancesentinel.app.ui.CustomIcons
import com.balancesentinel.app.ui.viewmodel.ConsoleUiState
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.io.ByteArrayInputStream
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

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
    val content = resolveConsoleScreenContent(platform, uiState)

    DebugLogger.log("[ConsoleScreen] Render: platform=${platform.name}, id=${platform.id}, isLoggedIn=${uiState.isLoggedIn}, sessionCookies=${uiState.session?.cookies?.size ?: 0}")

    when (content) {
        ConsoleScreenContent.Dashboard -> {
            val policy = remember(platform) { ConsoleOriginPolicy(platform) }
            ConsoleDashboard(
                platform = platform,
                policy = policy,
                session = uiState.session,
                onLogout = onLogout,
                onBack = onBack,
                modifier = modifier
            )
        }
        ConsoleScreenContent.Login -> {
            val policy = remember(platform) { ConsoleOriginPolicy(platform) }
            ConsoleLogin(
                platform = platform,
                policy = policy,
                onLoginSuccess = onLoginSuccess,
                onBack = onBack,
                modifier = modifier
            )
        }
        ConsoleScreenContent.InvalidConfiguration -> ConsoleConfigurationError(
            platform = platform,
            onBack = onBack,
            modifier = modifier
        )
        ConsoleScreenContent.LogoutProgress -> ConsoleLogoutProgress(modifier)
    }
}

internal enum class ConsoleScreenContent {
    Login,
    Dashboard,
    InvalidConfiguration,
    LogoutProgress
}

internal fun resolveConsoleScreenContent(
    platform: ConsolePlatform,
    uiState: ConsoleUiState
): ConsoleScreenContent {
    return when {
        ConsoleOriginPolicy.createOrNull(platform) == null -> ConsoleScreenContent.InvalidConfiguration
        uiState.isLoading && uiState.isLoggedIn -> ConsoleScreenContent.LogoutProgress
        uiState.isLoggedIn -> ConsoleScreenContent.Dashboard
        else -> ConsoleScreenContent.Login
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleConfigurationError(
    platform: ConsolePlatform,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(platform.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Invalid platform configuration")
            }
        }
    }
}

@Composable
private fun ConsoleLogoutProgress(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

// ═══════════════════════════════════════════════════════════
// 登录页面
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleLogin(
    platform: ConsolePlatform,
    policy: ConsoleOriginPolicy,
    onLoginSuccess: (cookies: Map<String, String>, localStorage: Map<String, String>, email: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val navigationHandler = remember(context, policy) {
        ConsoleNavigationHandler(policy, consoleExternalNavigator(context))
    }
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

    // 跳过登录时，先尝试提取当前 cookies，再进入控制台
    if (skipLogin) {
        // 如果还没有保存过 cookies，先尝试提取
        if (!cookiesSaved) {
            webView?.let { wv ->
                extractCookies(wv, platform) { cookies, localStorage, token, email ->
                    val allCookies = cookies.toMutableMap()
                    if (!token.isNullOrBlank()) {
                        allCookies["token"] = token
                    }
                    if (allCookies.isNotEmpty()) {
                        DebugLogger.log("[ConsoleLogin] Skip login but saving cookies: ${allCookies.size}")
                        cookiesSaved = true
                        onLoginSuccess(allCookies, localStorage, email)
                    }
                }
            }
        }

        ConsoleDashboard(
            platform = platform,
            policy = policy,
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
                                DebugLogger.log("[ConsoleLogin] Page finished: ${redactConsoleUrl(url)}")

                                if (url != null && !loginDetected) {
                                    val isSuccess = platform.isLoginSuccess(url)
                                    DebugLogger.log("[ConsoleLogin] Login success detected: $isSuccess")

                                    if (isSuccess) {
                                        loginDetected = true
                                        DebugLogger.log("[${platform.name}] Login success detected")
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

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                if (request?.isForMainFrame != true) return false
                                return navigationHandler.shouldOverride(request.url.toString())
                            }
                        }

                        webChromeClient = WebChromeClient()
                        webView = this
                        loadUrl(platform.loginUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            DisposableEffect(Unit) {
                onDispose {
                    webView?.let {
                        it.stopLoading()
                        it.destroy()
                    }
                }
            }

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
    policy: ConsoleOriginPolicy,
    session: com.balancesentinel.app.data.console.store.ConsoleSession?,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var loginExpired by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showClearSessionDialog by remember { mutableStateOf(false) }
    val captureEnabled = DebugCapturePolicy.enabled()
    val debugAccountId = "console:${platform.id}"
    var debugRevision by remember { mutableIntStateOf(0) }
    val apiLogs = remember(debugRevision, captureEnabled) {
        if (captureEnabled) ApiDebugStore.getEntries(debugAccountId) else emptyList()
    }
    val context = LocalContext.current

    val cookies = session?.cookies ?: emptyMap()
    val localStorage = session?.localStorage ?: emptyMap()

    // 构建会话调试信息
    val sessionDebugInfo = remember(session, currentUrl, captureEnabled) {
        consoleDebugProjection(captureEnabled) {
            SessionDebugInfo(
                platformId = platform.id,
                platformName = platform.name,
                isLoggedIn = session != null,
                isSessionValid = session != null,
                cookieCount = session?.cookies?.size ?: 0,
                localStorageCount = session?.localStorage?.size ?: 0,
                email = session?.email,
                currentUrl = redactConsoleUrl(currentUrl),
                sessionCreatedAt = session?.loginTime?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it)) },
                sessionExpiresAt = session?.lastActiveTime?.plus(THIRTY_DAYS_MS)?.let {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it))
                }
            )
        }
    }

    // 检测登录失效
    LaunchedEffect(currentUrl) {
        currentUrl?.let { url ->
            if (isLoginPage(url, platform) && !loginExpired) {
                loginExpired = true
                DebugLogger.log("[ConsoleDashboard] Login expired detected")
            }
        }
    }

    // 登录失效时自动返回登录页面
    if (loginExpired) {
        AlertDialog(
            onDismissRequest = { loginExpired = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("登录已失效") },
            text = { Text("检测到您的登录状态已失效，请重新登录") },
            confirmButton = {
                Button(onClick = {
                    loginExpired = false
                    onLogout()
                }) {
                    Text("重新登录")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { loginExpired = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 清除登录状态确认对话框
    if (showClearSessionDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("清除登录状态") },
            text = {
                Column {
                    Text("确定要清除「${platform.name}」的登录状态吗？")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• 所有登录信息将被清除\n• 需要重新登录才能访问控制台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearSessionDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearSessionDialog = false }) {
                    Text("取消")
                }
            }
        )
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
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (captureEnabled) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                CustomIcons.Analytics,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text("调试面板")
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDebugPanel = !showDebugPanel
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text("刷新页面")
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    webView?.reload()
                                    ApiDebugStore.clearEntries(debugAccountId)
                                    debugRevision++
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            "清除登录状态",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showClearSessionDialog = true
                                }
                            )
                        }
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
                    policy = policy,
                    cookies = cookies,
                    localStorage = localStorage,
                    instanceId = platform.id,
                    onLoadingChange = { isLoading = it },
                    onApiRequest = if (captureEnabled) {
                        { request ->
                            interceptApiRequest(
                                request = request,
                                tag = platform.id,
                                policy = policy,
                                debuggable = true,
                                entrySink = { entry ->
                                    ApiDebugStore.addEntry(entry)
                                    debugRevision++
                                }
                            )
                        }
                    } else {
                        null
                    },
                    onPageFinished = { url ->
                        currentUrl = url
                        DebugLogger.log("[ConsoleDashboard] Page finished: ${redactConsoleUrl(url)}")
                    },
                    onReceivedError = { error ->
                        DebugLogger.log("[ConsoleDashboard] Error: $error")
                    },
                    webView = { webView = it }
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (captureEnabled && showDebugPanel) {
                    ApiDebugPanel(
                        apiLogs = apiLogs,
                        sessionInfo = sessionDebugInfo,
                        onDismiss = { showDebugPanel = false },
                        onClear = {
                            ApiDebugStore.clearEntries(debugAccountId)
                            debugRevision++
                            DebugLogger.clear()
                        },
                        onLogout = { showClearSessionDialog = true }
                    )
                }
            }
        }
    }
}

/**
 * 检测是否是登录页面
 */
private fun isLoginPage(url: String, platform: ConsolePlatform): Boolean {
    return platform.isLoginPage(url)
}

// ═══════════════════════════════════════════════════════════
// WebView 组件
// ═══════════════════════════════════════════════════════════

@Composable
private fun ConsoleWebView(
    url: String,
    policy: ConsoleOriginPolicy,
    cookies: Map<String, String> = emptyMap(),
    localStorage: Map<String, String> = emptyMap(),
    instanceId: String = "",
    onLoadingChange: (Boolean) -> Unit,
    onApiRequest: ((WebResourceRequest?) -> WebResourceResponse?)? = null,
    onPageFinished: ((String?) -> Unit)? = null,
    onReceivedError: ((String?) -> Unit)? = null,
    webView: (WebView) -> Unit = {}
) {
    val context = LocalContext.current
    val navigationHandler = remember(context, policy) {
        ConsoleNavigationHandler(policy, consoleExternalNavigator(context))
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let {
                it.stopLoading()
                it.destroy()
            }
        }
    }

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

                // 注入 session 中保存的 cookies（如果有的话）
                // 注意：不再清除现有 cookies，保留 WebView 中已有的登录状态
                if (cookiesToInject.isNotEmpty()) {
                    try {
                        ConsoleCookieInjector(policy, cookieManager.asConsoleCookieSink())
                            .inject(cookiesToInject)
                        DebugLogger.log("[ConsoleWebView] Injected ${cookiesToInject.size} cookies")
                    } catch (e: Exception) {
                        DebugLogger.log("[ConsoleWebView] Failed to inject cookies: ${e.message}")
                    }
                } else {
                    DebugLogger.log("[ConsoleWebView] No cookies to inject")
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChange(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingChange(false)
                        onPageFinished?.invoke(url)

                        injectConsoleLocalStorage(view, url, localStorageToInject, policy)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            val errorMsg = error?.description?.toString() ?: "Unknown error"
                            DebugLogger.log("[ConsoleWebView] Error: $errorMsg")
                            onReceivedError?.invoke(errorMsg)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return onApiRequest?.invoke(request) ?: super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        if (request?.isForMainFrame != true) return false
                        return navigationHandler.shouldOverride(request.url.toString())
                    }
                }

                webChromeClient = WebChromeClient()
                webView(this)
                webViewRef = this
                loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

internal fun consoleExternalNavigator(context: Context): ConsoleExternalNavigator =
    ConsoleExternalNavigator { uri ->
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

private fun CookieManager.asConsoleCookieSink(): ConsoleCookieSink = object : ConsoleCookieSink {
    override fun setCookie(url: String, cookie: String) {
        this@asConsoleCookieSink.setCookie(url, cookie)
    }

    override fun flush() {
        this@asConsoleCookieSink.flush()
    }
}

internal fun injectConsoleLocalStorage(
    view: WebView?,
    url: String?,
    localStorage: Map<String, String>,
    policy: ConsoleOriginPolicy
) {
    if (view == null || url == null || localStorage.isEmpty() || !policy.canInjectLocalStorage(url)) {
        return
    }
    val script = buildString {
        append("(function() {")
        append("try {")
        localStorage.forEach { (key, value) ->
            append("localStorage.setItem('")
            append(key.escapeForSingleQuotedJavaScript())
            append("', '")
            append(value.escapeForSingleQuotedJavaScript())
            append("');")
        }
        append("} catch(e) {}")
        append("})()")
    }
    view.evaluateJavascript(script) {
        DebugLogger.log("[ConsoleWebView] Injected ${localStorage.size} localStorage items")
    }
}

private fun String.escapeForSingleQuotedJavaScript(): String =
    replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

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

    DebugLogger.log("[extractCookies] Extracting session data")

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

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
private fun redactConsoleUrl(url: String?): String? {
    val parsed = url?.toHttpUrlOrNull() ?: return null
    val port = if (parsed.port == 443 || parsed.port == 80) "" else ":${parsed.port}"
    return "${parsed.scheme}://${parsed.host}$port"
}

internal fun <T> consoleDebugProjection(
    captureEnabled: Boolean,
    factory: () -> T
): T? = if (captureEnabled) factory() else null

// ═══════════════════════════════════════════════════════════
// API 拦截
// ═══════════════════════════════════════════════════════════

/** 允许拦截的域名白名单 */
/**
 * 拦截 API 请求并获取完整响应
 * @return WebResourceResponse 或 null（交给默认处理）
 */
internal fun interceptApiRequest(
    request: WebResourceRequest?,
    tag: String,
    policy: ConsoleOriginPolicy,
    debuggable: Boolean,
    entrySink: (ApiDebugEntry) -> Unit,
    responseCookieSink: ConsoleCookieSink? = null
): WebResourceResponse? {
    if (!DebugCapturePolicy.enabled(debuggable)) return null
    val reqUrl = request?.url?.toString() ?: ""
    val method = request?.method ?: "GET"
    val startTime = System.currentTimeMillis()

    // 检查是否是需要拦截的请求
    if (!policy.isAllowedApiRequest(reqUrl)) {
        return null
    }

    // POST/PUT/PATCH 请求体无法通过 shouldInterceptRequest 获取，直接放行
    if (request?.method != "GET") return null

    val diagnosticUrl = reqUrl.toHttpUrlOrNull()?.let(SensitiveDataRedactor::redactUrl).orEmpty()
    DebugLogger.log("[$tag] API request: $method $diagnosticUrl")

    val forwardedHeaders = request?.requestHeaders.orEmpty()
    val diagnosticHeaders = SensitiveDataRedactor.redactHeaders(forwardedHeaders)

    return try {
        val connection = java.net.URL(reqUrl).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        // 复制请求头
        forwardedHeaders.forEach { (key, value) ->
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

        val responseBytes = try {
            val bodyStream = inputStream ?: ByteArrayInputStream(ByteArray(0))
            BoundedResponseReader(
                ResponseBudget.CONSOLE.maxEncodedBytes,
                "console-encoded",
                NetworkResponseException.Reason.ENCODED_LIMIT
            ).readBytes(
                input = bodyStream,
                declaredLength = connection.contentLengthLong,
                closeInput = true
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NetworkResponseException) {
            connection.disconnect()
            throw error
        }

        // Validate the decompressed budget independently. The raw transport
        // bytes remain unchanged for WebView, while diagnostics use the same
        // bounded path and cannot turn a gzip bomb into apparent success.
        if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            BoundedResponseReader(
                ResponseBudget.CONSOLE.maxDecodedBytes,
                "console-decoded"
            ).readBytes(GZIPInputStream(responseBytes.inputStream()))
        } else {
            BoundedResponseReader(
                ResponseBudget.CONSOLE.maxDecodedBytes,
                "console-decoded"
            ).readBytes(responseBytes.inputStream())
        }
        val capturedResponse = captureConsoleDiagnostic(
            responseBytes,
            connection.contentEncoding
        )

        // 记录响应头
        val responseHeaderFields = connection.headerFields.entries
            .filter { it.key != null }
        val responseSetCookies = responseHeaderFields
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
        if (responseSetCookies.isNotEmpty()) {
            val cookieSink = responseCookieSink ?: cookieManager.asConsoleCookieSink()
            responseSetCookies.forEach { cookie -> cookieSink.setCookie(reqUrl, cookie) }
            cookieSink.flush()
        }
        val transportResponseHeaders = responseHeaderFields
            .filterNot { it.key.equals("Set-Cookie", ignoreCase = true) }
            .associate { it.key to it.value.joinToString(", ") }
        val diagnosticResponseHeaders = SensitiveDataRedactor.redactHeaders(transportResponseHeaders)

        // 记录日志
        val entry = ApiDebugEntry(
            accountId = "console:$tag",
            url = diagnosticUrl,
            method = method,
            requestHeaders = diagnosticHeaders,
            requestBody = null,
            statusCode = statusCode,
            responseHeaders = diagnosticResponseHeaders,
            responseBody = capturedResponse.text,
            timestamp = startTime,
            duration = System.currentTimeMillis() - startTime,
            error = if (statusCode in 200..299) null else capturedResponse.text,
            providerType = "Console",
            endpoint = request?.url?.path,
            responseBodyTruncated = capturedResponse.truncated,
            errorTruncated = statusCode !in 200..299 && capturedResponse.truncated
        )
        entrySink(ApiDebugStore.sanitizedEntry(entry))

        // 检测认证失败（401/403）
        if (statusCode == 401 || statusCode == 403) {
            DebugLogger.log("[$tag] Auth failed: $statusCode")
        }

        val contentType = connection.contentType ?: "application/json"
        val encoding = contentType.substringAfter("charset=", "")
            .substringBefore(';')
            .trim()
            .ifEmpty { "utf-8" }

        WebResourceResponse(
            contentType,
            encoding,
            statusCode,
            connection.responseMessage,
            transportResponseHeaders,
            responseBytes.inputStream()
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        DebugLogger.log("[$tag] API request failed: ${e.javaClass.simpleName}")

        // 记录错误日志
        val capturedError = SensitiveDataRedactor.redactCaptured(
            "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        )
        val entry = ApiDebugEntry(
            accountId = "console:$tag",
            url = diagnosticUrl,
            method = method,
            requestHeaders = diagnosticHeaders,
            requestBody = null,
            statusCode = 0,
            responseHeaders = emptyMap(),
            responseBody = "",
            timestamp = startTime,
            duration = System.currentTimeMillis() - startTime,
            error = capturedError.text,
            providerType = "Console",
            endpoint = request?.url?.path,
            errorTruncated = capturedError.truncated
        )
        entrySink(ApiDebugStore.sanitizedEntry(entry))

        null
    }
}

private fun captureConsoleDiagnostic(
    responseBytes: ByteArray,
    contentEncoding: String?
) = runCatching {
    val input = if (contentEncoding?.contains("gzip", ignoreCase = true) == true) {
        java.util.zip.GZIPInputStream(responseBytes.inputStream())
    } else {
        responseBytes.inputStream()
    }
    input.use { DebugCapture.captureUtf8(it) }
}.getOrElse {
    DebugCapture.captureUtf8(responseBytes.inputStream())
}
