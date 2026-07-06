package com.balancesentinel.app.data.update

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdatePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    var lastPromptDate: String
        get() = prefs.getString(KEY_LAST_PROMPT_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_PROMPT_DATE, value).apply()

    var skippedVersion: String
        get() = prefs.getString(KEY_SKIPPED_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SKIPPED_VERSION, value).apply()

    fun shouldAutoCheckToday(): Boolean {
        val today = todayString()
        return lastPromptDate != today
    }

    fun shouldSkipVersion(version: String): Boolean {
        return skippedVersion.isNotEmpty() && skippedVersion == version
    }

    fun markPromptedToday() {
        lastPromptDate = todayString()
    }

    private fun todayString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    companion object {
        private const val KEY_LAST_PROMPT_DATE = "last_prompt_date"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
    }
}
