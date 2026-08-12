package com.balancesentinel.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppResetCoordinatorTest {
    @Test
    fun `startup recovery completes a reset interrupted after credential clearing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = createWalletTestDatabase()
        val checkpoint = AppResetCheckpointStore(context).also { it.complete() }
        val credentials = RecordingCredentialStore(
            CredentialPayload(listOf(AccountInfo("account", "Account", "secret")))
        )
        val settings = RoomSettingsRepository(database)
        database.accountDao().insertCreate(testAccount("account"))
        var injectFailure = true
        val interrupted = AppResetCoordinator(
            context = context,
            database = database,
            credentialStore = credentials,
            settingsRepository = settings,
            checkpointStore = checkpoint,
            legacyCredentialClear = {},
            externalStoreClear = {},
            afterStage = { stage ->
                if (stage == AppResetStage.CREDENTIALS_CLEARED && injectFailure) {
                    injectFailure = false
                    error("injected reset interruption")
                }
            }
        )

        try {
            assertTrue(runCatching { interrupted.reset() }.isFailure)
            assertEquals(AppResetStage.CREDENTIALS_CLEARED, checkpoint.current())
            assertTrue(credentials.read() is CredentialReadResult.Missing)
            assertTrue(database.accountDao().get("account") != null)

            AppResetCoordinator(
                context = context,
                database = database,
                credentialStore = credentials,
                settingsRepository = settings,
                checkpointStore = checkpoint,
                legacyCredentialClear = {},
                externalStoreClear = {}
            ).recoverIfNeeded()

            assertNull(checkpoint.current())
            assertNull(database.accountDao().get("account"))
            assertEquals(100, settings.readSnapshot().appSettings.logMaxEntries)
        } finally {
            checkpoint.complete()
            database.close()
        }
    }

    private class RecordingCredentialStore(initial: CredentialPayload) : CredentialStore {
        private var payload: CredentialPayload? = initial

        override fun read(): CredentialReadResult = payload?.let {
            CredentialReadResult.Valid(it, CredentialGeneration.ENCRYPTED_PREFERENCES)
        } ?: CredentialReadResult.Missing

        override suspend fun write(payload: CredentialPayload) {
            this.payload = payload
        }

        override suspend fun clear() {
            payload = null
        }
    }
}
