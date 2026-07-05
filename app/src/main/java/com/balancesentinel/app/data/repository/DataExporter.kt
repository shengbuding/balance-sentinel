package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
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
    val rawRecords: List<RawRecord>
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

        val data = DataExport(
            version = 1,
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            appVersion = appVersion,
            dailySummaries = DailySummaryStore.getSummaries(context),
            rawRecords = RawRecordStore.getAllRecords(context)
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
        return DailySummaryStore.getSummaries(context).isNotEmpty() ||
                RawRecordStore.getAllRecords(context).isNotEmpty()
    }

    // ── 导入 ──

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
     *
     * 合并策略（非覆盖）：
     * - 日摘要：按 (date + currency + accountId) 去重，
     *   已存在的保留本地版本，不存在的追加
     * - 原始记录：按 (accountId + timestamp) 去重，
     *   已存在的跳过，不存在的追加
     *
     * @return Pair(summariesImported, recordsImported) 实际新增数量
     */
    fun applyImport(context: Context, data: DataExport): Pair<Int, Int> {
        val summariesImported = mergeSummaries(context, data.dailySummaries)
        val recordsImported = mergeRecords(context, data.rawRecords)
        return Pair(summariesImported, recordsImported)
    }

    /**
     * 便捷方法：直接从 URI 导入并合并。
     * @return Pair(summariesImported, recordsImported)，失败返回 null。
     */
    fun importAndApply(context: Context, uri: Uri): Pair<Int, Int>? {
        val data = importFromUri(context, uri) ?: return null
        return applyImport(context, data)
    }

    /**
     * 合并日摘要：按 (date + currency + accountId) 去重。
     * 已存在的不覆盖（本地可能有更新的快照数据）。
     */
    private fun mergeSummaries(context: Context, imported: List<DailySummary>): Int {
        if (imported.isEmpty()) return 0
        val existing = DailySummaryStore.getSummaries(context)
        val existingKeys = existing.map { Triple(it.date, it.currency, it.accountId) }.toSet()
        val newSummaries = imported.filter {
            Triple(it.date, it.currency, it.accountId) !in existingKeys
        }
        for (summary in newSummaries) {
            DailySummaryStore.addSummary(context, summary)
        }
        return newSummaries.size
    }

    /**
     * 合并原始记录：按 (accountId + timestamp) 去重。
     * 已存在的时间戳跳过（同一时刻同账户只可能有一条记录）。
     */
    private fun mergeRecords(context: Context, imported: List<RawRecord>): Int {
        if (imported.isEmpty()) return 0
        val existing = RawRecordStore.getAllRecords(context)
        val existingKeys = existing.map { it.accountId to it.timestamp }.toSet()
        val newRecords = imported.filter {
            (it.accountId to it.timestamp) !in existingKeys
        }
        for (record in newRecords) {
            RawRecordStore.addRecord(context, record)
        }
        return newRecords.size
    }
}
