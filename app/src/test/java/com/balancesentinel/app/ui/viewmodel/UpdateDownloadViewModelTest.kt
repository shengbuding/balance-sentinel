package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import com.balancesentinel.app.data.local.update.DownloadState
import com.balancesentinel.app.data.model.GitHubRelease
import com.balancesentinel.app.data.update.ApkDownloadRepositoryContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateDownloadViewModelTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `binding restores persisted running state after recreation`() = runTest {
        val repository = FakeDownloadRepository(operation("new", DownloadState.RUNNING, 25, 100))
        val viewModel = UpdateDownloadViewModel(application, repository)

        viewModel.bind("v2")
        advanceUntilIdle()

        assertEquals(DownloadState.RUNNING, viewModel.uiState.value.state)
        assertEquals(0.25f, viewModel.uiState.value.progress)
    }

    @Test
    fun `older operation completion cannot replace newer operation state`() = runTest {
        val repository = FakeDownloadRepository(operation("new", DownloadState.RUNNING, 10, 100))
        val viewModel = UpdateDownloadViewModel(application, repository)
        viewModel.bind("v2")
        advanceUntilIdle()

        repository.latest.value = operation("old", DownloadState.COMPLETED, 100, 100, createdAt = 1)
        advanceUntilIdle()

        assertEquals("new", viewModel.uiState.value.operationId)
        assertEquals(DownloadState.RUNNING, viewModel.uiState.value.state)
    }

    private fun operation(
        id: String,
        state: DownloadState,
        downloaded: Long,
        total: Long,
        createdAt: Long = if (id == "new") 2 else 1
    ) = DownloadOperationEntity(
        id = id,
        ownerId = "owner-$id",
        tag = "v2",
        sourceUrl = "https://example.invalid/app.apk",
        temporaryPath = "/tmp/$id.part",
        targetPath = "/tmp/app.apk",
        state = state,
        downloadedBytes = downloaded,
        totalBytes = total,
        activeTag = if (state.isTerminal) null else "v2",
        activeTargetPath = if (state.isTerminal) null else "/tmp/app.apk",
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private class FakeDownloadRepository(initial: DownloadOperationEntity?) : ApkDownloadRepositoryContract {
        val latest = MutableStateFlow(initial)
        override fun observe(tag: String): Flow<DownloadOperationEntity?> = latest
        override suspend fun start(release: GitHubRelease): DownloadOperationEntity = requireNotNull(latest.value)
        override suspend fun cancel(operationId: String) = Unit
    }
}
