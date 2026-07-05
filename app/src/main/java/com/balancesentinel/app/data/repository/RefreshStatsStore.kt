package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.engine.ServiceHealthTracker

/**
 * 每次刷新的结果记录（环形缓冲区中的单条）。
 */
enum class RefreshOutcome { SUCCESS, FAILURE, SKIPPED }

/**
 * 刷新成功率统计快照。
 */
data class RefreshStats(
    val totalAttempts: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val skipped: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastSuccessTime: Long = 0,
    val lastAttemptTime: Long = 0
) {
    /** 成功率（0-100），无数据时返回 -1。 */
    val successRate: Int
        get() {
            val nonSkipped = successes + failures
            if (nonSkipped == 0) return -1
            return (successes * 100) / nonSkipped
        }
}

/**
 * 本地刷新成功率环形缓冲区存储。
 *
 * 最近 100 次刷新结果记录在 SharedPreferences 中，用于设置页仪表盘展示。
 * 所有数据仅存本地。
 */
object RefreshStatsStore {

    private const val PREFS_NAME = "refresh_stats"
    private const val KEY_RING = "ring_buffer"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_SUCCESSES = "successes"
    private const val KEY_FAILURES = "failures"
    private const val KEY_SKIPPED = "skipped"
    private const val KEY_LAST_SUCCESS = "last_success_time"
    private const val KEY_LAST_ATTEMPT = "last_attempt_time"

    private const val MAX_RECORDS = 100

    /**
     * 记录一次成功的刷新。
     */
    fun recordSuccess(context: Context) {
        val now = System.currentTimeMillis()
        val p = getPrefs(context)
        appendOutcome(p, RefreshOutcome.SUCCESS)
        p.edit().apply {
            putLong(KEY_LAST_SUCCESS, now)
            putLong(KEY_LAST_ATTEMPT, now)
            putInt(KEY_SUCCESSES, p.getInt(KEY_SUCCESSES, 0) + 1)
        }.apply()
    }

    /**
     * 记录一次失败的刷新。
     */
    fun recordFailure(context: Context) {
        val now = System.currentTimeMillis()
        val p = getPrefs(context)
        appendOutcome(p, RefreshOutcome.FAILURE)
        p.edit().apply {
            putLong(KEY_LAST_ATTEMPT, now)
            putInt(KEY_FAILURES, p.getInt(KEY_FAILURES, 0) + 1)
        }.apply()
    }

    /**
     * 记录一次跳过的刷新（上一轮仍在进行中）。
     */
    fun recordSkipped(context: Context) {
        val now = System.currentTimeMillis()
        val p = getPrefs(context)
        appendOutcome(p, RefreshOutcome.SKIPPED)
        p.edit().apply {
            putLong(KEY_LAST_ATTEMPT, now)
            putInt(KEY_SKIPPED, p.getInt(KEY_SKIPPED, 0) + 1)
        }.apply()
    }

    /**
     * 获取当前统计数据快照。
     */
    fun getStats(context: Context): RefreshStats {
        val p = getPrefs(context)
        return RefreshStats(
            totalAttempts = p.getInt(KEY_SUCCESSES, 0) + p.getInt(KEY_FAILURES, 0) + p.getInt(KEY_SKIPPED, 0),
            successes = p.getInt(KEY_SUCCESSES, 0),
            failures = p.getInt(KEY_FAILURES, 0),
            skipped = p.getInt(KEY_SKIPPED, 0),
            consecutiveFailures = ServiceHealthTracker.getConsecutiveFailures(context),
            lastSuccessTime = p.getLong(KEY_LAST_SUCCESS, 0),
            lastAttemptTime = p.getLong(KEY_LAST_ATTEMPT, 0)
        )
    }

    /**
     * 重置所有统计数据。
     */
    fun reset(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    // ── 私有 ──

    /**
     * 向环形缓冲区追加一条结果记录。
     * 存储为逗号分隔的字符序列："S,S,F,S,S,..."（最多 100 条）。
     */
    private fun appendOutcome(p: SharedPreferences, outcome: RefreshOutcome) {
        val ring = p.getString(KEY_RING, "") ?: ""
        val cursor = p.getInt(KEY_CURSOR, 0)
        val chars = if (ring.length < MAX_RECORDS) {
            // 缓冲区未满，直接追加
            ring + when (outcome) {
                RefreshOutcome.SUCCESS -> "S"
                RefreshOutcome.FAILURE -> "F"
                RefreshOutcome.SKIPPED -> "K"
            }
        } else {
            // 缓冲区已满，覆盖最旧记录（环形写入）
            val arr = ring.toCharArray()
            arr[cursor % MAX_RECORDS] = when (outcome) {
                RefreshOutcome.SUCCESS -> 'S'
                RefreshOutcome.FAILURE -> 'F'
                RefreshOutcome.SKIPPED -> 'K'
            }
            String(arr)
        }
        p.edit().apply {
            putString(KEY_RING, chars)
            putInt(KEY_CURSOR, (cursor + 1) % MAX_RECORDS)
        }.apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
