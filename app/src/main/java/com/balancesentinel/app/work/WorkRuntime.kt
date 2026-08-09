package com.balancesentinel.app.work

import android.content.Context
import androidx.work.WorkManager

/**
 * Small injection seam around WorkManager.
 *
 * Keeping access behind this interface lets scheduling code use the same
 * runtime in production and a deterministic test runtime without changing
 * application behaviour while the WorkManager migration is staged.
 */
interface WorkRuntime {
    fun workManager(context: Context): WorkManager
}

object DefaultWorkRuntime : WorkRuntime {
    override fun workManager(context: Context): WorkManager =
        WorkManager.getInstance(context.applicationContext)
}