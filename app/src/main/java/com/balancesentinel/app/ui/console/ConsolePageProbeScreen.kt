package com.balancesentinel.app.ui.console

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.balancesentinel.app.R
import com.balancesentinel.app.data.console.ConsolePageCaptureSession

/**
 * 「浏览并标记」全屏页：内嵌浏览器 + 两个标记按钮。
 *
 * 用户在这里一边点网站，一边把「登录页」和「余额页」标下来；
 * 点「使用这些页面」后把结果交回表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolePageProbeScreen(
    startUrl: String,
    onBack: () -> Unit,
    onApply: (ConsoleCaptureResult) -> Unit
) {
    val session = remember(startUrl) { ConsolePageCaptureSession() }
    var revision by remember { mutableIntStateOf(0) }
    val markedPages = remember(revision) { session.markedPages }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.console_probe_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.console_back))
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, stringResource(R.string.console_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (markedPages.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            markedPages.forEach { page ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = if (page.kind == ConsolePageKind.LOGIN) {
                                            stringResource(R.string.console_page_login_tag)
                                        } else {
                                            stringResource(R.string.console_page_dashboard_tag)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(72.dp)
                                    )
                                    Text(
                                        text = page.pattern,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            session.unmark(page)
                                            revision++
                                        }
                                    ) {
                                        Text(
                                            stringResource(R.string.console_page_unmark),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Button(
                        onClick = { onApply(session.result()) },
                        enabled = markedPages.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.console_probe_done))
                    }
                }
            }
        }
    ) { padding ->
        ConsolePageProbe(
            startUrl = startUrl,
            session = session,
            onMarked = { revision++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}
