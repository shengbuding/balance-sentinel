package com.balancesentinel.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.balancesentinel.app.R
import com.balancesentinel.app.data.local.update.DownloadState
import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.ui.viewmodel.UpdateDownloadViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private enum class DialogState {
    INITIAL, DOWNLOADING, COMPLETE, FAILED
}

internal const val UPDATE_DIALOG_CONTENT_TAG = "update_dialog_content"

@Composable
fun UpdateDialog(
    release: GitHubRelease,
    currentVersion: String,
    onDismiss: () -> Unit,
    onSkipVersion: () -> Unit,
    onRemindLater: () -> Unit,
    downloadViewModel: UpdateDownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    val downloadState by downloadViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(release.tagName) { downloadViewModel.bind(release.tagName) }

    val dialogState = when (downloadState.state) {
        DownloadState.QUEUED,
        DownloadState.RUNNING,
        DownloadState.CANCELLING -> DialogState.DOWNLOADING
        DownloadState.COMPLETED -> DialogState.COMPLETE
        DownloadState.FAILED -> DialogState.FAILED
        DownloadState.CANCELLED,
        null -> DialogState.INITIAL
    }
    val downloadedFile = downloadState.targetPath?.let(::File)?.takeIf(File::exists)

    val releaseDate = remember(release.publishedAt) {
        runCatching {
            val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = input.parse(release.publishedAt.orEmpty())
            date?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }
        }.getOrNull() ?: "--"
    }
    val releaseBody = release.body.orEmpty()

    fun installApk(file: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(viewIntent) }
            .recoverCatching { context.startActivity(fallback) }
    }

    fun openDownloadLink() {
        val url = release.htmlUrl.ifEmpty {
            "https://github.com/shengbuding/balance-sentinel/releases/tag/${release.tagName}"
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    AlertDialog(
        onDismissRequest = { if (dialogState != DialogState.DOWNLOADING) onDismiss() },
        title = { Text(stringResource(R.string.update_dialog_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UPDATE_DIALOG_CONTENT_TAG)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                Text(
                    stringResource(R.string.update_release_date, releaseDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                if (releaseBody.isNotEmpty()) {
                    Text(releaseBody, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
                when (dialogState) {
                    DialogState.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.update_downloading, (downloadState.progress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DialogState.COMPLETE -> Text(
                        stringResource(R.string.label_download_complete),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    DialogState.FAILED -> Text(
                        stringResource(
                            R.string.update_download_failed,
                            downloadState.errorMessage ?: stringResource(R.string.update_install_failed)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    DialogState.INITIAL -> Unit
                }
            }
        },
        confirmButton = {
            when (dialogState) {
                DialogState.INITIAL -> TextButton(onClick = { downloadViewModel.start(release) }) {
                    Text(stringResource(R.string.update_download))
                }
                DialogState.COMPLETE -> downloadedFile?.let { file ->
                    TextButton(onClick = { installApk(file) }) {
                        Text(stringResource(R.string.update_install))
                    }
                }
                DialogState.DOWNLOADING -> TextButton(onClick = downloadViewModel::cancel) {
                    Text(stringResource(R.string.update_cancel_download))
                }
                DialogState.FAILED -> TextButton(onClick = { downloadViewModel.start(release) }) {
                    Text(stringResource(R.string.update_retry))
                }
            }
        },
        dismissButton = {
            if (dialogState != DialogState.DOWNLOADING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dialogState != DialogState.FAILED) {
                        TextButton(onClick = onSkipVersion) {
                            Text(stringResource(R.string.update_skip_version))
                        }
                        TextButton(onClick = onRemindLater) {
                            Text(stringResource(R.string.update_remind_later))
                        }
                    } else {
                        TextButton(onClick = ::openDownloadLink) {
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
