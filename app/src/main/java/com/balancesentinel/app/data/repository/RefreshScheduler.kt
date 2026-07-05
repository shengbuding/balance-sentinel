package com.balancesentinel.app.data.repository

import android.app.AlarmManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType

/**
 * 调度状态快照。
 */
data class ScheduleState(
    val lastScheduledAt: Long,
    val expectedNextAt: Long,
    val alarmFiredAt: Long,
    val intervalSeconds: Int,
    val alarmMethod: String,
    val totalAlarmsSet: Int,
    val totalAlarmsFired: Int,
    val totalCancelled: Int,
    val totalDropped: Int
)

/** App 首页状态面板数据 */
data class StatusSummary(
    val serviceAlive: Boolean,
    val serviceStarting: Boolean,
    val lastHeartbeat: Long,
    val expectedNextRefresh: Long,
    val alarmMethod: String,
    val alarmDelaySeconds: Long,
    val totalSet: Int,
    val totalFired: Int,
    val totalCancelled: Int,
    val totalDropped: Int,
    val batteryOptimizing: Boolean
)

/**
 * 刷新调度追踪器。
 *
 * 职责：
 * 1. 记录每次闹钟设定的参数（预定时间、间隔、方法）
 * 2. App 启动时检测是否有遗漏的自动刷新
 * 3. 提供电池优化状态等辅助诊断信息
 */
object RefreshScheduler {

    private const val PREFS_NAME = "refresh_scheduler_state"
    private const val KEY_LAST_SCHEDULED = "last_scheduled_at"
    private const val KEY_EXPECTED_NEXT = "expected_next_at"
    private const val KEY_ALARM_FIRED_AT = "alarm_fired_at"
    private const val KEY_INTERVAL = "scheduled_interval"
    private const val KEY_ALARM_METHOD = "alarm_method"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
    private const val KEY_SERVICE_RESTART_COUNT = "service_restart_count"
    private const val KEY_TOTAL_ALARMS_SET = "total_alarms_set"
    private const val KEY_TOTAL_ALARMS_FIRED = "total_alarms_fired"
    private const val KEY_TOTAL_ALARMS_CANCELLED = "total_alarms_cancelled"
    private const val KEY_TOTAL_ALARMS_DROPPED = "total_alarms_dropped"
    private const val KEY_SERVICE_START_REQUESTED_AT = "service_start_requested_at"

    // ── 写入调度状态 ──

    fun recordSchedule(
        context: Context,
        intervalSeconds: Int,
        expectedTriggerTime: Long,
        method: String
    ) {
        val p = getPrefs(context)
        val oldCount = p.getInt(KEY_TOTAL_ALARMS_SET, 0)
        p.edit().apply {
            putLong(KEY_LAST_SCHEDULED, System.currentTimeMillis())
            putLong(KEY_EXPECTED_NEXT, expectedTriggerTime)
            putInt(KEY_INTERVAL, intervalSeconds)
            putString(KEY_ALARM_METHOD, method)
            putInt(KEY_TOTAL_ALARMS_SET, oldCount + 1)
        }.apply()
    }

    /** 闹钟已触发，记录触发时间 */
    fun markFired(context: Context) {
        val p = getPrefs(context)
        val oldFired = p.getInt(KEY_TOTAL_ALARMS_FIRED, 0)
        p.edit().apply {
            putLong(KEY_ALARM_FIRED_AT, System.currentTimeMillis())
            putLong(KEY_EXPECTED_NEXT, 0)
            putInt(KEY_TOTAL_ALARMS_FIRED, oldFired + 1)
        }.apply()
    }

    /** 旧闹钟被新闹钟覆盖（主动取消） */
    fun markCancelled(context: Context) {
        val p = getPrefs(context)
        p.edit().putInt(KEY_TOTAL_ALARMS_CANCELLED, p.getInt(KEY_TOTAL_ALARMS_CANCELLED, 0) + 1).apply()
    }

    /** 闹钟预定时间已过但未触发（系统丢弃） */
    fun markDropped(context: Context) {
        val p = getPrefs(context)
        p.edit().putInt(KEY_TOTAL_ALARMS_DROPPED, p.getInt(KEY_TOTAL_ALARMS_DROPPED, 0) + 1).apply()
    }

    /** 在 startForegroundService 之前调用，标记启动请求时间（用于启动宽限期判断） */
    fun markStartRequested(context: Context) {
        getPrefs(context).edit().putLong(KEY_SERVICE_START_REQUESTED_AT, System.currentTimeMillis()).apply()
    }

    /** 将闹钟计数器全部归零（含 expectedNext 和 alarmFired 时间戳）。 */
    fun resetAlarmCounters(context: Context) {
        val p = getPrefs(context)
        p.edit().apply {
            putInt(KEY_TOTAL_ALARMS_SET, 0)
            putInt(KEY_TOTAL_ALARMS_FIRED, 0)
            putInt(KEY_TOTAL_ALARMS_CANCELLED, 0)
            putInt(KEY_TOTAL_ALARMS_DROPPED, 0)
            putLong(KEY_ALARM_FIRED_AT, 0)
            putLong(KEY_EXPECTED_NEXT, 0)
        }.apply()
    }

    // ── 读取状态 ──

    fun getState(context: Context): ScheduleState {
        val p = getPrefs(context)
        return ScheduleState(
            lastScheduledAt = p.getLong(KEY_LAST_SCHEDULED, 0),
            expectedNextAt = p.getLong(KEY_EXPECTED_NEXT, 0),
            alarmFiredAt = p.getLong(KEY_ALARM_FIRED_AT, 0),
            intervalSeconds = p.getInt(KEY_INTERVAL, 0),
            alarmMethod = p.getString(KEY_ALARM_METHOD, "") ?: "",
            totalAlarmsSet = p.getInt(KEY_TOTAL_ALARMS_SET, 0),
            totalAlarmsFired = p.getInt(KEY_TOTAL_ALARMS_FIRED, 0),
            totalCancelled = p.getInt(KEY_TOTAL_ALARMS_CANCELLED, 0),
            totalDropped = p.getInt(KEY_TOTAL_ALARMS_DROPPED, 0)
        )
    }

    /** 获取首页状态摘要 */
    fun getStatusSummary(context: Context): StatusSummary {
        val state = getState(context)
        val svcDead = isServiceDead(context)
        val delay = if (state.expectedNextAt > 0) {
            (System.currentTimeMillis() - state.expectedNextAt) / 1000
        } else 0
        return StatusSummary(
            serviceAlive = !svcDead,
            serviceStarting = isServiceStarting(context),
            lastHeartbeat = getPrefs(context).getLong(KEY_LAST_HEARTBEAT, 0),
            expectedNextRefresh = state.expectedNextAt,
            alarmMethod = state.alarmMethod,
            alarmDelaySeconds = delay,
            totalSet = state.totalAlarmsSet,
            totalFired = state.totalAlarmsFired,
            totalCancelled = state.totalCancelled,
            totalDropped = state.totalDropped,
            batteryOptimizing = isBatteryOptimizing(context)
        )
    }

    // ── 遗漏检测 ──

    /**
     * 检查是否有遗漏的自动刷新。
     * 如果 [expectedNextAt] 已过期超过 interval × 1.2，且没有 AUTO 日志覆盖该时间点，
     * 则生成 MISSED 日志。
     *
     * 返回应写入日志的 MISSED 条目列表（通常 0 或 1 条）。
     */
    fun checkMissedRefresh(context: Context): List<RefreshLogEntry> {
        val state = getState(context)
        val expected = state.expectedNextAt
        if (expected <= 0 || state.intervalSeconds <= 0) return emptyList()

        val now = System.currentTimeMillis()
        val graceMs = (state.intervalSeconds * 1200L) // interval × 1.2 作为宽限期
        val deadline = expected + graceMs

        if (now < deadline) return emptyList() // 还没到检测时间

        // 确实遗漏了 → 记录为系统丢弃
        markDropped(context)
        val delayMinutes = (now - expected) / 60_000
        val reason = buildMissReason(context, state)

        val entry = RefreshLogEntry(
            id = now,
            type = RefreshLogType.MISSED,
            timestamp = now,
            message = "预定刷新未触发，延迟 ${delayMinutes} 分钟",
            intervalSeconds = state.intervalSeconds,
            expectedTime = expected,
            alarmMethod = state.alarmMethod,
            missReason = reason
        )

        // 清除预定状态，避免重复报告同一次遗漏
        markFired(context)

        return listOf(entry)
    }

    // ── 诊断辅助 ──

    /**
     * 推测自动刷新失败的原因。
     */
    private fun buildMissReason(context: Context, state: ScheduleState): String {
        val reasons = mutableListOf<String>()

        // 1. 闹钟方法
        when (state.alarmMethod) {
            "inexact" -> reasons.add("使用了不精确闹钟（缺少 SCHEDULE_EXACT_ALARM 权限），系统可大幅延迟")
            "failed" -> reasons.add("闹钟设置完全失败")
        }

        // 2. 电池优化状态
        if (isBatteryOptimizing(context)) {
            reasons.add("电池优化已开启，系统可能冻结后台闹钟")
        }

        // 3. Doze / 省电模式
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                reasons.add("未加入电池优化白名单")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && pm.isPowerSaveMode) {
                reasons.add("设备处于省电模式")
            }
        }

        // 4. 厂商特定
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer == "oneplus" || manufacturer == "oppo") {
            reasons.add("OnePlus/OPPO ColorOS 激进后台限制，建议：设置→电池→更多→关闭'自动冻结'")
        } else if (manufacturer == "xiaomi") {
            reasons.add("MIUI 后台限制，建议：设置→电池→应用智能省电→无限制")
        } else if (manufacturer == "huawei") {
            reasons.add("EMUI 后台限制，建议：手机管家→自启管理→允许自启")
        }

        if (reasons.isEmpty()) {
            reasons.add("闹钟未在预期时间内触发，可能因系统资源紧张")
        }

        return reasons.joinToString("；")
    }

    private fun isBatteryOptimizing(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                !pm.isIgnoringBatteryOptimizations(context.packageName)
            } else false
        } catch (_: Exception) {
            false
        }
    }

    // ── 心跳追踪（检测 Foreground Service 是否存活） ──

    /** Service 每次成功刷新后调用 */
    fun heartbeat(context: Context) {
        getPrefs(context).edit().putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    /**
     * Service 是否处于"启动中"宽限期（startForegroundService 已调用但心跳尚未到达）。
     * 宽限期 5 秒——onStartCommand 中立即写心跳，正常 1-2 秒内到达，5 秒已是兜底。
     */
    fun isServiceStarting(context: Context): Boolean {
        val lastHb = getPrefs(context).getLong(KEY_LAST_HEARTBEAT, 0)
        if (lastHb > 0) return false // 已有心跳，不属于启动中
        val startRequested = getPrefs(context).getLong(KEY_SERVICE_START_REQUESTED_AT, 0)
        return startRequested > 0 && System.currentTimeMillis() - startRequested < 5_000L
    }

    /**
     * 检测 Service 是否还活着。
     * 如果最后心跳超过 [timeoutMs] 毫秒前，认为 Service 已死亡。
     *
     * 默认超时 120 秒（2 分钟），原因：
     * - 10 分钟太长：Service 死后看门狗长时间走错分支，等同于失效
     * - 2 分钟对 30 秒间隔 = 4 个周期，对 30 分钟间隔也足够敏感
     * - OnePlus 自动冻结通常 1-2 分钟内就能冻结进程，2 分钟能及时检测
     */
    fun isServiceDead(context: Context, timeoutMs: Long = 120_000L): Boolean {
        // 启动宽限期：startForegroundService 已调用但心跳尚未写入
        if (isServiceStarting(context)) return false

        val lastHb = getPrefs(context).getLong(KEY_LAST_HEARTBEAT, 0)
        if (lastHb <= 0) return true // 从未有心跳且不在启动宽限期内
        return System.currentTimeMillis() - lastHb > timeoutMs
    }

    /** 记录 Service 重启次数（诊断用） */
    fun recordRestart(context: Context) {
        val p = getPrefs(context)
        val count = p.getInt(KEY_SERVICE_RESTART_COUNT, 0)
        p.edit().putInt(KEY_SERVICE_RESTART_COUNT, count + 1).apply()
    }

    fun getRestartCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_SERVICE_RESTART_COUNT, 0)
    }

    // ── 内部 ──

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
