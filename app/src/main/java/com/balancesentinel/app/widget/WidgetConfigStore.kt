package com.balancesentinel.app.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-widget 配置存储。
 * 每个 Widget 实例（appWidgetId）可配置：显示哪一个账户、哪一个币种。
 * 未配置的 Widget 保留现有行为（汇总显示所有账户）。
 */
object WidgetConfigStore {

    private const val PREFS_NAME = "widget_configs"
    private const val KEY_CONFIGS = "configs"

    private val json = Json { ignoreUnknownKeys = true }

    fun getConfig(context: Context, appWidgetId: Int): WidgetConfig? {
        return getAllConfigs(context)[appWidgetId]
    }

    fun saveConfig(context: Context, appWidgetId: Int, accountId: String, currency: String) {
        val all = getAllConfigs(context).toMutableMap()
        all[appWidgetId] = WidgetConfig(accountId, currency)
        val raw = json.encodeToString(all.mapKeys { it.key.toString() })
        getPrefs(context).edit().putString(KEY_CONFIGS, raw).apply()
    }

    fun removeConfig(context: Context, appWidgetId: Int) {
        val all = getAllConfigs(context).toMutableMap()
        all.remove(appWidgetId)
        val raw = json.encodeToString(all.mapKeys { it.key.toString() })
        getPrefs(context).edit().putString(KEY_CONFIGS, raw).apply()
    }

    private fun getAllConfigs(context: Context): Map<Int, WidgetConfig> {
        val raw = getPrefs(context).getString(KEY_CONFIGS, null) ?: return emptyMap()
        return try {
            // Store keys as strings, convert back to Int
            val stringMap = json.decodeFromString<Map<String, WidgetConfig>>(raw)
            stringMap.mapKeys { it.key.toIntOrNull() ?: -1 }.filterKeys { it >= 0 }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 清除所有 Widget 实例配置。 */
    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(KEY_CONFIGS).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

@Serializable
data class WidgetConfig(
    val accountId: String,
    val currency: String
)
