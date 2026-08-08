package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

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
        return try {
            val content = buildExport(context)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (_: Exception) {
            false
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
        val data = importFromUri(context, uri) ?: return null
        return applyImport(context, data)
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
