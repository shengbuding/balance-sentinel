package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用量快照存储（保留最近 30 天，最多 90 条）。
 * 每天每条账户保留一条快照，按时间戳去重。
 */
object UsageDataStore {

    private const val PREFS_NAME = "usage_snapshots"
    private const val KEY_SNAPSHOTS = "snapshots"
    private const val MAX_SNAPSHOTS = 90

    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 保存一条用量快照（同一天+同账户覆盖旧数据）。
     */
    fun saveSnapshot(context: Context, snapshot: UsageSnapshot) {
        try {
            val snapshots = getAllSnapshots(context).toMutableList()
            val today = dateFormat.format(Date(snapshot.timestamp))
            val existingIndex = snapshots.indexOfFirst {
                dateFormat.format(Date(it.timestamp)) == today && it.accountId == snapshot.accountId
            }
            if (existingIndex >= 0) {
                snapshots[existingIndex] = snapshot
            } else {
                snapshots.add(snapshot)
            }
            // 按时间排序，超限删旧
            snapshots.sortBy { it.timestamp }
            if (snapshots.size > MAX_SNAPSHOTS) {
                snapshots.subList(0, snapshots.size - MAX_SNAPSHOTS).clear()
            }
            val serialized = json.encodeToString(ListSerializer(UsageSnapshot.serializer()), snapshots)
            getPrefs(context).edit().putString(KEY_SNAPSHOTS, serialized).apply()
        } catch (_: Exception) {}
    }

    /**
     * 读取全部快照。
     */
    fun getAllSnapshots(context: Context): List<UsageSnapshot> {
        return try {
            val raw = getPrefs(context).getString(KEY_SNAPSHOTS, null) ?: return emptyList()
            json.decodeFromString(ListSerializer(UsageSnapshot.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取最近 [days] 天的快照（按账户筛选，null = 全部）。
     */
    fun getRecentSnapshots(context: Context, days: Int, accountId: String? = null): List<UsageSnapshot> {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        return getAllSnapshots(context).filter {
            it.timestamp >= cutoff && (accountId == null || it.accountId == accountId)
        }
    }

    /**
     * 计算指定账户在最近 [days] 天的总 token 消耗。
     */
    fun getTotalTokens(context: Context, days: Int, accountId: String? = null): Long {
        val snapshots = getRecentSnapshots(context, days, accountId)
        if (snapshots.isEmpty()) return 0
        // 取最近一条的总 token 数与最早一条的差值作为消耗量
        val latest = snapshots.last().records.sumOf { it.total_tokens }
        val earliest = snapshots.first().records.sumOf { it.total_tokens }
        return (latest - earliest).coerceAtLeast(0)
    }

    /**
     * 清除所有用量快照。
     */
    fun clear(context: Context) {
        try {
            getPrefs(context).edit().remove(KEY_SNAPSHOTS).apply()
        } catch (_: Exception) {}
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
