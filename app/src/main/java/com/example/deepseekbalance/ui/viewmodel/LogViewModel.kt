package com.example.deepseekbalance.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.deepseekbalance.CrashLogger
import com.example.deepseekbalance.R
import com.example.deepseekbalance.data.model.RefreshLogEntry
import com.example.deepseekbalance.data.model.RefreshLogType
import com.example.deepseekbalance.data.repository.LogExporter
import com.example.deepseekbalance.data.repository.RefreshLogStore
import com.example.deepseekbalance.data.repository.WidgetPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogUiState(
    val refreshLogs: List<RefreshLogEntry> = emptyList(),
    val missedCount: Int = 0,
    val logMaxEntries: Int = RefreshLogStore.DEFAULT_MAX_ENTRIES,
    val crashLogs: List<CrashLogger.CrashEntry> = emptyList(),
    val exportResult: String? = null,
    val selectedLogType: RefreshLogType? = null
)

class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    private val widgetPrefs: WidgetPrefs = WidgetPrefs(application)
    private var allLogs: List<RefreshLogEntry> = emptyList()

    init {
        loadLogs()
        loadCrashLogs()
        _uiState.value = _uiState.value.copy(
            logMaxEntries = widgetPrefs.logMaxEntries
        )
    }

    fun loadLogs() {
        try {
            allLogs = RefreshLogStore.getEntries(getApplication())
            applyFilter()
            _uiState.value = _uiState.value.copy(
                missedCount = allLogs.count { it.type == RefreshLogType.MISSED }
            )
        } catch (_: Exception) {}
    }

    fun selectLogType(type: RefreshLogType?) {
        _uiState.value = _uiState.value.copy(selectedLogType = type)
        applyFilter()
    }

    private fun applyFilter() {
        val type = _uiState.value.selectedLogType
        val filtered = if (type != null) {
            allLogs.filter { it.type == type }
        } else {
            allLogs
        }
        _uiState.value = _uiState.value.copy(refreshLogs = filtered)
    }

    fun clearLogs() {
        try {
            RefreshLogStore.clear(getApplication())
            _uiState.value = _uiState.value.copy(refreshLogs = emptyList(), missedCount = 0)
        } catch (_: Exception) {}
    }

    fun setLogMax(count: Int) {
        widgetPrefs.logMaxEntries = count
        _uiState.value = _uiState.value.copy(logMaxEntries = count)
    }

    fun exportLogs() {
        try {
            val path = LogExporter.export(getApplication())
            if (path != null) {
                _uiState.value = _uiState.value.copy(exportResult = path)
            } else {
                _uiState.value = _uiState.value.copy(exportResult = getApplication<Application>().getString(R.string.data_export_fail))
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(exportResult = getApplication<Application>().getString(R.string.data_export_exception, e.message ?: ""))
        }
    }

    fun clearExportResult() {
        _uiState.value = _uiState.value.copy(exportResult = null)
    }

    fun loadCrashLogs() {
        try {
            val app = getApplication<Application>()
            _uiState.value = _uiState.value.copy(crashLogs = CrashLogger.getCrashes(app))
        } catch (_: Exception) {}
    }

    fun clearCrashes() {
        try {
            CrashLogger.clear(getApplication())
            _uiState.value = _uiState.value.copy(crashLogs = emptyList())
        } catch (_: Exception) {}
    }
}
