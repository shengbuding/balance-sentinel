package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.balancesentinel.app.data.io.HistoryJsonLimits
import com.balancesentinel.app.data.io.HistoryUriStorage
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.io.HistoryExportHeader
import com.balancesentinel.app.data.io.HistoryExportSource
import com.balancesentinel.app.data.io.HistoryJsonConsumer
import com.balancesentinel.app.data.io.HistoryJsonReader
import com.balancesentinel.app.data.io.HistoryJsonWriter
import com.balancesentinel.app.data.io.HistoryLogCursor
import com.balancesentinel.app.data.io.HistoryLogPage
import com.balancesentinel.app.data.local.history.BalanceRecordSource
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.io.File
import java.time.LocalDate

/**
 * 历史数据导出（日摘要 + 原始记录）。
 * 跟随 ConfigManager 的 SAF + 版本化 JSON 模式。
 * 与配置导出分离——这是独立可选功能。
 */
@Serializable
data class DataExport(
    val version: Int = 1,
    val exportedAt: String,
    val appVersion: String,
    val dailySummaries: List<DailySummary>,
    val rawRecords: List<RawRecord>,
    val usageSnapshots: List<com.balancesentinel.app.data.model.UsageSnapshot> = emptyList(),
    val refreshLogs: List<com.balancesentinel.app.data.model.RefreshLogEntry> = emptyList()
)

object DataExporter {

    private class SnapshotChangedException : IllegalStateException("History changed while it was being exported")
    private const val MAX_SNAPSHOT_RETRIES = 3

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 构建导出 JSON 字符串。
     */
    fun buildExport(context: Context): String {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        val data = kotlinx.coroutines.runBlocking {
            buildExportData(
                context,
                RoomHistoryRepository(WalletDatabaseProvider.get(context)),
                RoomUsageRepository(WalletDatabaseProvider.get(context)),
                RoomEventLogRepository(WalletDatabaseProvider.get(context))
            )
        }
        return json.encodeToString(data)
    }

    private suspend fun buildExportData(
        context: Context,
        historyRepository: HistoryRepository,
        usageRepository: UsageRepository,
        eventLogRepository: EventLogRepository
    ): DataExport {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
        return DataExport(
            version = 1,
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            appVersion = appVersion,
            dailySummaries = historyRepository.summaries(),
            rawRecords = readAllHistory(historyRepository),
            usageSnapshots = readAllUsage(usageRepository, historyRepository),
            refreshLogs = eventLogRepository.newest(1000)
        )
    }

    suspend fun buildExport(context: Context, historyRepository: HistoryRepository): String {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        val database = WalletDatabaseProvider.get(context)
        val data = buildExportData(
            context,
            historyRepository,
            RoomUsageRepository(database),
            RoomEventLogRepository(database)
        )
        return json.encodeToString(data)
    }

    /**
     * 通过 SAF URI 写入导出文件。
     */
    suspend fun exportToUri(context: Context, uri: Uri): Boolean {
        val limits = HistoryJsonLimits()
        val database = WalletDatabaseProvider.get(context)
        return exportToUri(
            context,
            uri,
            roomHistoryExportSource(database, limits),
            ContentResolverHistoryUriStorage(context),
            limits
        )
    }

    suspend fun exportToUri(
        context: Context,
        uri: Uri,
        source: HistoryExportSource,
        storage: HistoryUriStorage = ContentResolverHistoryUriStorage(context),
        limits: HistoryJsonLimits = HistoryJsonLimits()
    ): Boolean {
        val staged = File.createTempFile("history-export-", ".json", context.cacheDir)
        try {
            var attempt = 0
            while (true) {
                try {
                    source.withConsistentSnapshot {
                        staged.outputStream().buffered().use { output ->
                            HistoryJsonWriter(limits).write(
                                output,
                                HistoryExportHeader(
                                    version = 1,
                                    exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                                    appVersion = appVersion(context)
                                ),
                                source
                            )
                        }
                    }
                    break
                } catch (changed: SnapshotChangedException) {
                    if (++attempt >= MAX_SNAPSHOT_RETRIES) throw changed
                }
            }
            staged.inputStream().buffered().use { input ->
                HistoryJsonReader(limits).read(input, ValidatingHistoryConsumer)
            }

            storage.replaceAtomically(uri, staged)?.let { return it }
            if (storage.containsExistingData(uri) != false) return false

            try {
                val output = storage.openOutput(uri) ?: error("Unable to open history export destination")
                output.use { destination ->
                    staged.inputStream().buffered().use { input -> copy(input, destination, cancellable = true) }
                    destination.flush()
                }
                return true
            } catch (cancelled: CancellationException) {
                deleteFailedNewDestination(uri, storage)
                throw cancelled
            } catch (_: Exception) {
                deleteFailedNewDestination(uri, storage)
                return false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            staged.delete()
        }
    }

    /**
     * 是否有历史数据可导出。
     */
    fun hasData(context: Context): Boolean {
        val database = WalletDatabaseProvider.get(context)
        return kotlinx.coroutines.runBlocking {
            database.historyDao().countSummaries() > 0L ||
                database.historyDao().countRecords() > 0L ||
                database.usageDao().countSnapshots() > 0L ||
                database.eventLogDao().countLogs() > 0L
        }
    }

    // ── 导入 ──

    /**
     * 导入结果详情，用于向用户展示各数据类型的解析和合并情况。
     */
    data class ImportResult(
        val summariesInFile: Int,
        val summariesImported: Int,
        val recordsInFile: Int,
        val recordsImported: Int,
        val snapshotsInFile: Int,
        val snapshotsImported: Int,
        val logsInFile: Int,
        val logsImported: Int
    )

    /**
     * 从 SAF URI 读取并解析历史数据导出文件。
     * @return 解析后的 [DataExport]，失败返回 null。
     */
    fun importFromUri(context: Context, uri: Uri): DataExport? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            json.decodeFromString<DataExport>(content)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将导入的历史数据合并到当前存储。
     */
    fun applyImport(context: Context, data: DataExport): ImportResult =
        DataMutationCoordinator.withMutation {
            kotlinx.coroutines.runBlocking {
                val database = WalletDatabaseProvider.get(context)
                database.withTransaction {
                    val zoneId = ZoneId.systemDefault()
                    val history = RoomHistoryRepository(database)
                    val usage = RoomUsageRepository(database)
                    val events = RoomEventLogRepository(database)
                    val accountIds = buildSet {
                        addAll(data.dailySummaries.map { it.accountId })
                        addAll(data.rawRecords.map { it.accountId })
                        addAll(data.usageSnapshots.map { it.accountId })
                    }
                    val knownAccountIds = accountIds.filterTo(mutableSetOf()) { accountId ->
                        accountId.isNotBlank() && database.accountDao().get(accountId) != null
                    }
                    // Continuity placeholders are only read-model shadows. They
                    // must not make a real imported summary look like a duplicate
                    // (or suppress raw rows that arrive with that summary).
                    val existingSummaries = database.historyDao()
                        .publishedSummaryKeys(CONTINUITY_SUMMARY_IDENTITY)
                        .mapTo(mutableSetOf()) { key ->
                            HistoryKey(
                                date = key.date,
                                accountId = key.accountId,
                                currency = key.currency.uppercase(Locale.ROOT)
                            )
                        }
                    val preImportSummaryKeys = existingSummaries.toSet()
                    val newSummaries = data.dailySummaries.filter { summary ->
                        summary.accountId in knownAccountIds && existingSummaries.add(summary.historyKey())
                    }
                    val existingRecords = readAllHistory(history).toMutableSet()
                    val existingRecordKeys = existingRecords.mapTo(mutableSetOf()) { it.historyKey(zoneId) }
                    val summaryOnlyKeys = preImportSummaryKeys.filterTo(mutableSetOf()) {
                        it !in existingRecordKeys
                    }
                    val newRecords = data.rawRecords.filter { record ->
                        record.accountId in knownAccountIds &&
                            record.historyKey(zoneId) !in summaryOnlyKeys &&
                            existingRecords.add(record)
                    }
                    val existingLogIds = database.eventLogDao().allIds().toMutableSet()
                    val newLogs = data.refreshLogs.filter { existingLogIds.add(it.id) }
                    val newSnapshots = data.usageSnapshots.filter { snapshot ->
                        snapshot.accountId in knownAccountIds &&
                            usage.count(snapshot.accountId, snapshot.timestamp, snapshot.timestamp + 1) == 0L
                    }

                    history.upsertSummaries(newSummaries)
                    history.insert(newRecords, com.balancesentinel.app.data.local.history.BalanceRecordSource.IMPORT)
                    events.append(newLogs)
                    newSnapshots.forEach { usage.upsert(it, "import") }
                    ImportResult(data.dailySummaries.size, newSummaries.size, data.rawRecords.size, newRecords.size,
                        data.usageSnapshots.size, newSnapshots.size, data.refreshLogs.size, newLogs.size)
                }
            }
        }

    /**
     * 便捷方法：直接从 URI 导入并合并。
     * @return [ImportResult]，失败返回 null。
     */
    suspend fun importAndApply(
        context: Context,
        uri: Uri,
        database: WalletDatabase = WalletDatabaseProvider.get(context),
        storage: HistoryUriStorage = ContentResolverHistoryUriStorage(context),
        limits: HistoryJsonLimits = HistoryJsonLimits(),
        consumer: HistoryJsonConsumer = roomHistoryImportConsumer(database)
    ): ImportResult? {
        val staged = File.createTempFile("history-import-", ".json", context.cacheDir)
        return try {
            val input = storage.openInput(uri) ?: return null
            input.use { stream ->
                staged.outputStream().buffered().use { output ->
                    copy(stream, output, cancellable = true, maximumBytes = limits.maxFileBytes)
                }
            }
            staged.inputStream().buffered().use { input ->
                HistoryJsonReader(limits).read(input, ValidatingHistoryConsumer)
            }
            staged.inputStream().buffered().use { stream ->
                database.withTransaction {
                    val result = HistoryJsonReader(limits).read(stream, consumer)
                    ImportResult(
                        result.summariesInFile,
                        result.summariesImported,
                        result.recordsInFile,
                        result.recordsImported,
                        result.snapshotsInFile,
                        result.snapshotsImported,
                        result.logsInFile,
                        result.logsImported
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            staged.delete()
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    internal fun roomHistoryExportSource(
        database: WalletDatabase,
        limits: HistoryJsonLimits = HistoryJsonLimits(),
        pageObserver: (section: String, limit: Int) -> Unit = { _, _ -> }
    ): HistoryExportSource = RoomHistoryExportSource(database, limits, pageObserver)

    internal fun roomHistoryImportConsumer(database: WalletDatabase): HistoryJsonConsumer =
        RoomHistoryImportConsumer(database)

    private class RoomHistoryExportSource(
        private val database: WalletDatabase,
        private val limits: HistoryJsonLimits,
        private val pageObserver: (section: String, limit: Int) -> Unit
    ) : HistoryExportSource {
        private val historyDao = database.historyDao()
        private val usageDao = database.usageDao()
        private val eventLogDao = database.eventLogDao()

        private var snapshot: RoomExportSnapshot? = null

        override suspend fun <T> withConsistentSnapshot(block: suspend () -> T): T {
            val captured = database.withTransaction { captureSnapshot() }
            snapshot = captured
            return try {
                val result = block()
                if (!isSnapshotStable(captured)) throw SnapshotChangedException()
                result
            } finally {
                snapshot = null
            }
        }

        override suspend fun dailySummaryPage(offset: Int, limit: Int): List<DailySummary> {
            pageObserver("dailySummaries", limit)
            snapshot?.let { return it.summaries.drop(offset).take(limit) }
            return historyDao.exportSummaryPage(offset, limit).map { entity ->
                DailySummary(
                    entity.accountId, entity.date, entity.currency,
                    entity.openBalance.toFloat(), entity.closeBalance.toFloat(),
                    entity.consumedBalance.toFloat(), entity.toppedUpBalance.toFloat(),
                    entity.grantedBalance.toFloat(), entity.averageBalance.toFloat(),
                    entity.sampleCount, entity.toppedUpBalanceClose.toFloat(),
                    entity.grantedBalanceClose.toFloat(), entity.generatedAt
                )
            }
        }

        override suspend fun rawRecordPage(after: HistoryCursor?, limit: Int): HistoryPage {
            pageObserver("rawRecords", limit)
            val rows = snapshot?.let {
                historyDao.exportPageUpTo(it.rawMaxId, after?.recordedAt, after?.id, limit)
            } ?: historyDao.keysetPageAll(
                Long.MIN_VALUE, Long.MAX_VALUE, after?.recordedAt, after?.id, limit
            )
            val records = rows.map { entity ->
                HistoryRecord(
                    entity.id,
                    RawRecord(
                        entity.accountId,
                        entity.recordedAt,
                        entity.currency,
                        entity.totalBalance.toFloat(),
                        entity.grantedBalance.toFloat(),
                        entity.toppedUpBalance.toFloat()
                    )
                )
            }
            return HistoryPage(
                records,
                records.lastOrNull()?.let { HistoryCursor(it.value.timestamp, it.id) }
            )
        }

        override suspend fun usageSnapshotPage(offset: Int, limit: Int): List<UsageSnapshot> {
            pageObserver("usageSnapshots", limit)
            val entities = snapshot?.usageSnapshots?.drop(offset)?.take(limit)
                ?: usageDao.exportPage(offset, limit)
            return entities.map { entity ->
                val recordCount = usageDao.countRecords(entity.id)
                require(recordCount <= limits.maxUsageRecordsPerSnapshot.toLong()) {
                    "Usage snapshot exceeds record limit ${limits.maxUsageRecordsPerSnapshot}"
                }
                val records = ArrayList<UsageRecord>(recordCount.toInt())
                var recordOffset = 0
                while (recordOffset < recordCount) {
                    currentCoroutineContext().ensureActive()
                    val recordLimit = minOf(HistoryJsonLimits.PAGE_SIZE, recordCount.toInt() - recordOffset)
                    pageObserver("usageRecords", recordLimit)
                    val page = usageDao.exportRecordPage(entity.id, recordOffset, recordLimit)
                    require(page.isNotEmpty()) { "Usage record page ended before reported count" }
                    records += page.map { record ->
                        UsageRecord(record.modelName, record.totalTokens, record.promptTokens, record.completionTokens)
                    }
                    recordOffset += page.size
                }
                UsageSnapshot(
                    entity.accountId,
                    entity.capturedAt,
                    records
                )
            }
        }

        override suspend fun refreshLogPage(after: HistoryLogCursor?, limit: Int): HistoryLogPage {
            pageObserver("refreshLogs", limit)
            val rows = snapshot?.let {
                eventLogDao.newestPageUpTo(it.logMaxId, after?.recordedAt, after?.id, limit)
            } ?: eventLogDao.newestPage(after?.recordedAt, after?.id, limit)
            return HistoryLogPage(
                rows.map { entity ->
                    RefreshLogEntry(
                        entity.id,
                        RefreshLogType.valueOf(entity.eventType.name),
                        entity.totalBalanceText,
                        entity.currencyText,
                        entity.isAvailable,
                        entity.grantedBalanceText,
                        entity.toppedUpBalanceText,
                        entity.recordedAt,
                        entity.message,
                        entity.intervalSeconds ?: 0,
                        entity.expectedAt ?: 0,
                        entity.alarmMethod ?: "",
                        entity.missReason ?: ""
                    )
                },
                rows.lastOrNull()?.let { HistoryLogCursor(it.recordedAt, it.id) }
            )
        }

        private suspend fun captureSnapshot(): RoomExportSnapshot {
            val summaries = ArrayList<DailySummary>()
            var offset = 0
            while (true) {
                val page = historyDao.exportSummaryPage(offset, limits.pageSize)
                if (page.isEmpty()) break
                summaries += page.map { it.toExportDomain() }
                offset += page.size
                require(summaries.size <= limits.maxSummaries) {
                    "History export exceeds summary limit ${limits.maxSummaries}"
                }
                if (page.size < limits.pageSize) break
            }

            val rawMaxId = historyDao.maxRecordId()
            val rawCount = historyDao.countRecordsUpTo(rawMaxId)
            require(rawCount <= limits.maxRawRecords.toLong()) {
                "History export exceeds raw-record limit ${limits.maxRawRecords}"
            }

            val usageSnapshots = ArrayList<UsageSnapshotEntity>()
            offset = 0
            while (true) {
                val page = usageDao.exportPage(offset, limits.pageSize)
                if (page.isEmpty()) break
                usageSnapshots += page
                offset += page.size
                require(usageSnapshots.size <= limits.maxUsageSnapshots) {
                    "History export exceeds usage-snapshot limit ${limits.maxUsageSnapshots}"
                }
                if (page.size < limits.pageSize) break
            }
            var usageRecordCount = 0L
            usageSnapshots.map { it.id }.chunked(500).forEach { ids ->
                if (ids.isNotEmpty()) usageRecordCount += usageDao.countRecordsForSnapshots(ids)
            }

            val logMaxId = eventLogDao.maxId()
            val logCount = eventLogDao.countUpTo(logMaxId)
            require(logCount <= limits.maxRefreshLogs.toLong()) {
                "History export exceeds log limit ${limits.maxRefreshLogs}"
            }
            return RoomExportSnapshot(
                summaries = summaries,
                rawMaxId = rawMaxId,
                rawCount = rawCount,
                usageSnapshots = usageSnapshots,
                usageRecordCount = usageRecordCount,
                logMaxId = logMaxId,
                logCount = logCount
            )
        }

        private suspend fun isSnapshotStable(captured: RoomExportSnapshot): Boolean {
            val currentSummaries = ArrayList<DailySummary>()
            var offset = 0
            while (true) {
                val page = historyDao.exportSummaryPage(offset, limits.pageSize)
                if (page.isEmpty()) break
                currentSummaries += page.map { it.toExportDomain() }
                offset += page.size
                if (page.size < limits.pageSize) break
            }
            if (currentSummaries != captured.summaries) return false
            if (historyDao.countRecordsUpTo(captured.rawMaxId) != captured.rawCount) return false
            if (eventLogDao.countUpTo(captured.logMaxId) != captured.logCount) return false
            val currentUsageSnapshots = ArrayList<UsageSnapshotEntity>()
            offset = 0
            while (true) {
                val page = usageDao.exportPage(offset, limits.pageSize)
                if (page.isEmpty()) break
                currentUsageSnapshots += page
                offset += page.size
                if (page.size < limits.pageSize) break
            }
            if (currentUsageSnapshots != captured.usageSnapshots) return false
            var currentUsageRecordCount = 0L
            captured.usageSnapshots.map { it.id }.chunked(500).forEach { ids ->
                if (ids.isNotEmpty()) currentUsageRecordCount += usageDao.countRecordsForSnapshots(ids)
            }
            return currentUsageRecordCount == captured.usageRecordCount
        }
    }

    private data class RoomExportSnapshot(
        val summaries: List<DailySummary>,
        val rawMaxId: Long,
        val rawCount: Long,
        val usageSnapshots: List<UsageSnapshotEntity>,
        val usageRecordCount: Long,
        val logMaxId: Long,
        val logCount: Long
    )

    private class RoomHistoryImportConsumer(
        private val database: WalletDatabase
    ) : HistoryJsonConsumer {
        private val history = RoomHistoryRepository(database)
        private val usage = RoomUsageRepository(database)
        private val events = RoomEventLogRepository(database)
        private val knownAccounts = mutableMapOf<String, Boolean>()
        private val importedSummaryKeys = mutableSetOf<HistoryKey>()
        private val summaryOnlyKeys = mutableMapOf<HistoryKey, Boolean>()
        private val fastImportedRecords = mutableSetOf<RawRecord>()
        private var rawTableInitiallyEmpty: Boolean? = null
        private val zoneId = ZoneId.systemDefault()

        override suspend fun dailySummaries(items: List<DailySummary>): Int {
            val fresh = mutableListOf<DailySummary>()
            items.distinctBy { it.historyKey() }.forEach { summary ->
                currentCoroutineContext().ensureActive()
                val currency = summary.currency.uppercase(Locale.ROOT)
                val key = summary.historyKey()
                if (known(summary.accountId) &&
                    database.historyDao().countPublishedSummaryKey(
                        summary.date,
                        summary.accountId,
                        currency,
                        CONTINUITY_SUMMARY_IDENTITY
                    ) == 0L
                ) {
                    fresh += summary
                    importedSummaryKeys += key
                }
            }
            history.upsertSummaries(fresh)
            return fresh.size
        }

        override suspend fun rawRecords(items: List<RawRecord>): Int {
            val fresh = mutableListOf<RawRecord>()
            val useEmptyTableFastPath = rawTableInitiallyEmpty ?: (database.historyDao().countRecords() == 0L).also {
                rawTableInitiallyEmpty = it
            }
            items.distinct().forEach { record ->
                currentCoroutineContext().ensureActive()
                val currency = record.currency.uppercase(Locale.ROOT)
                if (!known(record.accountId)) return@forEach
                val key = record.historyKey(zoneId)
                if (key !in importedSummaryKeys && summaryOnlyKeys.getOrPut(key) { isSummaryOnly(key) }) {
                    return@forEach
                }
                if (useEmptyTableFastPath) {
                    if (fastImportedRecords.add(record)) fresh += record
                } else if (database.historyDao().countExactRecord(
                        record.accountId,
                        currency,
                        record.timestamp,
                        record.totalBalance.toDouble(),
                        record.grantedBalance.toDouble(),
                        record.toppedUpBalance.toDouble()
                    ) == 0L
                ) {
                    fresh += record
                }
            }
            history.insert(fresh, BalanceRecordSource.IMPORT)
            return fresh.size
        }

        override suspend fun usageSnapshots(items: List<UsageSnapshot>): Int {
            var imported = 0
            items.forEach { snapshot ->
                currentCoroutineContext().ensureActive()
                if (known(snapshot.accountId) &&
                    usage.count(snapshot.accountId, snapshot.timestamp, snapshot.timestamp + 1) == 0L
                ) {
                    usage.upsert(snapshot, "import")
                    imported++
                }
            }
            return imported
        }

        override suspend fun refreshLogs(items: List<RefreshLogEntry>): Int {
            val fresh = items.distinctBy { it.id }.filter {
                currentCoroutineContext().ensureActive()
                database.eventLogDao().get(it.id) == null
            }
            events.append(fresh)
            return fresh.size
        }

        private suspend fun known(accountId: String): Boolean {
            knownAccounts[accountId]?.let { return it }
            val value = accountId.isNotBlank() && database.accountDao().get(accountId) != null
            knownAccounts[accountId] = value
            return value
        }

        private suspend fun isSummaryOnly(key: HistoryKey): Boolean {
            if (database.historyDao().countPublishedSummaryKey(
                    key.date,
                    key.accountId,
                    key.currency,
                    CONTINUITY_SUMMARY_IDENTITY
                ) == 0L
            ) return false
            val date = LocalDate.parse(key.date)
            val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return database.historyDao().countRecordsForDay(key.accountId, key.currency, start, end) == 0L
        }
    }

    private fun DailySummary.historyKey() = HistoryKey(
        date = date,
        accountId = accountId,
        currency = currency.uppercase(Locale.ROOT)
    )

    private fun RawRecord.historyKey(zoneId: ZoneId) = HistoryKey(
        date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString(),
        accountId = accountId,
        currency = currency.uppercase(Locale.ROOT)
    )

    private data class HistoryKey(
        val date: String,
        val accountId: String,
        val currency: String
    )

    private suspend fun deleteFailedNewDestination(
        uri: Uri,
        storage: HistoryUriStorage
    ) {
        try {
            withContext(NonCancellable) { storage.delete(uri) }
        } catch (_: Exception) {
            // A failed new document remains a failed export even if its provider rejects cleanup.
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        cancellable: Boolean,
        maximumBytes: Long = Long.MAX_VALUE
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (cancellable) currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            require(read.toLong() <= maximumBytes - total) {
                "History JSON exceeds file limit $maximumBytes"
            }
            total += read
            output.write(buffer, 0, read)
        }
    }

    private object ValidatingHistoryConsumer : HistoryJsonConsumer {
        override suspend fun dailySummaries(items: List<DailySummary>) = items.size
        override suspend fun rawRecords(items: List<RawRecord>) = items.size
        override suspend fun usageSnapshots(items: List<UsageSnapshot>) = items.size
        override suspend fun refreshLogs(items: List<RefreshLogEntry>) = items.size
    }

    private class ContentResolverHistoryUriStorage(private val context: Context) : HistoryUriStorage {
        override fun openInput(uri: Uri): InputStream? = try {
            if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream() else
                context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }

        override fun openOutput(uri: Uri): OutputStream? {
            if (uri.scheme == "file") return File(requireNotNull(uri.path)).outputStream()
            val truncating = try {
                context.contentResolver.openOutputStream(uri, "rwt")
            } catch (_: Exception) {
                null
            }
            return truncating ?: context.contentResolver.openOutputStream(uri)
        }

        override fun containsExistingData(uri: Uri): Boolean? {
            if (uri.scheme == "file") {
                val target = File(requireNotNull(uri.path))
                return target.exists() && target.length() > 0L
            }
            return super.containsExistingData(uri)
        }

        override suspend fun replaceAtomically(uri: Uri, staged: File): Boolean? {
            if (uri.scheme != "file") return null
            val target = File(requireNotNull(uri.path))
            val parent = target.parentFile ?: return false
            val sibling = File.createTempFile(".${target.name}.", ".tmp", parent)
            return try {
                staged.inputStream().buffered().use { input ->
                    FileOutputStream(sibling).use { output ->
                        copy(input, output, cancellable = true)
                        output.flush()
                        output.fd.sync()
                    }
                }
                Files.move(
                    sibling.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            } catch (_: AtomicMoveNotSupportedException) {
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally {
                sibling.delete()
            }
        }

        override fun delete(uri: Uri): Boolean = try {
            if (uri.scheme == "file") File(requireNotNull(uri.path)).delete() else
                context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readAllHistory(repository: HistoryRepository): List<RawRecord> {
        val records = mutableListOf<RawRecord>()
        var cursor: HistoryCursor? = null
        while (true) {
            val page = repository.pageAll(
                after = cursor,
                limit = HistoryRepository.MAX_PAGE_SIZE
            )
            if (page.records.isEmpty()) break
            records += page.records.map { it.value }
            val next = page.nextCursor ?: break
            if (next == cursor) break
            cursor = next
        }
        return records
    }

    private suspend fun readAllUsage(
        usageRepository: UsageRepository,
        historyRepository: HistoryRepository
    ): List<com.balancesentinel.app.data.model.UsageSnapshot> {
        val snapshots = mutableListOf<com.balancesentinel.app.data.model.UsageSnapshot>()
        val accountIds = (historyRepository.summaries().map { it.accountId } + usageRepository.accountIds()).distinct()
        for (accountId in accountIds) {
            var cursor: UsageCursor? = null
            while (true) {
                val page = usageRepository.page(accountId, Long.MIN_VALUE, Long.MAX_VALUE, cursor)
                if (page.snapshots.isEmpty()) break
                snapshots += page.snapshots.map { it.value }
                val next = page.nextCursor ?: break
                if (next == cursor) break
                cursor = next
            }
        }
        return snapshots
    }
}

private fun DailySummaryEntity.toExportDomain() = DailySummary(
    accountId = accountId,
    date = date,
    currency = currency,
    open = openBalance.toFloat(),
    close = closeBalance.toFloat(),
    consumed = consumedBalance.toFloat(),
    toppedUp = toppedUpBalance.toFloat(),
    granted = grantedBalance.toFloat(),
    avgBalance = averageBalance.toFloat(),
    sampleCount = sampleCount,
    toppedUpBalanceClose = toppedUpBalanceClose.toFloat(),
    grantedBalanceClose = grantedBalanceClose.toFloat(),
    generatedAt = generatedAt
)
