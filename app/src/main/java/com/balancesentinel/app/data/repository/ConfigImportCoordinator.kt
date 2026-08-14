package com.balancesentinel.app.data.repository

import androidx.room.withTransaction
import com.balancesentinel.app.data.credentials.CredentialGeneration
import com.balancesentinel.app.data.credentials.CredentialPayload
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.ConfigImportRecoveryStore
import com.balancesentinel.app.data.credentials.CredentialStore
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationType
import com.balancesentinel.app.data.local.mutation.MutationStage
import com.balancesentinel.app.data.local.publication.AccountAlertSettingsWrite
import com.balancesentinel.app.data.local.publication.AccountMutation
import com.balancesentinel.app.data.local.publication.AlertRuntimeStatesWrite
import com.balancesentinel.app.data.local.publication.AppSettingsValues
import com.balancesentinel.app.data.local.publication.AppSettingsWrite
import com.balancesentinel.app.data.local.publication.MetadataPublication
import com.balancesentinel.app.data.local.publication.MutationPublication
import com.balancesentinel.app.data.local.publication.MutationPublisher
import com.balancesentinel.app.data.local.publication.NotificationSelectionsWrite
import com.balancesentinel.app.data.local.publication.SettingsPublication
import com.balancesentinel.app.data.local.publication.SnoozesWrite
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.refresh.RefreshMutationBarrier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface ConfigImportCoordinator {
    suspend fun apply(plan: BackupImportPlan)
    suspend fun recover()
}

@Serializable
private data class ConfigImportManifest(
    val desiredPayload: CredentialPayload,
    val rollbackPayload: CredentialPayload,
    val settings: ConfigSettings,
    val credentialGeneration: String
)

/** Durable publication protocol joining credentials, Room accounts, and settings. */
class RoomConfigImportCoordinator(
    private val database: WalletDatabase,
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository,
    private val recoveryStore: ConfigImportRecoveryStore? = credentialStore as? ConfigImportRecoveryStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val publishPublication: suspend (MutationPublication) -> Unit = {
        MutationPublisher(database).publish(it)
    }
) : ConfigImportCoordinator {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    override suspend fun apply(plan: BackupImportPlan) {
        RefreshMutationBarrier.withAccountMutation(null) {
            MUTEX.withLock {
                withContext(Dispatchers.IO) { applyLocked(plan) }
            }
        }
    }

    override suspend fun recover() {
        RefreshMutationBarrier.withAccountMutation(null) {
            MUTEX.withLock {
                withContext(Dispatchers.IO) {
                    migrateLegacyManifests()
                    val recoverable = database.mutationOperationDao()
                        .listRecoverableByType(MutationOperationType.CONFIG_IMPORT)
                    recoverable.forEach { operation -> runCatching { recoverLocked(operation) } }
                    val recoverableIds = database.mutationOperationDao()
                        .listRecoverableByType(MutationOperationType.CONFIG_IMPORT)
                        .mapTo(mutableSetOf()) { it.id }
                    recoveryStore?.listConfigImportManifestIds()
                        ?.filter { it !in recoverableIds }
                        ?.forEach { operationId ->
                            runCatching { recoveryStore.clearConfigImportManifest(operationId) }
                        }
                }
            }
        }
    }

    private suspend fun applyLocked(plan: BackupImportPlan) {
        val currentAccounts = readPublishedAccounts()
        val currentSettings = settingsRepository.readSnapshot()
        val currentRevision = settingsRepository.currentRevision()
        if (currentRevision != plan.baselineRevision ||
            ImportFingerprint.sha256(
                currentAccounts,
                ConfigManager.toConfigSettings(currentSettings),
                currentRevision
            ) != plan.fingerprint
        ) {
            throw StalePlanException()
        }

        val currentRows = database.accountDao().getAllForMigration()
        val rowsById = currentRows.associateBy { it.id }
        val desiredAccounts = plan.finalAccounts.map { account ->
            account.copy(revision = rowsById[account.id]?.revision?.plus(1) ?: 0L)
        }
        val rollbackPayload = readCredentialPayload(currentRows.none { it.state == AccountState.VERIFIED })
        val operationId = UUID.randomUUID().toString()
        val manifest = ConfigImportManifest(
            desiredPayload = CredentialPayload(desiredAccounts),
            rollbackPayload = rollbackPayload,
            settings = plan.settings,
            credentialGeneration = "config-import:$operationId"
        )
        val manifestJson = json.encodeToString(manifest)
        val encryptedRecoveryStore = requireNotNull(recoveryStore) {
            "Configuration import recovery requires an encrypted credential store"
        }
        encryptedRecoveryStore.writeConfigImportManifest(operationId, manifestJson)
        val operation = try {
            prepareOperation(operationId, manifest, manifestJson, currentRevision)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { encryptedRecoveryStore.clearConfigImportManifest(operationId) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }

        try {
            writeAndVerify(manifest.desiredPayload)
            markStage(operation.id, MutationStage.CREDENTIALS_STAGED)
            markStage(operation.id, MutationStage.VERIFIED)
            publish(operation, manifest, currentSettings)
            complete(operation.id)
            runCatching { encryptedRecoveryStore.clearConfigImportManifest(operation.id) }
            settingsRepository.readSnapshot()
        } catch (failure: Throwable) {
            withContext(NonCancellable) { rollbackBeforePublish(operation.id, manifest, failure) }
            throw failure
        }
    }

    private suspend fun recoverLocked(operation: MutationOperationEntity) {
        if (operation.stage == MutationStage.PUBLISHED) {
            complete(operation.id)
            runCatching { recoveryStore?.clearConfigImportManifest(operation.id) }
            settingsRepository.readSnapshot()
            return
        }
        val manifest = readManifest(operation)
        if (operation.errorCode == "ROLLBACK_PENDING") {
            recoverRollbackPending(operation, manifest)
            return
        }
        when (operation.stage) {
            MutationStage.PREPARED,
            MutationStage.CREDENTIALS_STAGED,
            MutationStage.VERIFIED -> {
                try {
                    val staged = readCredentialPayloadOrNull()
                    require(staged != null && fingerprint(staged) == fingerprint(manifest.desiredPayload)) {
                        "Configuration import credentials were not staged"
                    }
                    if (operation.stage == MutationStage.PREPARED) {
                        markStage(operation.id, MutationStage.CREDENTIALS_STAGED)
                    }
                    if (database.mutationOperationDao().get(operation.id)?.stage == MutationStage.CREDENTIALS_STAGED) {
                        markStage(operation.id, MutationStage.VERIFIED)
                    }
                    publish(operation, manifest, settingsRepository.readSnapshot())
                    complete(operation.id)
                    runCatching { requireNotNull(recoveryStore).clearConfigImportManifest(operation.id) }
                    settingsRepository.readSnapshot()
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        rollbackBeforePublish(operation.id, manifest, failure)
                    }
                    throw failure
                }
            }
            MutationStage.PUBLISHED -> Unit
            else -> Unit
        }
    }

    private suspend fun prepareOperation(
        operationId: String,
        manifest: ConfigImportManifest,
        manifestJson: String,
        baselineRevision: Long
    ): MutationOperationEntity = database.withTransaction {
        database.appMetadataDao().ensureSingleton(now())
        val metadata = requireNotNull(database.appMetadataDao().get())
        require(metadata.localRevision == baselineRevision) { "Configuration import preview is stale" }
        val operation = MutationOperationEntity(
            id = operationId,
            operationType = MutationOperationType.CONFIG_IMPORT,
            targetsJson = json.encodeToString(manifest.desiredPayload.accounts.map { it.id }),
            // Secrets and rollback material live only in the encrypted recovery store.
            stagedGenerationManifestJson = json.encodeToString(
                ConfigImportRoomManifest(
                    operationId = operationId,
                    credentialGeneration = manifest.credentialGeneration,
                    desiredAccountIds = manifest.desiredPayload.accounts.map { it.id },
                    manifestFingerprint = fingerprint(manifestJson)
                )
            ),
            manifestVersion = CONFIG_IMPORT_ROOM_MANIFEST_VERSION,
            baselineRevision = baselineRevision,
            createdAt = now(),
            updatedAt = now()
        )
        database.mutationOperationDao().insertPrepared(operation)
        operation
    }

    private suspend fun publish(
        operation: MutationOperationEntity,
        manifest: ConfigImportManifest,
        currentSettings: SettingsSnapshot
    ) {
        val currentRows = database.accountDao().getAllForMigration()
        val desiredIds = manifest.desiredPayload.accounts.mapTo(mutableSetOf()) { it.id }
        val currentById = currentRows.associateBy { it.id }
        val mutations = buildList {
            manifest.desiredPayload.accounts.forEachIndexed { index, account ->
                val current = currentById[account.id]
                if (current == null) {
                    add(
                        AccountMutation.Create(
                            id = account.id,
                            displayOrder = index,
                            label = account.label.trim(),
                            providerType = account.providerType,
                            providerConfigJson = providerConfig(account),
                            activeCredentialGeneration = manifest.credentialGeneration
                        )
                    )
                } else {
                    add(
                        AccountMutation.Update(
                            id = account.id,
                            expectedRevision = current.revision,
                            displayOrder = index,
                            label = account.label.trim(),
                            providerType = account.providerType,
                            providerConfigJson = providerConfig(account),
                            activeCredentialGeneration = manifest.credentialGeneration
                        )
                    )
                }
            }
            currentRows
                .filter { it.id !in desiredIds && !AccountEntity.isLegacyOrphan(it) }
                .forEach { row ->
                add(AccountMutation.Delete(row.id, row.revision))
            }
        }
        publishPublication(
            MutationPublication(
                operationId = operation.id,
                baselineRevision = operation.baselineRevision,
                accountMutations = mutations,
                settings = settingsPublication(manifest.settings, currentSettings, desiredIds),
                metadata = MetadataPublication.Unchanged,
                publishedAt = now()
            )
        )
    }

    private fun settingsPublication(
        settings: ConfigSettings,
        current: SettingsSnapshot,
        accountIds: Set<String>
    ): SettingsPublication {
        val sharedInterval = settings.refreshIntervalSeconds.coerceAtLeast(1)
        return SettingsPublication(
            appSettings = AppSettingsWrite.ReplaceAll(
                AppSettingsValues(
                    backgroundRefreshIntervalSeconds = if (
                        settings.backgroundRefreshEnabledForImport()
                    ) sharedInterval else null,
                    foregroundMonitoringIntervalSeconds = sharedInterval,
                    alertEnabled = settings.alertEnabled,
                    alertThreshold = settings.alertThreshold.toDouble(),
                    changeAlertEnabled = settings.changeAlertEnabled,
                    changeAlertThreshold = settings.changeAlertThreshold.toDouble(),
                    changeAlertPeriodMinutes = settings.changeAlertPeriodMinutes,
                    logMaxEntries = settings.logMaxEntries,
                    snoozeDurationMinutes = settings.snoozeDurationMinutes,
                    showTotalBalanceInNotification = settings.showTotalBalance
                )
            ),
            accountAlertSettings = AccountAlertSettingsWrite.ReplaceAll(
                settings.perCurrencyAlertSettings.filter { it.accountId in accountIds }.map {
                    AccountAlertSettingEntity(
                        it.accountId,
                        it.currency,
                        it.balanceAlertEnabled,
                        it.changeAlertEnabled
                    )
                }
            ),
            notificationSelections = NotificationSelectionsWrite.ReplaceAll(
                settings.notificationSelectedWallets.filter { it.accountId in accountIds }
                    .mapIndexed { index, value ->
                        NotificationWalletSelectionEntity(value.accountId, value.currency, index)
                    }
            ),
            alertRuntimeStates = AlertRuntimeStatesWrite.ReplaceAll(
                current.alertRuntimeStates.filter { it.accountId in accountIds }
            ),
            snoozes = SnoozesWrite.ReplaceAll(current.snoozes.filter { it.accountId in accountIds })
        )
    }

    private suspend fun readPublishedAccounts(): List<AccountInfo> {
        val rows = database.accountDao().getAllForMigration().filter { it.state == AccountState.VERIFIED }
        val payload = readCredentialPayload(rows.isEmpty())
        val used = mutableSetOf<Int>()
        return rows.sortedBy { it.displayOrder }.map { row ->
            val index = payload.accounts.indexOfFirst { it.id == row.id || it.id == row.legacyStorageId }
            require(index >= 0 && used.add(index)) { "Credential payload does not match account ${row.id}" }
            payload.accounts[index].copy(
                id = row.id,
                label = row.label,
                providerType = row.providerType,
                revision = row.revision
            )
        }.also {
            require(used.size == payload.accounts.size) { "Credential payload contains unpublished accounts" }
        }
    }

    private fun readCredentialPayload(allowMissing: Boolean): CredentialPayload =
        when (val read = credentialStore.read()) {
            CredentialReadResult.Missing -> {
                require(allowMissing) { "Account metadata has no credential payload" }
                CredentialPayload(emptyList())
            }
            is CredentialReadResult.Corrupt -> throw read.exception
            is CredentialReadResult.Valid -> read.payload.also { it.validate() }
        }

    private fun readCredentialPayloadOrNull(): CredentialPayload? = when (val read = credentialStore.read()) {
        CredentialReadResult.Missing -> null
        is CredentialReadResult.Corrupt -> null
        is CredentialReadResult.Valid -> read.payload
    }

    private suspend fun writeAndVerify(payload: CredentialPayload) {
        credentialStore.write(payload)
        val readback = credentialStore.read()
        require(readback is CredentialReadResult.Valid)
        require(readback.generation == CredentialGeneration.ENCRYPTED_PREFERENCES)
        require(fingerprint(readback.payload) == fingerprint(payload))
        readback.payload.validate()
    }

    private suspend fun rollbackBeforePublish(
        operationId: String,
        manifest: ConfigImportManifest,
        failure: Throwable
    ) {
        val current = database.mutationOperationDao().get(operationId) ?: return
        if (current.stage == MutationStage.PUBLISHED || current.stage == MutationStage.COMPLETED) {
            if (current.stage == MutationStage.PUBLISHED) {
                runCatching { complete(operationId) }.onFailure(failure::addSuppressed)
            }
            runCatching { recoveryStore?.clearConfigImportManifest(operationId) }
                .onFailure(failure::addSuppressed)
            return
        }
        val rollbackSucceeded = runCatching {
            writeAndVerify(manifest.rollbackPayload)
        }.onFailure { failure.addSuppressed(it) }.isSuccess
        if (rollbackSucceeded) {
            fail(operationId, failure::class.simpleName ?: "CONFIG_IMPORT_FAILED", failure.message)
            runCatching { recoveryStore?.clearConfigImportManifest(operationId) }
                .onFailure(failure::addSuppressed)
        } else {
            val pending = database.mutationOperationDao().get(operationId) ?: return
            database.mutationOperationDao().updateStage(
                id = operationId,
                stage = MutationStage.PREPARED,
                batchCursor = pending.batchCursor,
                errorCode = "ROLLBACK_PENDING",
                errorMessage = "Credential rollback must be retried",
                updatedAt = now()
            )
        }
    }

    private suspend fun recoverRollbackPending(
        operation: MutationOperationEntity,
        manifest: ConfigImportManifest
    ) {
        try {
            writeAndVerify(manifest.rollbackPayload)
            require(
                database.mutationOperationDao().updateStage(
                    id = operation.id,
                    stage = MutationStage.FAILED,
                    batchCursor = operation.batchCursor,
                    errorCode = "ROLLBACK_RESTORED",
                    errorMessage = "Credential rollback completed after retry",
                    updatedAt = now()
                ) == 1
            )
            runCatching { recoveryStore?.clearConfigImportManifest(operation.id) }
        } catch (_: Throwable) {
            // Keep PREPARED + ROLLBACK_PENDING until the old credentials can be restored.
        }
    }

    private suspend fun markStage(id: String, stage: MutationStage) {
        val operation = requireNotNull(database.mutationOperationDao().get(id))
        if (operation.stage.ordinal < stage.ordinal) {
            require(
                database.mutationOperationDao().updateStage(
                    id, stage, operation.batchCursor, null, null, now()
                ) == 1
            )
        }
    }

    private suspend fun complete(id: String) {
        val operation = requireNotNull(database.mutationOperationDao().get(id))
        if (operation.stage == MutationStage.PUBLISHED) {
            require(database.mutationOperationDao().markCompleted(id, now()) == 1)
        }
    }

    private suspend fun fail(id: String, code: String, message: String?) {
        val operation = requireNotNull(database.mutationOperationDao().get(id))
        database.mutationOperationDao().updateStage(
            id,
            MutationStage.FAILED,
            operation.batchCursor,
            code.take(80),
            message?.take(240),
            now()
        )
    }

    private fun readManifest(operation: MutationOperationEntity): ConfigImportManifest {
        val stored = recoveryStore?.readConfigImportManifest(operation.id)
        if (stored != null) {
            val roomManifest = json.decodeFromString<ConfigImportRoomManifest>(
                operation.stagedGenerationManifestJson
            )
            require(fingerprint(stored) == roomManifest.manifestFingerprint) {
                "Configuration import recovery manifest is corrupt"
            }
            return json.decodeFromString(stored)
        }
        error("Configuration import recovery manifest is missing")
    }

    private suspend fun migrateLegacyManifests() {
        val encryptedRecoveryStore = requireNotNull(recoveryStore) {
            "Configuration import recovery requires an encrypted credential store"
        }
        database.mutationOperationDao().listByType(MutationOperationType.CONFIG_IMPORT)
            .forEach { operation ->
                val legacy = runCatching {
                    json.decodeFromString<ConfigImportManifest>(operation.stagedGenerationManifestJson)
                }.getOrNull() ?: return@forEach
                val legacyJson = json.encodeToString(legacy)
                if (operation.stage != MutationStage.COMPLETED && operation.stage != MutationStage.FAILED) {
                    encryptedRecoveryStore.writeConfigImportManifest(operation.id, legacyJson)
                }
                val sanitized = roomManifestJson(operation.id, legacy, legacyJson)
                require(
                    database.mutationOperationDao().replaceStagedManifestIfCurrent(
                        id = operation.id,
                        expectedManifestJson = operation.stagedGenerationManifestJson,
                        newManifestJson = sanitized,
                        newManifestVersion = CONFIG_IMPORT_ROOM_MANIFEST_VERSION,
                        updatedAt = now()
                    ) == 1
                )
            }
    }

    private fun roomManifestJson(
        operationId: String,
        manifest: ConfigImportManifest,
        manifestJson: String
    ): String = json.encodeToString(
        ConfigImportRoomManifest(
            operationId = operationId,
            credentialGeneration = manifest.credentialGeneration,
            desiredAccountIds = manifest.desiredPayload.accounts.map { it.id },
            manifestFingerprint = fingerprint(manifestJson)
        )
    )

    private fun fingerprint(payload: CredentialPayload): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun fingerprint(manifest: String): String = MessageDigest.getInstance("SHA-256")
        .digest(manifest.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun providerConfig(account: AccountInfo): String = buildJsonObject {
        account.extraSettings.forEach { (key, value) -> put(key, value) }
        account.usageScript?.let { put("usageScript", it) }
        put("usageScriptEnabled", account.usageScriptEnabled)
        put("authorizedScriptOrigins", account.authorizedScriptOrigins.sorted().joinToString(","))
    }.toString()

    private companion object {
        val MUTEX = Mutex()
        const val CONFIG_IMPORT_ROOM_MANIFEST_VERSION = 2
    }
}

@Serializable
private data class ConfigImportRoomManifest(
    val operationId: String,
    val credentialGeneration: String,
    val desiredAccountIds: List<String>,
    val manifestFingerprint: String
)
