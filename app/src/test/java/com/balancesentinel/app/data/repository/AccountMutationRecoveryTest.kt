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
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import com.balancesentinel.app.data.model.AccountDraft
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
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
        var writeCount: Int = 0

        override fun read(): CredentialReadResult =
            readResult ?: payload?.let {
                CredentialReadResult.Valid(
                    it,
                    com.balancesentinel.app.data.credentials.CredentialGeneration.ENCRYPTED_PREFERENCES
                )
            } ?: CredentialReadResult.Missing

        override suspend fun write(payload: CredentialPayload) {
            beforeWrite?.invoke()
            writeCount++
            this.payload = payload
        }

        override suspend fun clear() {
            payload = null
        }
    }
}
