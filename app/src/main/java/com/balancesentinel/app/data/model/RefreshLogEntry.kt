package com.balancesentinel.app.data.model

import kotlinx.serialization.Serializable

/**
 * 刷新日志类型。
 */
enum class RefreshLogType {
    /** 用户在 App 内点击刷新按钮 */
    MANUAL,
    /** 自动刷新成功（Service 调 API 或 Widget 读缓存） */
    AUTO,
    /** 闹钟被设定（记录调度结果，不含余额数据） */
    SCHEDULE,
    /** 检测到预定的自动刷新未发生 */
    MISSED,
    /** 检测到 Foreground Service 已停止（心跳超时） */
    SERVICE_DIED,
    /** Foreground Service 启动或重启 */
    SERVICE_START,
    /** AlarmManager 看门狗触发（唤醒检查 Service 状态） */
    WATCHDOG
}

/**
 * 单条刷新日志。
 * 不同 type 使用不同字段组合，未使用的字段保持默认值。
 */
@Serializable
data class RefreshLogEntry(
    val id: Long,
    val type: RefreshLogType,

    // ── 余额快照（MANUAL / AUTO 时填充） ──
    val totalBalance: String = "",
    val currency: String = "",
    val isAvailable: Boolean = false,
    val grantedBalance: String = "",
    val toppedUpBalance: String = "",

    // ── 时间戳 ──
    val timestamp: Long,

    // ── 诊断信息（SCHEDULE / MISSED 时填充） ──
    /** 人类可读的摘要描述 */
    val message: String = "",
    /** 设定的刷新间隔（秒） */
    val intervalSeconds: Int = 0,
    /** 预定下次触发的时间 */
    val expectedTime: Long = 0,
    /** 闹钟方法："alarm_clock" / "exact" / "inexact" / "foreground_service" / "protection_mode" / "failed" */
    val alarmMethod: String = "",
    /** 失败原因推测 */
    val missReason: String = ""
)
