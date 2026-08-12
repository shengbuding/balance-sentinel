package com.balancesentinel.app.ui.viewmodel

import com.balancesentinel.app.data.repository.DataExporter

enum class DataOperationKind {
    EXPORT_HISTORY,
    IMPORT_HISTORY
}

data class DataImportSummary(
    val summariesInFile: Int,
    val summariesImported: Int,
    val recordsInFile: Int,
    val recordsImported: Int,
    val snapshotsInFile: Int,
    val snapshotsImported: Int,
    val logsInFile: Int,
    val logsImported: Int
) {
    companion object {
        fun from(result: DataExporter.ImportResult) = DataImportSummary(
            summariesInFile = result.summariesInFile,
            summariesImported = result.summariesImported,
            recordsInFile = result.recordsInFile,
            recordsImported = result.recordsImported,
            snapshotsInFile = result.snapshotsInFile,
            snapshotsImported = result.snapshotsImported,
            logsInFile = result.logsInFile,
            logsImported = result.logsImported
        )
    }
}

sealed interface DataOperationState {
    data object Idle : DataOperationState

    data class Running(
        val operationId: String,
        val kind: DataOperationKind,
        val progressPercent: Int? = null
    ) : DataOperationState

    data class Succeeded(
        val operationId: String,
        val kind: DataOperationKind,
        val importSummary: DataImportSummary? = null
    ) : DataOperationState

    data class Failed(
        val operationId: String,
        val kind: DataOperationKind
    ) : DataOperationState

    data class Cancelled(
        val operationId: String,
        val kind: DataOperationKind
    ) : DataOperationState
}
