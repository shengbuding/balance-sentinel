package com.balancesentinel.app.ui.console

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.balancesentinel.app.R
import com.balancesentinel.app.data.console.ConsoleAutoMarkHeuristics
import com.balancesentinel.app.data.console.ConsolePageCaptureSession

/**
 * 页面浏览与标记。
 *
 * 用户在这里输入一个网站，然后一边点一边把「登录页」「余额页」标下来；
 * 页面看起来像余额/用量页时也会自动标一次（有上限，不覆盖手动标记）。
 */
@Composable
fun ConsolePageProbe(
    startUrl: String,
    session: ConsolePageCaptureSession,
    onMarked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(startUrl) }
    var isLoading by remember { mutableStateOf(true) }
    var autoNoteVisible by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val openExternal = remember(context) { consoleExternalNavigator(context) }

    // 离开这个页面时把 WebView 彻底销毁：内嵌浏览器持有自己的渲染进程，
    // 和 ConsoleWebView 用同一套清理方式，避免返回表单后还留着一个后台 WebView。
    DisposableEffect(Unit) {
        onDispose {
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            webView = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.console_probe_current_page),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = ConsolePlatformDraft.hostAndPath(currentUrl),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.console_refresh_page))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = {
                    if (session.mark(ConsolePageKind.LOGIN, currentUrl)) onMarked()
                },
                label = { Text(stringResource(R.string.console_probe_mark_login)) },
                modifier = Modifier.weight(1f)
            )
            AssistChip(
                onClick = {
                    if (session.mark(ConsolePageKind.DASHBOARD, currentUrl)) onMarked()
                },
                label = { Text(stringResource(R.string.console_probe_mark_dashboard)) },
                modifier = Modifier.weight(1f)
            )
        }

        if (session.markedPages.isEmpty()) {
            Text(
                text = stringResource(R.string.console_probe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                session.markedPages.forEach { page ->
                    AssistChip(
                        onClick = {
                            session.unmark(page)
                            onMarked()
                        },
                        label = {
                            Text(
                                text = if (page.kind == ConsolePageKind.LOGIN) {
                                    stringResource(R.string.console_page_login_tag)
                                } else {
                                    stringResource(R.string.console_page_dashboard_tag)
                                } + " " + page.pattern,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.console_page_unmark),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        if (autoNoteVisible) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.console_probe_auto_marked),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { autoNoteVisible = false }) {
                        Text(stringResource(R.string.console_close))
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 4.dp),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            tonalElevation = 1.dp
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        configureProbeSettings()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                val finishedUrl = url ?: return
                                currentUrl = finishedUrl
                                if (ConsoleAutoMarkHeuristics.looksLikeLoginPage(finishedUrl)) {
                                    if (session.mark(ConsolePageKind.LOGIN, finishedUrl, auto = true)) {
                                        autoNoteVisible = true
                                        onMarked()
                                    }
                                } else {
                                    view?.evaluateJavascript(READ_VISIBLE_TEXT) { raw ->
                                        val text = raw.orEmpty().unescapeJsonString()
                                        if (ConsoleAutoMarkHeuristics.looksLikeDashboard(finishedUrl, text)) {
                                            if (session.mark(ConsolePageKind.DASHBOARD, finishedUrl, auto = true)) {
                                                autoNoteVisible = true
                                                onMarked()
                                            }
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                if (request?.isForMainFrame != true) return false
                                val target = request.url.toString()
                                return if (ConsolePageCaptureSession.isSameSite(startUrl, target)) {
                                    false
                                } else {
                                    openExternal.open(request.url)
                                    true
                                }
                            }
                        }
                        webView = this
                        loadUrl(startUrl)
                    }
                }
            )
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun WebView.configureProbeSettings() {
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
    android.webkit.CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@configureProbeSettings, true)
    }
}

/** 只读可见文本，不修改页面。 */
private const val READ_VISIBLE_TEXT =
    "(function(){try{return (document.body && document.body.innerText || '').slice(0, 20000);}catch(e){return '';}})()"

/** evaluateJavascript 返回的是 JSON 字符串字面量，需要去掉外层引号并还原转义。 */
private fun String.unescapeJsonString(): String {
    if (length < 2 || first() != '"' || last() != '"') return this
    return runCatching { org.json.JSONArray("[$this]").optString(0) }.getOrDefault("")
}