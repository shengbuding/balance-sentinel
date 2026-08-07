package com.balancesentinel.app.ui.viewmodel
import com.balancesentinel.app.data.util.Logger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.R
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.LogExporter
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.repository.RoomEventLogRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.SettingsSnapshotState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LogUiState(
    val refreshLogs: List<RefreshLogEntry> = emptyList(),
    val missedCount: Int = 0,
    val logMaxEntries: Int = 100,
    val crashLogs: List<CrashLogger.CrashEntry> = emptyList(),
    val exportResult: String? = null,
    val selectedLogType: RefreshLogType? = null
)

class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LogUiState())
    val uiState: StateFlow<LogUiState> = _uiState.asStateFlow()

    private val settingsRepository = SettingsRepositoryProvider.get(application)
    private var allLogs: List<RefreshLogEntry> = emptyList()

    init {
        loadLogs()
        loadCrashLogs()
        viewModelScope.launch {
            settingsRepository.snapshot.collect { state ->
                if (state is SettingsSnapshotState.Ready) {
                    _uiState.value = _uiState.value.copy(
                        logMaxEntries = state.value.appSettings.logMaxEntries
                    )
                }
            }
        }
    }

    fun loadLogs() {
        try {
            allLogs = kotlinx.coroutines.runBlocking { RoomEventLogRepository(WalletDatabaseProvider.get(getApplication())).newest(1000) }
            applyFilter()
            _uiState.value = _uiState.value.copy(
                missedCount = allLogs.count { it.type == RefreshLogType.MISSED }
            )
        } catch (e: Exception) { Logger.w("LogViewModel", "operation failed", e) }
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
            kotlinx.coroutines.runBlocking { WalletDatabaseProvider.get(getApplication()).eventLogDao().clearAll() }
            allLogs = emptyList()
            _uiState.value = _uiState.value.copy(refreshLogs = emptyList(), missedCount = 0)
        } catch (e: Exception) { Logger.w("LogViewModel", "operation failed", e) }
    }

    fun setLogMax(count: Int) {
        _uiState.value = _uiState.value.copy(logMaxEntries = count)
        viewModelScope.launch {
            settingsRepository.updateSnapshot { current ->
                current.copy(appSettings = current.appSettings.copy(logMaxEntries = count))
            }
        }
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
        } catch (e: Exception) { Logger.w("LogViewModel", "operation failed", e) }
    }

    fun clearCrashes() {
        try {
            CrashLogger.clear(getApplication())
            _uiState.value = _uiState.value.copy(crashLogs = emptyList())
        } catch (e: Exception) { Logger.w("LogViewModel", "operation failed", e) }
    }
}
