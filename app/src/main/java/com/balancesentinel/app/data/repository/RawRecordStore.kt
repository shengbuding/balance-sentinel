package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.model.RawRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原始刷新记录存储（保留 >=24 小时数据）。
 * 每条刷新写入一条，上限 90000。跨天不再自动清空，改为由 CleanupScheduler 批量删除。
 * 使用普通 SharedPreferences + JSON，Widget 进程也能写入。
 */
object RawRecordStore {

    private const val PREFS_NAME = "raw_records"
    private const val KEY_RECORDS = "records"

    /** 当日最大记录数（90000 条兜底） */
    const val MAX_RECORDS = 90_000

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 追加一条原始记录。
     * 跨天不再自动清空，旧数据由 CleanupScheduler 批量删除。
     */
    fun addRecord(context: Context, record: RawRecord) {
        try {
            val records = getRecordsInternal(context).toMutableList()
            records.add(record)

            // 上限兜底：保留最新 MAX_RECORDS 条
            if (records.size > MAX_RECORDS) {
                records.subList(0, records.size - MAX_RECORDS).clear()
            }

            val serialized = json.encodeToString(ListSerializer(RawRecord.serializer()), records)
            getPrefs(context).edit().putString(KEY_RECORDS, serialized).apply()
        } catch (_: Exception) {
            // 记录写入失败不应影响刷新主流程
        }
    }

    /**
     * 读取今日所有原始记录。
     */
    fun getTodayRecords(context: Context): List<RawRecord> {
        return try {
            val today = dateFormat.format(Date())
            getRecordsInternal(context).filter {
                dateFormat.format(Date(it.timestamp)) == today
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取全部原始记录（含跨天旧数据，用于启动检测补汇）。
     */
    fun getAllRecords(context: Context): List<RawRecord> {
        return try {
            getRecordsInternal(context)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取指定账户的今日原始记录。
     */
    fun getTodayRecordsForAccount(context: Context, accountId: String): List<RawRecord> {
        return try {
            val today = dateFormat.format(Date())
            getRecordsInternal(context).filter {
                dateFormat.format(Date(it.timestamp)) == today && it.accountId == accountId
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取指定账户的全部原始记录。
     */
    fun getAllRecordsForAccount(context: Context, accountId: String): List<RawRecord> {
        return try {
            getRecordsInternal(context).filter { it.accountId == accountId }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 清除全部原始记录。
     */
    fun clear(context: Context) {
        try {
            getPrefs(context).edit().remove(KEY_RECORDS).apply()
        } catch (_: Exception) {}
    }

    /**
     * 精确删除指定的记录（按 accountId + timestamp 匹配）。
     * 不会影响未匹配的记录。用于午夜聚合后只删除已归档的旧数据。
     */
    fun removeRecords(context: Context, recordsToRemove: List<RawRecord>) {
        try {
            val toRemove = recordsToRemove.map { it.accountId to it.timestamp }.toSet()
            val remaining = getRecordsInternal(context).filter {
                (it.accountId to it.timestamp) !in toRemove
            }
            if (remaining.size < getRecordsInternal(context).size) {
                val serialized = json.encodeToString(ListSerializer(RawRecord.serializer()), remaining)
                getPrefs(context).edit().putString(KEY_RECORDS, serialized).apply()
            }
        } catch (_: Exception) {}
    }

    /** 内部读取，不做日期过滤 */
    private fun getRecordsInternal(context: Context): List<RawRecord> {
        val raw = getPrefs(context).getString(KEY_RECORDS, null) ?: return emptyList()
        return json.decodeFromString(ListSerializer(RawRecord.serializer()), raw)
    }

    /**
     * 读取指定时间戳之后的所有记录（24h 滑动窗口）。
     */
    fun getRecordsSince(context: Context, timestamp: Long): List<RawRecord> {
        return try {
            getRecordsInternal(context).filter { it.timestamp >= timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取指定日期的所有记录。
     */
    fun getRecordsForDate(context: Context, date: String): List<RawRecord> {
        return try {
            getRecordsInternal(context).filter {
                dateFormat.format(Date(it.timestamp)) == date
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 返回存储中所有不同的日期（yyyy-MM-dd）。
     */
    fun getDistinctDates(context: Context): List<String> {
        return try {
            getRecordsInternal(context)
                .map { dateFormat.format(Date(it.timestamp)) }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 批量删除指定日期中年龄超过 minAgeMs 的记录。
     * 只删除旧记录，保留 < minAgeMs 的记录以维持 24h 滑动窗口完整性。
     */
    fun removeByDate(context: Context, date: String, minAgeMs: Long = 24 * 3600_000L) {
        try {
            val now = System.currentTimeMillis()
            val remaining = getRecordsInternal(context).filter {
                dateFormat.format(Date(it.timestamp)) != date ||
                    (now - it.timestamp) < minAgeMs
            }
            val originalSize = getRecordsInternal(context).size
            if (remaining.size < originalSize) {
                val serialized = json.encodeToString(ListSerializer(RawRecord.serializer()), remaining)
                getPrefs(context).edit().putString(KEY_RECORDS, serialized).apply()
            }
        } catch (_: Exception) {}
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
