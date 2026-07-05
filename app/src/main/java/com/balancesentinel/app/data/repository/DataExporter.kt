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
}
