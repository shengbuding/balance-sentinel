package com.balancesentinel.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

sealed interface WidgetRefreshDecision {
    data object Ignored : WidgetRefreshDecision
    data class Refresh(val watchdog: Boolean) : WidgetRefreshDecision
}

class WidgetRefreshActionHandler {
    fun decide(context: Context, action: String?, now: Long): WidgetRefreshDecision = when (action) {
        StaticWidgetProvider.ACTION_REFRESH_NOW -> WidgetRefreshDecision.Refresh(false)
        StaticWidgetProvider.ACTION_WATCHDOG ->
            if (com.balancesentinel.app.data.repository.RefreshScheduler.shouldRestart(context, now)) {
                WidgetRefreshDecision.Refresh(true)
            } else WidgetRefreshDecision.Ignored
        else -> WidgetRefreshDecision.Ignored
    }
}

object WidgetRefreshIntents {
    fun manual(context: Context): Intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
        action = StaticWidgetProvider.ACTION_REFRESH_NOW
    }

    fun watchdog(context: Context): Intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
        action = StaticWidgetProvider.ACTION_WATCHDOG
    }
}

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}