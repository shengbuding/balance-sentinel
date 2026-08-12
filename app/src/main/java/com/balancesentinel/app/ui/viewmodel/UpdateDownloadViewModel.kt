package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import com.balancesentinel.app.data.local.update.DownloadState
import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.update.ApkDownloadRepository
import com.balancesentinel.app.data.update.ApkDownloadRepositoryContract
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UpdateDownloadUiState(
    val tag: String? = null,
    val operationId: String? = null,
    val state: DownloadState? = null,
    val progress: Float = 0f,
    val targetPath: String? = null,
    val errorMessage: String? = null
)

class UpdateDownloadViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: ApkDownloadRepositoryContract = ApkDownloadRepository(application)
) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(UpdateDownloadUiState())
    val uiState: StateFlow<UpdateDownloadUiState> = mutableUiState.asStateFlow()
    private var observationJob: Job? = null
    private var newestCreatedAt = Long.MIN_VALUE

    fun bind(tag: String) {
        if (mutableUiState.value.tag == tag && observationJob?.isActive == true) return
        observationJob?.cancel()
        newestCreatedAt = Long.MIN_VALUE
        mutableUiState.value = UpdateDownloadUiState(tag = tag)
        observationJob = viewModelScope.launch {
            repository.observe(tag).collect { operation ->
                if (operation == null || operation.createdAt < newestCreatedAt) return@collect
                newestCreatedAt = operation.createdAt
                mutableUiState.value = operation.toUiState()
            }
        }
    }

    fun start(release: GitHubRelease) {
        bind(release.tagName)
        viewModelScope.launch {
            runCatching { repository.start(release) }
                .onFailure { error ->
                    mutableUiState.value = mutableUiState.value.copy(
                        state = DownloadState.FAILED,
                        errorMessage = error.message ?: "download_start_failed"
                    )
                }
        }
    }

    fun cancel() {
        val operationId = mutableUiState.value.operationId ?: return
        viewModelScope.launch { repository.cancel(operationId) }
    }

    private fun DownloadOperationEntity.toUiState(): UpdateDownloadUiState {
        val denominator = totalBytes?.takeIf { it > 0L }
        val progress = if (denominator == null) 0f else {
            (downloadedBytes.toDouble() / denominator.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        return UpdateDownloadUiState(
            tag = tag,
            operationId = id,
            state = state,
            progress = progress,
            targetPath = targetPath,
            errorMessage = errorMessage
        )
    }
}
