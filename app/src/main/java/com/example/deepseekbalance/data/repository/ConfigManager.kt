package com.example.deepseekbalance.data.repository

import android.content.Context
import android.net.Uri
import com.example.deepseekbalance.data.model.AccountInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val version: Int = 1,
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
    val logMaxEntries: Int
)

object ConfigManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 将当前配置序列化为 JSON 字符串。
     */
    fun buildConfig(
        context: Context,
        apiKeyManager: ApiKeyManager,
        widgetPrefs: WidgetPrefs
    ): String {
        val accounts = apiKeyManager.getAccounts()
        val settings = ConfigSettings(
            refreshIntervalSeconds = widgetPrefs.refreshIntervalSeconds,
            alertEnabled = widgetPrefs.alertEnabled,
            alertThreshold = widgetPrefs.alertThreshold,
            changeAlertEnabled = widgetPrefs.changeAlertEnabled,
            changeAlertThreshold = widgetPrefs.changeAlertThreshold,
            changeAlertPeriodMinutes = widgetPrefs.changeAlertPeriodMinutes,
            logMaxEntries = widgetPrefs.logMaxEntries
        )
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        val config = AppConfig(
            version = 1,
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
        widgetPrefs: WidgetPrefs
    ): Boolean {
        return try {
            val content = buildConfig(context, apiKeyManager, widgetPrefs)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 SAF URI 读取并解析配置。
     * @return 解析后的 [AppConfig]，失败返回 null。
     */
    fun importFromUri(context: Context, uri: Uri): AppConfig? {
        return try {
            val content = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null
            json.decodeFromString<AppConfig>(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将导入的配置应用到 App：覆盖所有账户和设置。
     */
    fun applyConfig(
        config: AppConfig,
        apiKeyManager: ApiKeyManager,
        widgetPrefs: WidgetPrefs
    ) {
        // 清空现有账户，写入导入账户
        apiKeyManager.clearAll()
        for (account in config.accounts) {
            apiKeyManager.addAccount(account.label, account.apiKey)
        }

        // 应用全局设置
        val s = config.settings
        widgetPrefs.refreshIntervalSeconds = s.refreshIntervalSeconds
        widgetPrefs.alertEnabled = s.alertEnabled
        widgetPrefs.alertThreshold = s.alertThreshold
        widgetPrefs.changeAlertEnabled = s.changeAlertEnabled
        widgetPrefs.changeAlertThreshold = s.changeAlertThreshold
        widgetPrefs.changeAlertPeriodMinutes = s.changeAlertPeriodMinutes
        widgetPrefs.logMaxEntries = s.logMaxEntries
    }

    /**
     * 便捷方法：直接从 Context 创建依赖并应用配置。
     * 用于没有 ViewModel 的独立页面。
     */
    fun applyConfigDirectly(context: Context, config: AppConfig) {
        applyConfig(config, ApiKeyManager(context), WidgetPrefs(context))
    }
}
