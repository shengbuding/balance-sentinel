package com.balancesentinel.app.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dispatches a widget refresh action with guaranteed [finish] callback.
 * Ensures [finish] is always invoked in a `finally` block regardless
 * of whether [action] succeeds or throws.
 *
 * Used by [StaticWidgetProvider] after `goAsync()` to manage the
 * pending-result lifecycle. The [action] performs the actual refresh;
 * [finish] calls `PendingResult.finish()`.
 */
class WidgetRefreshDispatcher(
    private val action: () -> Unit,
    private val finish: () -> Unit
) {
    /**
     * Execute [action] and guarantee [finish] is called in `finally`.
     */
    fun dispatch() {
        try {
            action()
        } finally {
            finish()
        }
    }
}

class WidgetRefreshCoroutineDispatcher(
    private val scope: CoroutineScope
) {
    fun dispatch(action: suspend () -> Unit, finish: () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (_: Exception) {
                // The broadcast lifecycle is completed below; refresh errors are non-fatal.
            } finally {
                finish()
            }
        }
    }
}
