package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
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
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
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
    fun exportToUri(context: Context, uri: Uri): Boolean {
        var temporary: File? = null
        return try {
            val temp = File.createTempFile("history-export-", ".json", context.cacheDir)
            temporary = temp
            val database = WalletDatabaseProvider.get(context)
            kotlinx.coroutines.runBlocking {
                database.withTransaction {
                    temp.outputStream().buffered().use { output ->
                        HistoryJsonWriter().write(
                            output,
                            HistoryExportHeader(
                                version = 1,
                                exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                                appVersion = appVersion(context)
                            ),
                            RoomHistoryExportSource(database)
                        )
                    }
                }
            }
            context.contentResolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().buffered().use { input -> input.copyTo(out) }
                out.flush()
                true
            } ?: false
        } catch (_: Exception) {
            false
        } finally {
            temporary?.delete()
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
                    val existingSummaries = history.summaries().mapTo(mutableSetOf()) { it.historyKey() }
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
    fun importAndApply(context: Context, uri: Uri): ImportResult? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { stream ->
                DataMutationCoordinator.withMutation {
                    kotlinx.coroutines.runBlocking {
                        val database = WalletDatabaseProvider.get(context)
                        database.withTransaction {
                            val result = HistoryJsonReader().read(stream, RoomHistoryImportConsumer(database))
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
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }

    private class RoomHistoryExportSource(
        database: WalletDatabase
    ) : HistoryExportSource {
        private val historyDao = database.historyDao()
        private val usageDao = database.usageDao()
        private val eventLogDao = database.eventLogDao()

        override suspend fun dailySummaryPage(offset: Int, limit: Int): List<DailySummary> =
            historyDao.exportSummaryPage(offset, limit).map { entity ->
                DailySummary(
                    entity.accountId, entity.date, entity.currency,
                    entity.openBalance.toFloat(), entity.closeBalance.toFloat(),
                    entity.consumedBalance.toFloat(), entity.toppedUpBalance.toFloat(),
                    entity.grantedBalance.toFloat(), entity.averageBalance.toFloat(),
                    entity.sampleCount, entity.toppedUpBalanceClose.toFloat(),
                    entity.grantedBalanceClose.toFloat(), entity.generatedAt
                )
            }

        override suspend fun rawRecordPage(after: HistoryCursor?, limit: Int): HistoryPage {
            val rows = historyDao.keysetPageAll(
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                after?.recordedAt,
                after?.id,
                limit
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

        override suspend fun usageSnapshotPage(offset: Int, limit: Int): List<UsageSnapshot> =
            usageDao.exportPage(offset, limit).map { snapshot ->
                UsageSnapshot(
                    snapshot.accountId,
                    snapshot.capturedAt,
                    usageDao.getRecords(snapshot.id).map { record ->
                        UsageRecord(record.modelName, record.totalTokens, record.promptTokens, record.completionTokens)
                    }
                )
            }

        override suspend fun refreshLogPage(after: HistoryLogCursor?, limit: Int): HistoryLogPage {
            val rows = eventLogDao.newestPage(after?.recordedAt, after?.id, limit)
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
    }

    private class RoomHistoryImportConsumer(
        private val database: WalletDatabase
    ) : HistoryJsonConsumer {
        private val history = RoomHistoryRepository(database)
        private val usage = RoomUsageRepository(database)
        private val events = RoomEventLogRepository(database)
        private val knownAccounts = mutableMapOf<String, Boolean>()
        private val importedSummaryKeys = mutableSetOf<HistoryKey>()
        private val zoneId = ZoneId.systemDefault()

        override suspend fun dailySummaries(items: List<DailySummary>): Int {
            val fresh = mutableListOf<DailySummary>()
            items.distinctBy { it.historyKey() }.forEach { summary ->
                val currency = summary.currency.uppercase(Locale.ROOT)
                val key = summary.historyKey()
                if (known(summary.accountId) &&
                    database.historyDao().countSummaryKey(summary.date, summary.accountId, currency) == 0L
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
            items.distinct().forEach { record ->
                val currency = record.currency.uppercase(Locale.ROOT)
                if (!known(record.accountId)) return@forEach
                val key = record.historyKey(zoneId)
                if (key !in importedSummaryKeys && isSummaryOnly(key)) return@forEach
                if (database.historyDao().countExactRecord(
                        record.accountId,
                        currency,
                        record.timestamp,
                        record.totalBalance.toDouble(),
                        record.grantedBalance.toDouble(),
                        record.toppedUpBalance.toDouble()
                    ) == 0L
                ) fresh += record
            }
            history.insert(fresh, BalanceRecordSource.IMPORT)
            return fresh.size
        }

        override suspend fun usageSnapshots(items: List<UsageSnapshot>): Int {
            var imported = 0
            items.forEach { snapshot ->
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
            val fresh = items.distinctBy { it.id }.filter { database.eventLogDao().get(it.id) == null }
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
            if (database.historyDao().countSummaryKey(key.date, key.accountId, key.currency) == 0L) return false
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
