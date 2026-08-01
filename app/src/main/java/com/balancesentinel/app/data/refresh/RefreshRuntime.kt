package com.balancesentinel.app.data.refresh

import android.content.Context
import com.balancesentinel.app.data.repository.ApiKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object RefreshRuntime {
    /**
     * Obtain the Application-scoped gateway. Production callers use this;
     * tests inject their own [RefreshGateway] directly.
     */
    fun from(context: Context): RefreshGateway =
        (context.applicationContext as com.balancesentinel.app.DeepSeekApp).refreshGateway

    fun create(context: Context): RefreshGateway {
        val appContext = context.applicationContext
        val accountStore = ApiKeyRefreshAccountStore(ApiKeyManager(appContext))
        val source = AccountBalanceRefresher()
        val committer = RefreshResultCommitter(appContext, accountStore)
        return RefreshCoordinator(
            accountStore = accountStore,
            source = source,
            committer = committer,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }
}
