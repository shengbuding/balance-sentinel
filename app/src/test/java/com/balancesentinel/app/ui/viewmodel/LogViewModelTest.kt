package com.balancesentinel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.repository.RoomEventLogRepository
import com.balancesentinel.app.data.repository.SettingsRepositoryProvider
import com.balancesentinel.app.data.repository.WidgetPrefs
import com.balancesentinel.app.testing.MutableSettingsRepository
import kotlinx.coroutines.runBlocking
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
    private lateinit var database: WalletDatabase
    private lateinit var settingsRepository: MutableSettingsRepository
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        CrashLogger.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        application = context as Application
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        WalletDatabaseProvider.installForTests(database)
        settingsRepository = MutableSettingsRepository()
        SettingsRepositoryProvider.factory = { settingsRepository }
        runBlocking { database.accountDao().insertCreate(logRoomAccount()) }
        viewModel = LogViewModel(application)
    }

    @After
    fun tearDown() {
        SettingsRepositoryProvider.resetForTests()
        WalletDatabaseProvider.clearForTests()
        CrashLogger.clear(application)
        CrashLogger.resetForTests()
        val restored = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        assertSame(originalHandler, restored)
    }

    @Test
    fun `initial state has empty logs`() {
        val state = viewModel.uiState.value
        assertTrue(state.refreshLogs.isEmpty())
        assertEquals(0, state.missedCount)
    }

    @Test
    fun `loadLogs populates state from store`() {
        addRoomLog(RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000,
            totalBalance = "100", currency = "CNY"
        ))
        addRoomLog(RefreshLogEntry(
            id = 2, type = RefreshLogType.AUTO, timestamp = 2000,
            totalBalance = "90", currency = "CNY"
        ))

        viewModel.loadLogs()

        val state = viewModel.uiState.value
        assertEquals(2, state.refreshLogs.size)
    }

    @Test
    fun `missedCount counts MISSED entries`() {
        addRoomLog(RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "ok"
        ))
        addRoomLog(RefreshLogEntry(
            id = 2, type = RefreshLogType.MISSED, timestamp = 2000, message = "missed1"
        ))
        addRoomLog(RefreshLogEntry(
            id = 3, type = RefreshLogType.MISSED, timestamp = 3000, message = "missed2"
        ))

        viewModel.loadLogs()

        assertEquals(2, viewModel.uiState.value.missedCount)
    }

    @Test
    fun `selectLogType filters logs`() {
        addRoomLog(RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "m"
        ))
        addRoomLog(RefreshLogEntry(
            id = 2, type = RefreshLogType.AUTO, timestamp = 2000, message = "a"
        ))
        addRoomLog(RefreshLogEntry(
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
        addRoomLog(RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "m"
        ))
        addRoomLog(RefreshLogEntry(
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
        addRoomLog(RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "m"
        ))
        viewModel.loadLogs()
        assertEquals(1, viewModel.uiState.value.refreshLogs.size)

        viewModel.clearLogs()

        val state = viewModel.uiState.value
        assertTrue(state.refreshLogs.isEmpty())
        assertEquals(0, state.missedCount)
        assertEquals(0, runBlocking { database.eventLogDao().countLogs() })
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
    fun `loadLogs honors the supported one thousand entry limit`() {
        viewModel.setLogMax(1_000)
        addRoomLogs((1L..101L).map { id ->
            RefreshLogEntry(id = id, type = RefreshLogType.AUTO, timestamp = id)
        })

        viewModel.loadLogs()

        assertEquals(101, viewModel.uiState.value.refreshLogs.size)
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

    // ═══════════════════════════════════════════════════════════
    // Crash log integration
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loadCrashLogs populates state from CrashLogger`() {
        CrashLogger.install(application)
        CrashLogger.logNonFatal("TestTag", RuntimeException("test-error-for-log-vm"))
        viewModel.loadCrashLogs()

        val state = viewModel.uiState.value
        assertTrue(state.crashLogs.isNotEmpty())
        assertTrue(state.crashLogs.any { it.fullStack.contains("test-error-for-log-vm") })
    }

    @Test
    fun `clearCrashes empties crash logs in state`() {
        CrashLogger.install(application)
        CrashLogger.logNonFatal("TestTag", RuntimeException("to-be-cleared"))
        viewModel.loadCrashLogs()
        assertTrue(viewModel.uiState.value.crashLogs.isNotEmpty())

        viewModel.clearCrashes()

        val state = viewModel.uiState.value
        assertTrue(state.crashLogs.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // Export
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `exportLogs writes result to state`() {
        addRoomLog(RefreshLogEntry(
            id = 1, type = RefreshLogType.MANUAL, timestamp = 1000, message = "test"
        ))
        viewModel.loadLogs()
        viewModel.exportLogs()

        val state = viewModel.uiState.value
        // exportResult is set (path or error string), should not be null after export call
        assertNotNull(state.exportResult)
    }

    @Test
    fun `exportLogs handles empty store gracefully`() {
        viewModel.loadLogs()
        viewModel.exportLogs()

        val state = viewModel.uiState.value
        // Either succeeds with null path or fails gracefully
        assertNotNull(state.exportResult)
    }

    @Test
    fun `init sets logMaxEntries from settings repository`() {
        runBlocking {
            settingsRepository.updateSnapshot { current ->
                current.copy(appSettings = current.appSettings.copy(logMaxEntries = 200))
            }
        }
        val vm = LogViewModel(application)
        assertEquals(200, vm.uiState.value.logMaxEntries)
    }

    private fun addRoomLog(entry: RefreshLogEntry) = runBlocking {
        RoomEventLogRepository(database).append(listOf(entry))
    }

    private fun addRoomLogs(entries: List<RefreshLogEntry>) = runBlocking {
        RoomEventLogRepository(database).append(entries)
    }

    private fun logRoomAccount() = AccountEntity(
        id = LOG_ACCOUNT_ID,
        displayOrder = 0,
        label = "Log test account",
        providerType = ProviderType.DEEPSEEK,
        activeCredentialGeneration = "test",
        state = AccountState.VERIFIED,
        createdAt = 1L,
        updatedAt = 1L
    )

    private companion object {
        const val LOG_ACCOUNT_ID = "8d4b6f8a-2b3f-4f2b-9f6d-7f8b4a1c2d30"
    }
}
