package com.balancesentinel.app.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.update.ApkDownloader
import com.balancesentinel.app.data.update.DownloadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class DialogState {
    INITIAL, DOWNLOADING, COMPLETE, FAILED
}

@Composable
fun UpdateDialog(
    release: GitHubRelease,
    currentVersion: String,
    onDismiss: () -> Unit,
    onSkipVersion: () -> Unit,
    onRemindLater: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { ApkDownloader() }

    var dialogState by remember { mutableStateOf(DialogState.INITIAL) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var errorMessage by remember { mutableStateOf("") }
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    val releaseDate = remember(release.publishedAt) {
        try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            isoFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = isoFormat.parse(release.publishedAt ?: "")
            val outFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            date?.let { outFormat.format(it) } ?: "未知"
        } catch (_: Exception) {
            "未知"
        }
    }

    val releaseBody = release.body ?: ""

    fun startDownload() {
        dialogState = DialogState.DOWNLOADING
        downloadProgress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                downloader.download(
                    release = release,
                    cacheDir = context.cacheDir,
                    onProgress = { progress ->
                        downloadProgress = progress
                    }
                )
            }
            when (result) {
                is DownloadResult.Success -> {
                    downloadedFile = result.file
                    dialogState = DialogState.COMPLETE
                }
                is DownloadResult.Error -> {
                    errorMessage = result.message
                    dialogState = DialogState.FAILED
                }
            }
        }
    }

    fun installApk(file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "com.balancesentinel.app.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            // Grant URI permission to any package that can handle the intent
            context.grantUriPermission(
                "android", apkUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.update_install_failed)
            dialogState = DialogState.FAILED
        }
    }

    fun openDownloadLink() {
        val url = release.htmlUrl.ifEmpty {
            "https://github.com/shengbuding/balance-sentinel/releases/tag/${release.tagName}"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    AlertDialog(
        onDismissRequest = {
            if (dialogState != DialogState.DOWNLOADING) {
                onDismiss()
            }
        },
        title = {
            Text(
                stringResource(R.string.update_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Version info
                Text(
                    stringResource(R.string.update_latest_version, release.tagName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.update_current_version, currentVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Release date
                Text(
                    stringResource(R.string.update_release_date, releaseDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // Release notes (scrollable within the already-scrollable text area)
                if (releaseBody.isNotEmpty()) {
                    Text(
                        releaseBody,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 15
                    )
                    HorizontalDivider()
                }

                // Download progress
                when (dialogState) {
                    DialogState.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.update_downloading,
                                (downloadProgress * 100).toInt()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DialogState.COMPLETE -> {
                        Text(
                            "✓ 下载完成",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    DialogState.FAILED -> {
                        Text(
                            stringResource(R.string.update_download_failed, errorMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    DialogState.INITIAL -> { /* nothing extra */ }
                }
            }
        },
        confirmButton = {
            when (dialogState) {
                DialogState.INITIAL -> {
                    TextButton(onClick = { startDownload() }) {
                        Text(stringResource(R.string.update_download))
                    }
                }
                DialogState.COMPLETE -> {
                    downloadedFile?.let { file ->
                        TextButton(onClick = { installApk(file) }) {
                            Text(stringResource(R.string.update_install))
                        }
                    }
                }
                DialogState.DOWNLOADING -> {
                    TextButton(
                        onClick = {
                            dialogState = DialogState.INITIAL
                        }
                    ) {
                        Text(stringResource(R.string.update_cancel_download))
                    }
                }
                DialogState.FAILED -> {
                    TextButton(onClick = { startDownload() }) {
                        Text(stringResource(R.string.update_retry))
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (dialogState) {
                    DialogState.INITIAL -> {
                        TextButton(onClick = {
                            openDownloadLink()
                        }) {
                            Text(stringResource(R.string.update_open_link))
                        }
                        TextButton(onClick = onSkipVersion) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onRemindLater) {
                            Text(stringResource(R.string.update_remind_later))
                        }
                    }
                    DialogState.COMPLETE -> {
                        TextButton(onClick = {
                            openDownloadLink()
                        }) {
                            Text(stringResource(R.string.update_open_link))
                        }
                        TextButton(onClick = onSkipVersion) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onRemindLater) {
                            Text(stringResource(R.string.update_remind_later))
                        }
                    }
                    DialogState.DOWNLOADING -> {
                        TextButton(onClick = onSkipVersion) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onRemindLater) {
                            Text(stringResource(R.string.update_remind_later))
                        }
                    }
                    DialogState.FAILED -> {
                        TextButton(onClick = {
                            openDownloadLink()
                        }) {
                            Text(stringResource(R.string.update_open_link))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.update_close))
                        }
                    }
                }
            }
        }
    )
}
