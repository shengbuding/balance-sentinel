package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.AccountInfo
import java.security.MessageDigest

object ImportFingerprint {
    fun sha256(plan: ImportPlan): String = sha256(plan.accounts, plan.settings, plan.baselineRevision)

    fun sha256(accounts: List<AccountInfo>, settings: ConfigSettings, baselineRevision: Long = 0): String {
        val canonical = buildString {
            append("revision=").append(baselineRevision).append('|')
            accounts.sortedBy { it.id }.forEach { account ->
                append(account.id).append('\u0000').append(account.label).append('\u0000')
                append(account.apiKey).append('\u0000').append(account.providerType.name).append('\u0000')
                account.extraCredentials.toSortedMap().forEach { (k, v) -> append("c:").append(k).append('=').append(v).append(';') }
                account.extraSettings.toSortedMap().forEach { (k, v) -> append("s:").append(k).append('=').append(v).append(';') }
                append("script=").append(account.usageScript ?: "").append('\u0000')
                append(account.usageScriptEnabled).append('\u0000')
                account.authorizedScriptOrigins.sorted().forEach { append(it).append(';') }
                append("accountRevision=").append(account.revision).append('|')
            }
            append(settings)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
