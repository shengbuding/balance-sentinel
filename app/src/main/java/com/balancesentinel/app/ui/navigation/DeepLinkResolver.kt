package com.balancesentinel.app.ui.navigation

import android.content.Intent
import android.net.Uri
import com.balancesentinel.app.data.model.AccountInfo
import java.util.Currency
import java.util.Locale

/** Pure deep-link input and resolution support shared by all producers. */
data class DeepLinkInput(
    val uri: Uri? = null,
    val target: String? = null,
    val accountId: String? = null,
    val currency: String? = null
)

sealed interface DeepLinkResult {
    val route: AppRoute

    data class Resolved(override val route: AppRoute) : DeepLinkResult
    data class InvalidDeepLink(val reason: InvalidReason) : DeepLinkResult {
        override val route: AppRoute = AppRoute.InvalidDeepLink(reason.name)
    }
}

enum class InvalidReason {
    MissingUri,
    UnsupportedScheme,
    UnsupportedTarget,
    MissingAccount,
    UnknownAccount,
    MissingCurrency,
    InvalidCurrency,
    MalformedUri
}

/**
 * Resolves canonical URI and legacy notification extras through one code path.
 * Account membership is supplied by the caller, so this class has no storage or
 * Android lifecycle dependency.
 */
object DeepLinkResolver {

    fun resolve(uri: Uri?, accountIds: Set<String>): DeepLinkResult =
        resolve(DeepLinkInput(uri = uri), accountIds)

    fun resolve(intent: Intent?, accountIds: Set<String>): DeepLinkResult {
        if (intent == null) return invalid(InvalidReason.MissingUri)
        val input = LegacyNavigationAdapter.inputOf(intent)
        return resolve(input, accountIds)
    }

    fun resolve(input: DeepLinkInput, accountIds: Set<String>): DeepLinkResult {
        val uri = input.uri
        if (uri != null) {
            return resolveUri(uri, accountIds)
        }
        return resolveLegacy(input, accountIds)
    }

    fun resolve(uri: Uri?, accounts: Collection<AccountInfo>): DeepLinkResult =
        resolve(uri, accounts.map { it.id }.toSet())

    fun resolve(intent: Intent?, accounts: Collection<AccountInfo>): DeepLinkResult =
        resolve(intent, accounts.map { it.id }.toSet())

    fun resolveLegacy(
        target: String?, accountId: String?, currency: String?, accountIds: Set<String>
    ): DeepLinkResult = resolve(
        DeepLinkInput(target = target, accountId = accountId, currency = currency), accountIds
    )

    private fun resolveUri(uri: Uri, accountIds: Set<String>): DeepLinkResult {
        if (!uri.scheme.equals(AppRoute.SCHEME, ignoreCase = true)) {
            return invalid(InvalidReason.UnsupportedScheme)
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host != AppRoute.INSIGHTS_HOST) return invalid(InvalidReason.UnsupportedTarget)

        val segments = uri.pathSegments
        if (segments.isNotEmpty() && (segments.size > 2 || segments.any { it.isBlank() })) {
            return invalid(InvalidReason.MalformedUri)
        }
        val accountId = segments.firstOrNull()
            ?: uri.getQueryParameter("account_id")
            ?: uri.getQueryParameter("accountId")
        val currency = segments.getOrNull(1)
            ?: uri.getQueryParameter("currency")
        return resolveInsights(accountId, currency, accountIds)
    }

    private fun resolveLegacy(input: DeepLinkInput, accountIds: Set<String>): DeepLinkResult {
        if (!input.target.equals("insights", ignoreCase = true)) {
            return invalid(if (input.target == null) InvalidReason.MissingUri else InvalidReason.UnsupportedTarget)
        }
        return resolveInsights(input.accountId, input.currency, accountIds)
    }

    private fun resolveInsights(
        accountId: String?, currency: String?, accountIds: Set<String>
    ): DeepLinkResult {
        val id = accountId?.trim().orEmpty()
        if (id.isEmpty()) return invalid(InvalidReason.MissingAccount)
        if (id !in accountIds) return invalid(InvalidReason.UnknownAccount)
        val normalizedCurrency = currency?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (normalizedCurrency.isEmpty()) return invalid(InvalidReason.MissingCurrency)
        if (!isIsoCurrency(normalizedCurrency)) return invalid(InvalidReason.InvalidCurrency)
        return DeepLinkResult.Resolved(AppRoute.Insights(id, normalizedCurrency))
    }

    private fun isIsoCurrency(value: String): Boolean = try {
        value.length == 3 && Currency.getInstance(value).currencyCode == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun invalid(reason: InvalidReason) = DeepLinkResult.InvalidDeepLink(reason)
}

/** Keeps legacy extras as an input adapter; resolution remains centralized above. */
object LegacyNavigationAdapter {
    fun inputOf(intent: Intent): DeepLinkInput = DeepLinkInput(
        uri = intent.data,
        target = intent.getStringExtra(AppRoute.LEGACY_TARGET_EXTRA),
        accountId = intent.getStringExtra(AppRoute.LEGACY_ACCOUNT_EXTRA),
        currency = intent.getStringExtra(AppRoute.LEGACY_CURRENCY_EXTRA)
    )
}
