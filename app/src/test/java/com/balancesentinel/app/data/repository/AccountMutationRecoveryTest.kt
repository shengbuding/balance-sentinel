package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.execSql
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountMutationRecoveryTest {
    private lateinit var database: WalletDatabase
    private lateinit var store: RecordingCredentialStore
    private lateinit var coordinator: RoomAccountMutationCoordinator

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
        store = RecordingCredentialStore()
        coordinator = RoomAccountMutationCoordinator(database, store)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replacement persists PREPARED operation before credential staging`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        store.payload = CredentialPayload(listOf(account("legacy-a", "old-key")))
        store.beforeWrite = {
            assertNotNull(database.mutationOperationDao().listRecoverable().singleOrNull())
        }

        coordinator.save(
            "stable-a",
            AccountDraft("Updated", "new-key", ProviderType.DEEPSEEK)
        )
    }

    @Test
    fun `replacement publishes complete account while preserving stable UUID`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        store.payload = CredentialPayload(listOf(account("legacy-a", "old-key")))

        coordinator.save(
            "stable-a",
            AccountDraft("Updated", "new-key", ProviderType.DEEPSEEK)
        )

        val published = database.accountDao().get("stable-a")
        assertEquals("Updated", published?.label)
        assertEquals(AccountState.VERIFIED, published?.state)
        assertEquals("stable-a", published?.id)
        assertEquals(1L, published?.revision)
        assertEquals("new-key", store.payload?.accounts?.single()?.apiKey)
    }

    @Test
    fun `delete hides and cascades before cleanup failure and remains recoverable`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        database.usageDao().upsertSnapshot(
            UsageSnapshotEntity("snapshot-a", "stable-a", capturedAt = 1)
        )
        store.payload = CredentialPayload(listOf(account("legacy-a", "old-key")))
        var cleanupCalls = 0
        coordinator = RoomAccountMutationCoordinator(
            database,
            store,
            AccountMutationCleanup {
                cleanupCalls++
                error("injected cleanup failure")
            }
        )

        val outcome = runCatching { coordinator.delete("stable-a") }

        assertTrue("cleanup failure must not undo Room publication", outcome.isSuccess)
        assertNull(database.accountDao().get("stable-a"))
        assertEquals(0L, database.usageDao().countSnapshots())
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun `corrupt active generation fails closed without writing`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        store.readResult = CredentialReadResult.Corrupt(DataCorruptionException("corrupt"))

        val failure = runCatching {
            coordinator.save("stable-a", AccountDraft("Updated", "new-key", ProviderType.DEEPSEEK))
        }.exceptionOrNull()

        assertTrue(failure is DataCorruptionException)
        assertEquals(0, store.writeCount)
        assertEquals("old", database.accountDao().get("stable-a")?.label)
    }

    @Test
    fun `recovery resumes staged operation and marks it completed`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        val desired = CredentialPayload(listOf(account("legacy-a", "new-key")))
        store.payload = desired
        database.appMetadataDao().ensureSingleton(1)
        val operationId = "recover-operation"
        val stagedGeneration = "generation:$operationId:stable-a"
        val manifest = RecoveryManifest(
            accountId = "stable-a",
            legacyStorageId = "legacy-a",
            previousGeneration = "generation-a",
            stagedGeneration = stagedGeneration,
            expectedRevision = 0,
            create = false,
            payloadFingerprint = fingerprint(desired)
        )
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = operationId,
                operationType = MutationOperationType.ACCOUNT_REPLACE,
                stage = MutationStage.CREDENTIALS_STAGED,
                targetsJson = Json.encodeToString(listOf("stable-a")),
                stagedGenerationManifestJson = Json.encodeToString(listOf(manifest)),
                baselineRevision = 0,
                createdAt = 1,
                updatedAt = 1
            )
        )

        coordinator.recover()

        assertEquals(stagedGeneration, database.accountDao().get("stable-a")?.activeCredentialGeneration)
        assertEquals("new-key", store.payload?.accounts?.single()?.apiKey)
        assertEquals(MutationStage.COMPLETED, database.mutationOperationDao().get(operationId)?.stage)
    }

    @Test
    fun `recovery ignores unrelated operations and isolates a corrupt account operation`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        val desired = CredentialPayload(listOf(account("legacy-a", "new-key")))
        store.payload = desired
        database.appMetadataDao().ensureSingleton(1)
        insertStagedOperation("valid-account", desired)
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = "bad-account",
                operationType = MutationOperationType.ACCOUNT_REPLACE,
                stage = MutationStage.CREDENTIALS_STAGED,
                stagedGenerationManifestJson = "not-json",
                baselineRevision = 0,
                createdAt = 1,
                updatedAt = 1
            )
        )
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = "legacy-migration",
                operationType = MutationOperationType.LEGACY_ACCOUNT_MIGRATION,
                stage = MutationStage.VERIFIED,
                stagedGenerationManifestJson = "not-json",
                baselineRevision = 0,
                createdAt = 1,
                updatedAt = 1
            )
        )

        coordinator.recover()

        assertEquals(MutationStage.COMPLETED, database.mutationOperationDao().get("valid-account")?.stage)
        assertEquals(MutationStage.CREDENTIALS_STAGED, database.mutationOperationDao().get("bad-account")?.stage)
        assertEquals(MutationStage.VERIFIED, database.mutationOperationDao().get("legacy-migration")?.stage)
    }

    @Test
    fun `prepared operation after process death is recovered from staged payload`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        store.payload = CredentialPayload(listOf(account("legacy-a", "old-key")))
        store.afterWrite = { throw SimulatedProcessDeath() }

        val failure = runCatching {
            coordinator.save("stable-a", AccountDraft("Updated", "new-key", ProviderType.DEEPSEEK))
        }.exceptionOrNull()

        assertTrue(failure is SimulatedProcessDeath)
        val operationId = database.mutationOperationDao().listRecoverable().single().id
        assertEquals(MutationStage.PREPARED, database.mutationOperationDao().get(operationId)?.stage)
        store.afterWrite = null

        coordinator.recover()

        assertEquals(MutationStage.COMPLETED, database.mutationOperationDao().get(operationId)?.stage)
        assertEquals("Updated", database.accountDao().get("stable-a")?.label)
    }

    @Test
    fun `coordinator serializes interleaved mutations`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        store.payload = CredentialPayload(listOf(account("legacy-a", "old-key")))
        val firstWriteEntered = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var blockFirstWrite = true
        store.beforeWrite = {
            if (blockFirstWrite) {
                blockFirstWrite = false
                firstWriteEntered.complete(Unit)
                releaseFirstWrite.await()
            }
        }

        val first = async {
            coordinator.save("stable-a", AccountDraft("First", "first-key", ProviderType.DEEPSEEK))
        }
        firstWriteEntered.await()
        val second = async {
            coordinator.save("stable-a", AccountDraft("Second", "second-key", ProviderType.DEEPSEEK))
        }
        delay(50)
        assertEquals(1, store.writeAttempts)
        releaseFirstWrite.complete(Unit)
        first.await()
        second.await()

        assertEquals("Second", database.accountDao().get("stable-a")?.label)
        assertEquals("second-key", store.payload?.accounts?.single()?.apiKey)
    }

    @Test
    fun `published cleanup failure is retried on recovery`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        store.payload = CredentialPayload(listOf(account("legacy-a", "old-key")))
        var failCleanup = true
        val cleanup = AccountMutationCleanup {
            if (failCleanup) error("injected cleanup failure")
        }
        coordinator = RoomAccountMutationCoordinator(database, store, cleanup)

        coordinator.delete("stable-a")
        val published = database.mutationOperationDao().listRecoverable().single()
        assertEquals(MutationStage.PUBLISHED, published.stage)

        failCleanup = false
        coordinator.recover()

        assertEquals(0, database.mutationOperationDao().listRecoverable().size)
        assertEquals(MutationStage.COMPLETED, database.mutationOperationDao().get(published.id)?.stage)
    }

    @Test
    fun `rollback failure leaves a durable retry signal`() = runTest {
        seedAccount("stable-a", "legacy-a", "generation-a")
        store.payload = CredentialPayload(listOf(account("legacy-a", "old-key")))
        store.afterWrite = {
            database.execSql("UPDATE accounts SET revision = 7 WHERE id = 'stable-a'")
            store.failNextWrite = true
        }

        val failure = runCatching {
            coordinator.save("stable-a", AccountDraft("Updated", "new-key", ProviderType.DEEPSEEK))
        }.exceptionOrNull()

        assertNotNull(failure)
        val operation = database.mutationOperationDao().listRecoverable().single()
        assertEquals(MutationStage.PREPARED, operation.stage)
        assertEquals("ROLLBACK_PENDING", operation.errorCode)

        store.afterWrite = null
        coordinator.recover()

        val terminated = database.mutationOperationDao().get(operation.id)
        assertEquals(MutationStage.FAILED, terminated?.stage)
        assertNotNull(terminated?.errorCode)
        assertEquals("old-key", store.payload?.accounts?.single()?.apiKey)
        assertEquals("old", database.accountDao().get("stable-a")?.label)
    }

    private suspend fun seedAccount(id: String, legacyId: String, generation: String) {
        database.accountDao().insertCreate(
            AccountEntity(
                id = id,
                displayOrder = 0,
                label = "old",
                providerType = ProviderType.DEEPSEEK,
                activeCredentialGeneration = generation,
                revision = 0,
                state = AccountState.VERIFIED,
                legacyStorageId = legacyId,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    private suspend fun insertStagedOperation(id: String, desired: CredentialPayload) {
        val stagedGeneration = "generation:$id:stable-a"
        val manifest = RecoveryManifest(
            accountId = "stable-a",
            legacyStorageId = "legacy-a",
            previousGeneration = "generation-a",
            stagedGeneration = stagedGeneration,
            expectedRevision = 0,
            create = false,
            payloadFingerprint = fingerprint(desired)
        )
        database.mutationOperationDao().insertPrepared(
            MutationOperationEntity(
                id = id,
                operationType = MutationOperationType.ACCOUNT_REPLACE,
                stage = MutationStage.CREDENTIALS_STAGED,
                targetsJson = Json.encodeToString(listOf("stable-a")),
                stagedGenerationManifestJson = Json.encodeToString(listOf(manifest)),
                baselineRevision = 0,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    private fun account(id: String, key: String) = AccountInfo(
        id = id,
        label = "old",
        apiKey = key,
        providerType = ProviderType.DEEPSEEK
    )

    @Serializable
    private data class RecoveryManifest(
        val accountId: String,
        val legacyStorageId: String?,
        val previousGeneration: String?,
        val stagedGeneration: String,
        val expectedRevision: Long,
        val create: Boolean,
        val payloadFingerprint: String
    )

    private fun fingerprint(payload: CredentialPayload): String {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        return MessageDigest.getInstance("SHA-256")
            .digest(json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private class RecordingCredentialStore : CredentialStore {
        var payload: CredentialPayload? = null
        var readResult: CredentialReadResult? = null
        var beforeWrite: (suspend () -> Unit)? = null
        var afterWrite: (suspend () -> Unit)? = null
        var writeCount: Int = 0
        var writeAttempts: Int = 0
        var failNextWrite: Boolean = false

        override fun read(): CredentialReadResult =
            readResult ?: payload?.let {
                CredentialReadResult.Valid(
                    it,
                    com.balancesentinel.app.data.credentials.CredentialGeneration.ENCRYPTED_PREFERENCES
                )
            } ?: CredentialReadResult.Missing

        override suspend fun write(payload: CredentialPayload) {
            writeAttempts++
            if (failNextWrite) {
                failNextWrite = false
                error("injected rollback failure")
            }
            beforeWrite?.invoke()
            writeCount++
            this.payload = payload
            afterWrite?.invoke()
        }

        override suspend fun clear() {
            payload = null
        }
    }

    private class SimulatedProcessDeath : Error("simulated process death")
}
