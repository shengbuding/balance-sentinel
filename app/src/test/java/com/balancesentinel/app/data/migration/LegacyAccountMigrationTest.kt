package com.balancesentinel.app.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.repository.AccountLoadState
import com.balancesentinel.app.data.repository.RoomAccountRepository
import com.balancesentinel.app.data.repository.RoomAccountUiRepository
import com.balancesentinel.app.data.repository.AppResetCheckpointStore
import com.balancesentinel.app.data.repository.AppResetCoordinator
import com.balancesentinel.app.data.repository.RoomSettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class LegacyAccountMigrationTest {
    @Test
    fun rerunReusesStableUuidAndDoesNotDuplicateRoomRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val account = AccountInfo("legacy-id", "Legacy", "key-before", ProviderType.DEEPSEEK)
            val reader = object : LegacyAccountReader {
                override fun read(): CredentialReadResult = CredentialReadResult.Valid(
                    CredentialPayload(listOf(account)),
                    com.balancesentinel.app.data.credentials.CredentialGeneration.LEGACY
                )
            }
            val first = LegacyAccountMigration(database, reader, RecordingCredentialStore()).run()
            val second = LegacyAccountMigration(database, reader, RecordingCredentialStore()).run()
            assertEquals(first.mappings.single().accountId, second.mappings.single().accountId)
            assertEquals(first.mappings.single().accountId, UUID.fromString(first.mappings.single().accountId).toString())
            assertTrue(first.mappings.single().accountId != account.id)
            assertEquals(1, database.accountDao().getAllForMigration().size)
            val row = database.accountDao().get(first.mappings.single().accountId)
            assertNotNull(row)
            assertEquals("legacy-id", row?.legacyStorageId)
            val operation = database.mutationOperationDao().listRecoverable().single()
            assertEquals(MutationStage.VERIFIED, operation.stage)
            assertTrue(operation.stagedGenerationManifestJson.contains(first.mappings.single().credentialGeneration))
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyCredentialRevisionIsNormalizedToNewRoomMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val account = AccountInfo(
                id = "legacy-id",
                label = "Legacy",
                apiKey = "key-before",
                providerType = ProviderType.DEEPSEEK,
                revision = 7L
            )
            val store = RecordingCredentialStore()

            val result = LegacyAccountMigration(database, validReader(account), store).run()

            val row = requireNotNull(database.accountDao().get(result.mappings.single().accountId))
            assertEquals(0L, row.revision)
            val staged = (store.read() as CredentialReadResult.Valid).payload.accounts.single()
            assertEquals(0L, staged.revision)

            val state = RoomAccountUiRepository(RoomAccountRepository(database), store)
                .observe()
                .first { it !is AccountLoadState.Loading }
            assertTrue(state is AccountLoadState.Ready)
            assertEquals(row.id, (state as AccountLoadState.Ready).accounts.single().id)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedMigrationNeverReplaysLegacyCredentialsAfterEditOrDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val legacy = AccountInfo("legacy-id", "Legacy", "key-before", ProviderType.DEEPSEEK)
            var sourceReads = 0
            val reader = LegacyAccountReader {
                sourceReads++
                CredentialReadResult.Valid(
                    CredentialPayload(listOf(legacy)),
                    com.balancesentinel.app.data.credentials.CredentialGeneration.LEGACY
                )
            }
            val store = RecordingCredentialStore()
            val migration = LegacyAccountMigration(database, reader, store)

            migration.run()
            val edited = CredentialPayload(listOf(legacy.copy(label = "Edited", apiKey = "key-after")))
            store.write(edited)
            val writesAfterEdit = store.writeCount

            migration.run()
            assertEquals(1, sourceReads)
            assertEquals(writesAfterEdit, store.writeCount)
            assertEquals(edited, (store.read() as CredentialReadResult.Valid).payload)

            store.write(CredentialPayload(emptyList()))
            val writesAfterDelete = store.writeCount
            migration.run()
            assertEquals(1, sourceReads)
            assertEquals(writesAfterDelete, store.writeCount)
            assertEquals(CredentialPayload(emptyList()), (store.read() as CredentialReadResult.Valid).payload)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedLegacyMigrationRepairsRevisionDriftOnlyForItsGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val legacy = AccountInfo("abcdef12", "Legacy", "key-before", ProviderType.DEEPSEEK)
            val store = RecordingCredentialStore()
            val migration = LegacyAccountMigration(database, validReader(legacy), store)
            val result = migration.run()
            val row = requireNotNull(database.accountDao().get(result.mappings.single().accountId))

            store.write(CredentialPayload(listOf(legacy.copy(id = "ABCDEF12", revision = 7L))))
            migration.run()

            assertEquals(0L, (store.read() as CredentialReadResult.Valid).payload.accounts.single().revision)

            val editedRow = requireNotNull(database.accountDao().get(row.id))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE accounts SET active_credential_generation = ?, revision = 2 WHERE id = ?",
                arrayOf("generation:user-edit", editedRow.id)
            )
            store.write(CredentialPayload(listOf(legacy.copy(revision = 9L))))
            migration.run()

            assertEquals(9L, (store.read() as CredentialReadResult.Valid).payload.accounts.single().revision)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedLegacyMigrationRefreshesExistingUiSubscriptionAfterRepair() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val legacy = AccountInfo("abcdef12", "Legacy", "key-before", ProviderType.DEEPSEEK)
            val store = RecordingCredentialStore()
            val migration = LegacyAccountMigration(database, validReader(legacy), store)
            migration.run()
            val repository = RoomAccountUiRepository(RoomAccountRepository(database), store)
            store.write(CredentialPayload(listOf(legacy.copy(id = "ABCDEF12", revision = 7L))))

            val corruptSeen = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<AccountLoadState.Ready>()
            val collector: Job = launch {
                repository.observe().collect { state ->
                    if (state is AccountLoadState.Corrupt) {
                        corruptSeen.complete(Unit)
                    }
                    if (corruptSeen.isCompleted && state is AccountLoadState.Ready) {
                        ready.complete(state)
                    }
                }
            }

            withTimeout(5_000) {
                while (!corruptSeen.isCompleted) yield()
            }
            migration.run()

            val recovered = withTimeout(5_000) {
                while (!ready.isCompleted) yield()
                ready.await()
            }
            assertEquals(0L, recovered.accounts.single().revision)
            collector.cancelAndJoin()
        } finally {
            database.close()
        }
    }

    @Test
    fun persistedUppercaseLegacyIdIsReusedByCanonicalMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = UUID.randomUUID().toString()
            database.accountDao().insertCreate(
                com.balancesentinel.app.data.local.account.AccountEntity(
                    id = accountId,
                    displayOrder = 0,
                    label = "Existing",
                    providerType = ProviderType.DEEPSEEK,
                    activeCredentialGeneration = "legacy:existing",
                    state = AccountState.VERIFIED,
                    legacyStorageId = "ABCDEF12",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
            val store = RecordingCredentialStore()

            val result = LegacyAccountMigration(
                database,
                validReader(AccountInfo("abcdef12", "Legacy", "key")),
                store
            ).run()

            assertEquals(accountId, result.mappings.single().accountId)
            assertEquals("abcdef12", result.mappings.single().legacyStorageId)
            assertEquals(1, database.accountDao().getAllForMigration().size)
            val state = RoomAccountUiRepository(RoomAccountRepository(database), store)
                .observe()
                .first { it !is AccountLoadState.Loading }
            assertTrue(state is AccountLoadState.Ready)
        } finally {
            database.close()
        }
    }

    @Test
    fun persistedUppercaseLegacyOrphanIsHydratedByCanonicalMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = UUID.randomUUID().toString()
            database.accountDao().insertCreate(
                com.balancesentinel.app.data.local.account.AccountEntity(
                    id = accountId,
                    displayOrder = 0,
                    label = "Orphan",
                    providerType = ProviderType.DEEPSEEK,
                    activeCredentialGeneration = "legacy-orphan:ABCDEF12",
                    state = AccountState.PENDING,
                    legacyStorageId = "ABCDEF12",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
            val store = RecordingCredentialStore()

            val result = LegacyAccountMigration(
                database,
                validReader(AccountInfo("abcdef12", "Recovered", "key")),
                store
            ).run()

            assertEquals(accountId, result.mappings.single().accountId)
            assertEquals(AccountState.VERIFIED, database.accountDao().get(accountId)?.state)
            assertEquals(1, database.accountDao().getAllForMigration().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun canonicalRoomLegacyIdCollisionFailsBeforeCredentialStaging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            listOf("abcdef12", "ABCDEF12").forEachIndexed { index, legacyId ->
                database.accountDao().insertCreate(
                    com.balancesentinel.app.data.local.account.AccountEntity(
                        id = UUID.randomUUID().toString(),
                        displayOrder = index,
                        label = legacyId,
                        providerType = ProviderType.DEEPSEEK,
                        activeCredentialGeneration = "legacy:collision:$index",
                        state = AccountState.PENDING,
                        legacyStorageId = legacyId,
                        createdAt = 1,
                        updatedAt = 1
                    )
                )
            }
            val store = RecordingCredentialStore()

            val failure = runCatching {
                LegacyAccountMigration(
                    database,
                    validReader(AccountInfo("abcdef12", "Legacy", "key")),
                    store
                ).run()
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("Multiple Room accounts map to legacy id"))
            assertEquals(0, store.writeCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedRepairAndFullResetLeaveNoOrphanCredentials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        val checkpoint = AppResetCheckpointStore(context).also { it.complete() }
        try {
            val legacy = AccountInfo("abcdef12", "Legacy", "key", ProviderType.DEEPSEEK)
            val store = GatedCredentialStore()
            val migration = LegacyAccountMigration(database, validReader(legacy), store)
            migration.run()
            store.seed(CredentialPayload(listOf(legacy.copy(revision = 7))))
            store.gateNextWrite()

            val repair = async { migration.run() }
            store.writeStarted.await()
            val reset = async {
                AppResetCoordinator(
                    context = context,
                    database = database,
                    credentialStore = store,
                    settingsRepository = RoomSettingsRepository(database),
                    checkpointStore = checkpoint,
                    legacyCredentialClear = {},
                    externalStoreClear = {}
                ).reset()
            }
            yield()
            assertEquals(null, checkpoint.current())

            store.allowWrite.complete(Unit)
            repair.await()
            reset.await()

            assertTrue(store.read() is CredentialReadResult.Missing)
            assertTrue(database.accountDao().getAllForMigration().isEmpty())
            assertEquals(null, checkpoint.current())
        } finally {
            checkpoint.complete()
            database.close()
        }
    }

    @Test
    fun missingCredentialReadbackFailsBeforeRoomWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val reader = validReader(AccountInfo("legacy-id", "Legacy", "key"))
            val failure = runCatching {
                LegacyAccountMigration(database, reader, MissingReadbackCredentialStore).run()
            }.exceptionOrNull()
            assertTrue("Missing credential readback must fail before Room writes", failure is IllegalArgumentException)
            assertTrue(database.accountDao().getAllForMigration().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun operationManifestExistsBeforeCredentialStaging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val reader = validReader(AccountInfo("legacy-id", "Legacy", "key"))
            val store = RecordingCredentialStore(database)
            LegacyAccountMigration(database, reader, store).run()
            assertTrue(store.operationSeenBeforeWrite)
            assertTrue(store.manifestSeenBeforeWrite)
        } finally {
            database.close()
        }
    }

    @Test
    fun verificationCrashLeavesAccountHiddenAndOperationWrittenStage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val reader = validReader(AccountInfo("legacy-id", "Legacy", "key"))
            val failure = runCatching {
                LegacyAccountMigration(
                    database,
                    reader,
                    RecordingCredentialStore(),
                    onStage = { stage ->
                        if (stage == LegacyAccountMigrationStage.ROOM_WRITTEN) error("injected Room-written crash")
                    }
                ).run()
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(database.accountDao().observeVerified().first().isEmpty())
            assertTrue(database.mutationOperationDao().listRecoverable().isNotEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun everyDurableStageRecoversWithoutDuplicatesAndRetainsLegacyJson() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = Json { encodeDefaults = true }
        val raw = json.encodeToString(listOf(AccountInfo("legacy-id", "Legacy", "key")))
        val stages = listOf(
            LegacyAccountMigrationStage.DISCOVERED,
            LegacyAccountMigrationStage.VALIDATED,
            LegacyAccountMigrationStage.CREDENTIALS_STAGED,
            LegacyAccountMigrationStage.ROOM_WRITTEN,
            LegacyAccountMigrationStage.VERIFIED
        )

        stages.forEach { crashStage ->
            val prefs = context.getSharedPreferences("task3-stage-${crashStage.name}", Context.MODE_PRIVATE)
            prefs.edit().clear().putString(LegacyAccountSource.KEY_ACCOUNTS, raw).commit()
            val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
            val store = RecordingCredentialStore()
            try {
                val failure = runCatching {
                    LegacyAccountMigration(
                        database,
                        LegacyAccountSource(prefs),
                        store,
                        onStage = { stage -> if (stage == crashStage) error("crash at $stage") }
                    ).run()
                }.exceptionOrNull()
                assertNotNull("Expected injected crash at $crashStage", failure)
                assertEquals(raw, prefs.getString(LegacyAccountSource.KEY_ACCOUNTS, null))
                if (crashStage == LegacyAccountMigrationStage.ROOM_WRITTEN) {
                    assertTrue(database.accountDao().observeVerified().first().isEmpty())
                    assertTrue(database.accountDao().getAllForMigration().all { it.state == AccountState.PENDING })
                }

                val recovered = LegacyAccountMigration(
                    database,
                    LegacyAccountSource(prefs),
                    store
                ).run()
                assertEquals(LegacyAccountMigrationStage.VERIFIED, recovered.stage)
                assertEquals(1, database.accountDao().getAllForMigration().size)
                assertEquals(1, database.accountDao().observeVerified().first().size)
                assertTrue(store.read() is CredentialReadResult.Valid)
                assertEquals(raw, prefs.getString(LegacyAccountSource.KEY_ACCOUNTS, null))
            } finally {
                database.close()
                prefs.edit().clear().commit()
            }
        }
    }

    @Test
    fun corruptLegacyJsonDoesNotCreateRoomAccountsOrOverwriteSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("task3-corrupt", Context.MODE_PRIVATE)
        val raw = "{ definitely not valid account JSON"
        prefs.edit().clear().putString(LegacyAccountSource.KEY_ACCOUNTS, raw).commit()
        val database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val failure = runCatching {
                LegacyAccountMigration(database, LegacyAccountSource(prefs), RecordingCredentialStore()).run()
            }.exceptionOrNull()
            assertTrue(failure is DataCorruptionException)
            assertTrue(database.accountDao().getAllForMigration().isEmpty())
            assertEquals(raw, prefs.getString(LegacyAccountSource.KEY_ACCOUNTS, null))
        } finally {
            database.close()
            prefs.edit().clear().commit()
        }
    }

    private fun validReader(account: AccountInfo): LegacyAccountReader =
        LegacyAccountReader {
            CredentialReadResult.Valid(
                CredentialPayload(listOf(account)),
                com.balancesentinel.app.data.credentials.CredentialGeneration.LEGACY
            )
        }

    private class RecordingCredentialStore(
        private val database: WalletDatabase? = null
    ) : CredentialStore {
        var operationSeenBeforeWrite = false
        var manifestSeenBeforeWrite = false
        var writeCount = 0
            private set
        private var payload: CredentialPayload? = null

        override fun read(): CredentialReadResult = payload?.let {
            CredentialReadResult.Valid(it, com.balancesentinel.app.data.credentials.CredentialGeneration.ENCRYPTED_PREFERENCES)
        } ?: CredentialReadResult.Missing

        override suspend fun write(payload: CredentialPayload) {
            writeCount++
            this.payload = payload
            database?.let {
                val operation = it.mutationOperationDao().listRecoverable().firstOrNull()
                operationSeenBeforeWrite = operation != null
                manifestSeenBeforeWrite = operation?.stagedGenerationManifestJson?.contains("legacy:") == true
            }
        }

        override suspend fun clear() = Unit
    }

    private object MissingReadbackCredentialStore : CredentialStore {
        override fun read(): CredentialReadResult = CredentialReadResult.Missing
        override suspend fun write(payload: CredentialPayload) = Unit
        override suspend fun clear() = Unit
    }

    private class GatedCredentialStore : CredentialStore {
        @Volatile
        private var payload: CredentialPayload? = null
        private var gate = false
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()

        override fun read(): CredentialReadResult = payload?.let {
            CredentialReadResult.Valid(
                it,
                com.balancesentinel.app.data.credentials.CredentialGeneration.ENCRYPTED_PREFERENCES
            )
        } ?: CredentialReadResult.Missing

        override suspend fun write(payload: CredentialPayload) {
            if (gate) {
                gate = false
                writeStarted.complete(Unit)
                allowWrite.await()
            }
            this.payload = payload
        }

        override suspend fun clear() {
            payload = null
        }

        fun seed(payload: CredentialPayload) {
            this.payload = payload
        }

        fun gateNextWrite() {
            gate = true
        }
    }
}
