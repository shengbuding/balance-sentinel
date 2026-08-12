package com.balancesentinel.app.data.update

import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import java.io.File

data class DownloadTarget(
    val operationId: String,
    val sourceUrl: String,
    val temporaryFile: File,
    val targetFile: File
)

sealed interface DownloadFileResult {
    data class ReadyToPublish(val temporaryFile: File, val bytes: Long) : DownloadFileResult
    data class Published(val file: File, val bytes: Long) : DownloadFileResult
    data class Failure(val code: String, val message: String) : DownloadFileResult
}

data class DownloadClaimResult(
    val operation: DownloadOperationEntity,
    val created: Boolean
)

object DownloadFailureCode {
    const val HTTP = "http_error"
    const val EMPTY_BODY = "empty_body"
    const val LENGTH_MISMATCH = "length_mismatch"
    const val INVALID_APK = "invalid_apk"
    const val IO = "io_error"
    const val PUBLISH = "atomic_publish_failed"
    const val OWNERSHIP_LOST = "ownership_lost"
}
