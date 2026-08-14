package com.balancesentinel.app.data.repository

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import com.balancesentinel.app.BuildConfig
import com.balancesentinel.app.CrashLogger
import com.balancesentinel.app.data.credentials.CredentialReadResult
import com.balancesentinel.app.data.credentials.EncryptedPreferencesCredentialStore
import com.balancesentinel.app.data.debug.ApiDebugStore
import com.balancesentinel.app.data.debug.DebugCapture
import com.balancesentinel.app.data.debug.DebugCapturePolicy
import com.balancesentinel.app.data.debug.DebugReportFormatter
import com.balancesentinel.app.data.debug.NetworkDiagnostics
import com.balancesentinel.app.data.debug.SensitiveDataRedactor
import com.balancesentinel.app.data.engine.ServiceHealthTracker
import com.balancesentinel.app.data.local.WalletDatabaseProvider
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.refresh.RefreshDiagnostics
import com.balancesentinel.app.widget.WidgetErrorLogger
import com.balancesentinel.app.work.BackgroundRefreshMode
import com.balancesentinel.app.work.RefreshWorkScheduler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking

/** Builds a bounded, redacted diagnostic report and writes it to app storage or a SAF URI. */
object LogExporter {

    private const val MAX_REPORT_BYTES = 2 * 1024 * 1024
    private const val MAX_EVENT_LOGS = 500
    private const val MAX_REFRESH_RUNS = 20
    private const val MAX_API_ENTRIES = 100
    private const val REPORT_TRUNCATED_MARKER = "\n[REPORT TRUNCATED]\n"

    /** Compatibility path used by the log screen. */
    fun export(context: Context): String? = runCatching {
        val now = System.currentTimeMillis()
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(directory, suggestedFileName(now))
        file.writeText(buildReport(context, now), Charsets.UTF_8)
        file.absolutePath
    }.getOrNull()

    /** Writes the same report to a user-selected Storage Access Framework destination. */
    fun exportToUri(context: Context, uri: Uri): Boolean = runCatching {
        val report = buildReport(context, System.currentTimeMillis())
        openOutputStream(context, uri)?.use { output ->
            output.write(report.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: return false
        true
    }.getOrDefault(false)

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String =
        "debug_report_${fileDateFormat().format(Date(now))}.txt"

    internal fun buildReport(context: Context, now: Long = System.currentTimeMillis()): String {
        val raw = buildString {
            appendLine("==========================================")
            appendLine("  Wallet Sentinel - 调试报告 / Debug Report")
            appendLine("  导出时间 / exportedAt=${formatTimestamp(now)}")
            appendLine("==========================================")
            appendLine()

            appendSection("设备信息 / Application and device") {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("  package=${context.packageName}")
                appendLine("  versionName=${packageInfo.versionName ?: "unknown"}")
                appendLine("  versionCode=${packageInfo.longVersionCode}")
                appendLine("  buildType=${BuildConfig.BUILD_TYPE}")
                appendLine("  manufacturer=${Build.MANUFACTURER}")
                appendLine("  model=${Build.MODEL}")
                appendLine("  android=${Build.VERSION.RELEASE}")
                appendLine("  sdk=${Build.VERSION.SDK_INT}")
                appendLine("  abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
                appendLine("  locale=${Locale.getDefault().toLanguageTag()}")
                appendLine("  timezone=${TimeZone.getDefault().id}")
                appendLine("  note=no device identifiers, addresses, SSID, or DNS values collected")
            }

            appendSection("网络环境 / Network environment") {
                append(NetworkDiagnostics.capture(context).toReportText())
            }

            appendSection("刷新设置 / Refresh configuration") {
                val snapshot = runBlocking { SettingsRepositoryProvider.get(context).readSnapshot() }
                val configured = snapshot.sharedRefreshIntervalSeconds
                val backgroundEnabled = snapshot.effectiveBackgroundCadenceSeconds != null
                val backgroundPlan = snapshot.effectiveBackgroundCadenceSeconds?.toLong()
                    ?.let(RefreshWorkScheduler::planFor)
                appendLine("  configuredSharedIntervalSeconds=$configured")
                appendLine("  foregroundActualIntervalSeconds=$configured")
                appendLine("  backgroundEnabled=$backgroundEnabled")
                appendLine("  backgroundMode=${backgroundPlan?.mode?.diagnosticName ?: "disabled"}")
                appendLine("  backgroundPlannedIntervalSeconds=${backgroundPlan?.scheduledIntervalSeconds ?: "disabled"}")
                appendLine(
                    "  backgroundExecutionNote=" + when (backgroundPlan?.mode) {
                        BackgroundRefreshMode.RECOVERY_CHAIN ->
                            "persistent one-shot recovery chain; system may defer execution"
                        BackgroundRefreshMode.PERIODIC ->
                            "unique periodic WorkManager request; system may defer execution"
                        null -> "background recovery disabled"
                    }
                )
                appendLine("  backgroundPlatformMinimumSeconds=${RefreshWorkScheduler.MIN_BACKGROUND_INTERVAL_SECONDS}")
                appendLine("  protectionMode=${ServiceHealthTracker.isInProtectionMode(context)}")
            }

            appendSection("系统状态 / Scheduler and service") {
                val summary = RefreshScheduler.getStatusSummary(context)
                val schedule = RefreshScheduler.getState(context)
                val health = RefreshScheduler.getServiceHealthState(context)
                val monitoring = runCatching {
                    runBlocking { WalletDatabaseProvider.get(context).monitoringStateDao().get() }
                }.getOrNull()
                appendLine("  前台服务 / serviceAlive=${summary.serviceAlive}")
                appendLine("  serviceStarting=${summary.serviceStarting}")
                appendLine("  monitoringDesired=${monitoring?.desired ?: "unknown"}")
                appendLine("  monitoringObservedState=${monitoring?.observedState ?: "unknown"}")
                appendLine("  monitoringProcessSession=${monitoring?.processSessionId ?: "none"}")
                appendLine("  monitoringLeaseExpiresAt=${monitoring?.leaseExpiresAt ?: 0L}")
                appendLine("  monitoringStateReason=${monitoring?.stateReason ?: "none"}")
                appendLine("  电池优化 / batteryOptimizing=${summary.batteryOptimizing}")
                appendLine("  restartCount=${RefreshScheduler.getRestartCount(context)}")
                appendLine("  alarmMethod=${summary.alarmMethod.ifBlank { "none" }}")
                appendLine("  scheduledIntervalSeconds=${schedule.intervalSeconds}")
                appendTime("lastScheduledAt", schedule.lastScheduledAt)
                appendTime("alarmFiredAt", schedule.alarmFiredAt)
                appendTime("expectedNextRefresh", summary.expectedNextRefresh)
                appendTime("lastHeartbeat", summary.lastHeartbeat)
                appendTime("serviceStartRequestedAt", health.startRequestedAt)
                appendTime("refreshDeadlineAt", health.refreshDeadlineAt)
                appendLine("  alarmDelaySeconds=${summary.alarmDelaySeconds}")
                appendLine("  alarmsSet=${summary.totalSet}")
                appendLine("  alarmsFired=${summary.totalFired}")
                appendLine("  alarmsCancelled=${summary.totalCancelled}")
                appendLine("  alarmsDropped=${summary.totalDropped}")
                if (summary.totalSet > 0) {
                    appendLine("  alarmArrivalRate=${summary.totalFired * 100 / summary.totalSet}%")
                    val netSet = summary.totalSet - summary.totalCancelled
                    if (netSet > 0 && netSet != summary.totalSet) {
                        appendLine("  effectiveDeliveryRate=${summary.totalFired * 100 / netSet}%")
                    }
                }
            }

            appendSection("刷新健康 / Refresh health") {
                val stats = RefreshStatsStore.getStats(context)
                appendLine("  totalAttempts=${stats.totalAttempts}")
                appendLine("  successes=${stats.successes}")
                appendLine("  failures=${stats.failures}")
                appendLine("  skipped=${stats.skipped}")
                appendLine("  successRatePercent=${stats.successRate}")
                appendLine("  consecutiveFailures=${stats.consecutiveFailures}")
                appendLine("  protectionMode=${ServiceHealthTracker.isInProtectionMode(context)}")
                appendTime("lastAttemptTime", stats.lastAttemptTime)
                appendTime("lastSuccessTime", stats.lastSuccessTime)
            }

            appendSection("Account consistency (metadata only)") {
                appendAccountDiagnostics(context)
            }

            appendSection("刷新运行账本 / Recent refresh runs") {
                val database = WalletDatabaseProvider.get(context)
                val runs = runBlocking { database.refreshRunDao().newestRuns(MAX_REFRESH_RUNS) }
                appendLine("  runs=${runs.size} limit=$MAX_REFRESH_RUNS")
                runs.forEachIndexed { index, run ->
                    appendLine(
                        "  run[$index] id=${run.id} source=${run.source} state=${run.state} " +
                            "startedAt=${formatTimestamp(run.startedAt)} " +
                            "completedAt=${formatNullableTimestamp(run.completedAt)} " +
                            "accounts=${run.accountCount} succeeded=${run.successCount} " +
                            "failed=${run.failureCount} cancelled=${run.cancelledCount} " +
                            "errorCode=${run.errorCode ?: "-"}"
                    )
                    val results = runBlocking { database.refreshRunDao().getAccountResults(run.id) }
                    results.forEachIndexed { resultIndex, result ->
                        appendLine(
                            "    account[$resultIndex] id=${result.accountId} state=${result.state} " +
                                "errorCategory=${result.errorCategory ?: "-"} " +
                                "errorCode=${result.errorCode ?: "-"} retryable=${result.retryable} " +
                                "attempts=${result.attemptCount} stale=${result.stale} " +
                                "startedAt=${formatTimestamp(result.startedAt)} " +
                                "completedAt=${formatNullableTimestamp(result.completedAt)}"
                        )
                    }
                }
                if (runs.isEmpty()) appendLine("  (无记录 / no records)")
            }

            appendSection("Refresh execution stages (current process)") {
                append(RefreshDiagnostics.toReportText(now))
            }

            appendSection("刷新日志 / Event log") {
                val entries = runBlocking {
                    RoomEventLogRepository(WalletDatabaseProvider.get(context)).newest(MAX_EVENT_LOGS)
                }
                appendLine("  entries=${entries.size} limit=$MAX_EVENT_LOGS")
                entries.forEach { appendLine(it.toLogLine()) }
                if (entries.isEmpty()) appendLine("  (无记录 / no records)")
            }

            appendSection("API 调试 / API debug capture") {
                val enabled = DebugCapturePolicy.enabled()
                val entries = ApiDebugStore.getAccountIds()
                    .flatMap(ApiDebugStore::getEntries)
                    .sortedByDescending { it.timestamp }
                    .take(MAX_API_ENTRIES)
                    .map { it.copy(accountLabel = null, scriptPreview = null) }
                appendLine("  captureEnabled=$enabled")
                appendLine("  capturePolicy=${if (enabled) "debug-build in-memory capture" else "disabled for this build"}")
                appendLine("  retainedStoreBytes=${ApiDebugStore.currentBytes}")
                appendLine("  entries=${entries.size} limit=$MAX_API_ENTRIES")
                if (!enabled) {
                    appendLine("  note=capture disabled; zero entries does not mean no network activity")
                } else if (entries.isEmpty()) {
                    appendLine("  (no captured requests in the current process)")
                } else {
                    entries.forEachIndexed { index, entry ->
                        appendLine("  --- apiEntry[$index] accountId=${entry.accountId} ---")
                        appendLine(DebugReportFormatter.formatEntry(entry).trimEnd())
                    }
                }
            }

            appendSection("Widget 错误 / Widget errors") {
                val entries = WidgetErrorLogger.getLogs(context)
                appendLine("  entries=${entries.size}")
                entries.forEachIndexed { index, entry ->
                    appendLine("  widgetError[$index] timestamp=${entry.timestamp}")
                    appendLine(indent(entry.message, 4))
                }
                if (entries.isEmpty()) appendLine("  (无记录 / no records)")
            }

            appendSection("崩溃日志 / Crashes") {
                val application = context.applicationContext as? Application
                    ?: error("Application context unavailable")
                val crashes = CrashLogger.getCrashes(application)
                appendLine("  entries=${crashes.size}")
                crashes.forEachIndexed { index, crash ->
                    appendLine("  --- crash[$index] ${crash.header} ---")
                    appendLine(crash.fullStack)
                }
                if (crashes.isEmpty()) appendLine("  (无记录 / no records)")
            }

            appendSection("面包屑 / Breadcrumbs") {
                val breadcrumbs = CrashLogger.getBreadcrumbs()
                appendLine("  entries=${breadcrumbs.size}")
                breadcrumbs.forEach { appendLine("  $it") }
                if (breadcrumbs.isEmpty()) appendLine("  (无记录 / no records)")
            }

            appendLine("==========================================")
            appendLine("  报告结束 / End of report")
        }

        return boundUtf8(SensitiveDataRedactor.redactAggregate(raw))
    }

    private fun StringBuilder.appendSection(
        title: String,
        content: StringBuilder.() -> Unit
    ) {
        appendLine("-- $title --")
        try {
            content()
        } catch (error: Exception) {
            appendLine("  unavailable=${error.javaClass.simpleName.ifBlank { "UnknownError" }}")
        }
        appendLine()
    }

    private fun StringBuilder.appendAccountDiagnostics(context: Context) {
        val database = WalletDatabaseProvider.get(context)
        val rows = runBlocking { database.accountDao().getAllForMigration() }
        appendLine("  roomRows=${rows.size}")
        rows.forEachIndexed { index, row ->
            val generationType = row.activeCredentialGeneration.substringBefore(':', "opaque")
            appendLine(
                "  roomAccount[$index] id=${row.id} state=${row.state} " +
                    "provider=${row.providerType} revision=${row.revision} " +
                    "legacyIdPresent=${row.legacyStorageId != null} " +
                    "credentialGenerationType=$generationType"
            )
        }
        when (val read = EncryptedPreferencesCredentialStore(context).read()) {
            CredentialReadResult.Missing -> appendLine("  credentialPayload=MISSING")
            is CredentialReadResult.Corrupt -> appendLine("  credentialPayload=CORRUPT")
            is CredentialReadResult.Valid -> {
                appendLine("  credentialPayload=VALID accounts=${read.payload.accounts.size}")
                read.payload.accounts.forEachIndexed { index, account ->
                    appendLine(
                        "  credentialAccount[$index] id=${account.id} " +
                            "provider=${account.providerType} revision=${account.revision}"
                    )
                }
            }
        }
    }

    private fun StringBuilder.appendTime(name: String, timestamp: Long) {
        appendLine("  $name=${if (timestamp > 0) formatTimestamp(timestamp) else "unset"}")
    }

    private fun RefreshLogEntry.toLogLine(): String {
        val typeLabel = when (type) {
            RefreshLogType.MANUAL -> "[手动]"
            RefreshLogType.AUTO -> "[自动]"
            RefreshLogType.SCHEDULE -> "[调度]"
            RefreshLogType.MISSED -> "[遗漏]"
            RefreshLogType.SERVICE_DIED -> "[服务死]"
            RefreshLogType.SERVICE_START -> "[服务启]"
            RefreshLogType.WATCHDOG -> "[看门狗]"
        }
        val parts = mutableListOf("$typeLabel ${formatTimestamp(timestamp)}")
        if (totalBalance.isNotEmpty()) parts += "balance=$totalBalance currency=$currency"
        if (message.isNotEmpty()) parts += "message=$message"
        if (missReason.isNotEmpty()) parts += "reason=$missReason"
        if (expectedTime > 0) parts += "expected=${formatTimestamp(expectedTime)}"
        if (intervalSeconds > 0) parts += "intervalSeconds=$intervalSeconds"
        if (alarmMethod.isNotEmpty()) parts += "alarmMethod=$alarmMethod"
        return "  " + parts.joinToString(" | ")
    }

    private fun openOutputStream(context: Context, uri: Uri): OutputStream? {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.outputStream()
        }
        return runCatching { context.contentResolver.openOutputStream(uri, "rwt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri)
    }

    private fun boundUtf8(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_REPORT_BYTES) return text
        val markerBytes = REPORT_TRUNCATED_MARKER.toByteArray(Charsets.UTF_8).size
        val captured = DebugCapture.captureUtf8(
            ByteArrayInputStream(bytes),
            MAX_REPORT_BYTES - markerBytes
        )
        return captured.text + REPORT_TRUNCATED_MARKER
    }

    private fun indent(value: String, spaces: Int): String {
        val prefix = " ".repeat(spaces)
        return value.lineSequence().joinToString("\n") { prefix + it }
    }

    private fun formatTimestamp(timestamp: Long): String = dateFormat().format(Date(timestamp))

    private fun formatNullableTimestamp(timestamp: Long?): String =
        timestamp?.let(::formatTimestamp) ?: "-"

    private fun dateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    private fun fileDateFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
}
