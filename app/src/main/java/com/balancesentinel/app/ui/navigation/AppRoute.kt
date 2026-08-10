package com.balancesentinel.app.ui.navigation

import android.net.Uri
import java.util.Locale

/**
 * The application's navigation contract.
 *
 * Screens are intentionally represented here without a NavController. This keeps
 * deep-link parsing and producers (notifications/widgets) testable while the legacy
 * screen switch is migrated in a later task.
 */
sealed interface AppRoute {
    val route: String

    data object Onboarding : AppRoute { override val route = "onboarding" }
    data object Home : AppRoute { override val route = "home" }
    data class Insights(val accountId: String, val currency: String) : AppRoute {
        override val route: String
            get() = "insights/${Uri.encode(accountId)}/${currency.uppercase(Locale.ROOT)}"
    }
    data object Settings : AppRoute { override val route = "settings" }
    data object RefreshSettings : AppRoute { override val route = "refresh-settings" }
    data object SystemStatus : AppRoute { override val route = "system-status" }
    data object About : AppRoute { override val route = "about" }
    data object Log : AppRoute { override val route = "log" }
    data object DataHub : AppRoute { override val route = "data-hub" }
    data object ClearData : AppRoute { override val route = "clear-data" }
    data object BackupRestore : AppRoute { override val route = "backup-restore" }
    data object AlertSettings : AppRoute { override val route = "alert-settings" }
    data object ConsoleSelect : AppRoute { override val route = "console-select" }
    data class Console(val platformId: String) : AppRoute {
        override val route: String get() = "console/${Uri.encode(platformId)}"
    }
    data object AddPlatform : AppRoute { override val route = "add-platform" }
    data class InvalidDeepLink(val reason: String) : AppRoute {
        override val route: String get() = "invalid-deep-link"
    }

    /** One canonical URI shape used by notifications, widgets and external intents. */
    fun toUri(): Uri = when (this) {
        Home -> Uri.parse("$SCHEME://home")
        is Insights -> Uri.Builder()
            .scheme(SCHEME)
            .authority(INSIGHTS_HOST)
            .appendPath(Uri.encode(accountId))
            .appendPath(currency.uppercase(Locale.ROOT))
            .build()
        else -> Uri.parse("$SCHEME://$route")
    }

    companion object {
        const val SCHEME = "balancesentinel"
        const val INSIGHTS_HOST = "insights"
        const val LEGACY_TARGET_EXTRA = "deep_link_target"
        const val LEGACY_ACCOUNT_EXTRA = "deep_link_account_id"
        const val LEGACY_CURRENCY_EXTRA = "deep_link_currency"
    }
}
