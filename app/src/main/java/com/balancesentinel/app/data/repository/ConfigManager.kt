package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import com.balancesentinel.app.data.model.AccountInfo
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用配置的导入/导出。
 *
 * 导出格式为 JSON，包含所有账户和全局设置。
 * 使用 SAF (Storage Access Framework) 读写文件，
 * 不需要额外的存储权限。
 */

@Serializable
data class AppConfig(
    val version: Int = 2,
    val credentialsIncluded: Boolean = false,
    val exportedAt: String,
    val appVersion: String,
    val accounts: List<AccountInfo>,
    val settings: ConfigSettings
)

@Serializable
data class ConfigSettings(
    val refreshIntervalSeconds: Int,
    val alertEnabled: Boolean,
    val alertThreshold: Float,
    val changeAlertEnabled: Boolean,
    val changeAlertThreshold: Float,
    val changeAlertPeriodMinutes: Int,
    val logMaxEntries: Int,
    val snoozeDurationMinutes: Int = 60,
    val perCurrencyAlertSettings: List<PerCurrencyAlertSetting> = emptyList(),
    val showTotalBalance: Boolean = true,
    val notificationSelectedWallets: List<NotificationWalletSelection> = emptyList()
)

object ConfigManager {

    private const val TAG = "ConfigManager"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * API Key 脱敏：保留前 4 后 4 位，中间用 **** 替代。
     * 长度不足 8 的短 Key 全量替换为 [REDACTED]。
     */
    fun redactApiKey(key: String): String {
        if (key.length < 8) return "[REDACTED]"
        return "${key.take(4)}****${key.takeLast(4)}"
    }

    /** Detects the redaction markers used by schema-v1 credential-free backups. */
    fun isRedactedApiKey(key: String): Boolean {
        return key.contains("****") || key == "[REDACTED]"
    }

    /**
     * 将当前配置序列化为 JSON 字符串。
     * @param includeTokens true keeps credentials and scripts; false removes credentials,
     * scripts, enablement, and grants.
     */
    fun buildConfig(
        context: Context,
        apiKeyManager: ApiKeyManager,
        widgetPrefs: WidgetPrefs,
        includeTokens: Boolean = false
    ): String = buildConfig(
        context,
        apiKeyManager.getAccounts(),
        widgetPrefs,
        includeTokens
    )

    fun buildConfig(
        context: Context,
        sourceAccounts: List<AccountInfo>,
        widgetPrefs: WidgetPrefs,
        includeTokens: Boolean = false
    ): String {
        val accounts = sourceAccounts.map { account ->
            if (includeTokens) account
            else account.copy(
                apiKey = "",
                extraCredentials = account.extraCredentials.mapValues { "" },
                usageScript = null,
                usageScriptEnabled = false,
                authorizedScriptOrigins = emptySet()
            )
        }
        val settings = ConfigSettings(
            refreshIntervalSeconds = widgetPrefs.refreshIntervalSeconds,
            alertEnabled = widgetPrefs.alertEnabled,
            alertThreshold = widgetPrefs.alertThreshold,
            changeAlertEnabled = widgetPrefs.changeAlertEnabled,
            changeAlertThreshold = widgetPrefs.changeAlertThreshold,
            changeAlertPeriodMinutes = widgetPrefs.changeAlertPeriodMinutes,
            logMaxEntries = widgetPrefs.logMaxEntries,
            snoozeDurationMinutes = widgetPrefs.snoozeDurationMinutes,
            perCurrencyAlertSettings = widgetPrefs.getAllPerCurrencyAlertSettings(),
            showTotalBalance = widgetPrefs.showTotalBalanceInNotification,
            notificationSelectedWallets = widgetPrefs.getAllNotificationWalletSelections()
        )
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        val config = AppConfig(
            version = 2,
            credentialsIncluded = includeTokens,
            exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            appVersion = appVersion,
            accounts = accounts,
            settings = settings
        )
        return json.encodeToString(config)
    }

    /**
     * 将 JSON 配置写入 SAF URI。
     * @return true 表示写入成功。
     */
    fun exportToUri(
        context: Context,
        uri: Uri,
        apiKeyManager: ApiKeyManager,
        widgetPrefs: WidgetPrefs,
        includeTokens: Boolean = false
    ): Boolean = exportToUri(
        context,
        uri,
        apiKeyManager.getAccounts(),
        widgetPrefs,
        includeTokens
    )

    /**
     * 从 SAF URI 读取并解析配置。
     * @return 解析后的 [AppConfig]，失败返回 null。
     */
    fun importFromUri(context: Context, uri: Uri): AppConfig? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            decodeConfig(content)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse imported config: ${e.message}")
            null
        }
    }

    internal fun applySettings(s: ConfigSettings, widgetPrefs: WidgetPrefs) {
        widgetPrefs.refreshIntervalSeconds = s.refreshIntervalSeconds
        widgetPrefs.alertEnabled = s.alertEnabled
        widgetPrefs.alertThreshold = s.alertThreshold
        widgetPrefs.changeAlertEnabled = s.changeAlertEnabled
        widgetPrefs.changeAlertThreshold = s.changeAlertThreshold
        widgetPrefs.changeAlertPeriodMinutes = s.changeAlertPeriodMinutes
        widgetPrefs.logMaxEntries = s.logMaxEntries
        widgetPrefs.snoozeDurationMinutes = s.snoozeDurationMinutes
        widgetPrefs.applyPerCurrencyAlertSettings(s.perCurrencyAlertSettings)
        widgetPrefs.showTotalBalanceInNotification = s.showTotalBalance
        widgetPrefs.applyNotificationWalletSelections(s.notificationSelectedWallets)
    }

    fun exportToUri(
        context: Context,
        uri: Uri,
        accounts: List<AccountInfo>,
        widgetPrefs: WidgetPrefs,
        includeTokens: Boolean = false
    ): Boolean {
        return try {
            val content = buildConfig(context, accounts, widgetPrefs, includeTokens)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeConfig(content: String): AppConfig {
        val root = json.parseToJsonElement(content).jsonObject
        val decoded = json.decodeFromString<AppConfig>(content)
        return if ("version" !in root) decoded.copy(version = 1) else decoded
    }
}
