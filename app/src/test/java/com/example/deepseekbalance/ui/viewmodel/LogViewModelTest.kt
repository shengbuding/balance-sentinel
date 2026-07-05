package com.example.deepseekbalance.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.deepseekbalance.data.model.RefreshLogEntry
import com.example.deepseekbalance.data.model.RefreshLogType
import com.example.deepseekbalance.data.repository.RefreshLogStore
import com.example.deepseekbalance.data.repository.WidgetPrefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogViewModelTest {

    private lateinit var application: Application
    private lateinit var viewModel: LogViewModel
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        application = context as Application
        RefreshLogStore.clear(context)
        val prefs = WidgetPrefs(context)
        prefs.logMaxEntries = 100
        viewModel = LogViewModel(application)
    }

    @After
    fun tearDown() {
        RefreshLogStore.clear(context)
    }

    @Test
    fun `initial state has empty logs`() {
        val state = viewModel.uiState.value
        assertTrue(state.refreshLogs.isEmpty())
        assertEquals(0, state.missedCount)
    }

    @Test
    fun `loadLogs populates state from store`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000,
            totalBalance = "100", currency = "CNY"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 2, type = RefreshLogType.AUTO, timestamp = 2000,
            totalBalance = "90", currency = "CNY"
        ))

        viewModel.loadLogs()

        val state = viewModel.uiState.value
        assertEquals(2, state.refreshLogs.size)
    }

    @Test
    fun `missedCount counts MISSED entries`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "ok"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 2, type = RefreshLogType.MISSED, timestamp = 2000, message = "missed1"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 3, type = RefreshLogType.MISSED, timestamp = 3000, message = "missed2"
        ))

        viewModel.loadLogs()

        assertEquals(2, viewModel.uiState.value.missedCount)
    }

    @Test
    fun `selectLogType filters logs`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "m"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 2, type = RefreshLogType.AUTO, timestamp = 2000, message = "a"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 3, type = RefreshLogType.MANUAL, timestamp = 3000, message = "m2"
        ))

        viewModel.loadLogs()
        viewModel.selectLogType(RefreshLogType.AUTO)

        val state = viewModel.uiState.value
        assertEquals(RefreshLogType.AUTO, state.selectedLogType)
        assertEquals(1, state.refreshLogs.size)
        assertEquals(RefreshLogType.AUTO, state.refreshLogs[0].type)
    }

    @Test
    fun `selectLogType with null shows all logs`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "m"
        ))
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 2, type = RefreshLogType.AUTO, timestamp = 2000, message = "a"
        ))

        viewModel.loadLogs()
        viewModel.selectLogType(RefreshLogType.MANUAL)
        assertEquals(1, viewModel.uiState.value.refreshLogs.size)

        viewModel.selectLogType(null)
        assertEquals(2, viewModel.uiState.value.refreshLogs.size)
        assertNull(viewModel.uiState.value.selectedLogType)
    }

    @Test
    fun `clearLogs removes all entries from store and state`() {
        RefreshLogStore.addEntry(context, RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "m"
        ))
        viewModel.loadLogs()
        assertEquals(1, viewModel.uiState.value.refreshLogs.size)

        viewModel.clearLogs()

        val state = viewModel.uiState.value
        assertTrue(state.refreshLogs.isEmpty())
        assertEquals(0, state.missedCount)
        assertTrue(RefreshLogStore.getEntries(context).isEmpty())
    }

    @Test
    fun `setLogMax updates setting`() {
        viewModel.setLogMax(50)

        assertEquals(50, viewModel.uiState.value.logMaxEntries)
        // Verify it persists to WidgetPrefs
        val prefs = WidgetPrefs(context)
        assertEquals(50, prefs.logMaxEntries)
    }

    @Test
    fun `clearExportResult clears export message`() {
        viewModel.clearExportResult()
        assertNull(viewModel.uiState.value.exportResult)
    }

    @Test
    fun `filter on empty logs does not crash`() {
        viewModel.loadLogs()
        viewModel.selectLogType(RefreshLogType.MISSED)

        val state = viewModel.uiState.value
        assertTrue(state.refreshLogs.isEmpty())
        assertEquals(0, state.missedCount)
    }
}
