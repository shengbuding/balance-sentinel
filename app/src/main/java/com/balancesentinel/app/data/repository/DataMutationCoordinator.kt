package com.balancesentinel.app.data.repository

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object DataMutationCoordinator {
    private val lock = ReentrantLock(true)

    // Always acquire this coordinator before any store-specific lock.
    fun <T> withMutation(block: () -> T): T = lock.withLock(block)
}
