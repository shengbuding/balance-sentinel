package com.balancesentinel.app.widget

/**
 * Dispatches a widget refresh action with guaranteed [finish] callback.
 * Ensures [finish] is always invoked in a `finally` block regardless
 * of whether [action] succeeds or throws.
 *
 * Used by [StaticWidgetProvider] after `goAsync()` to manage the
 * pending-result lifecycle. The [action] performs the actual refresh;
 * [finish] calls `PendingResult.finish()`.
 *
 * Contract shell — test support only. Behavior added in GREEN phase.
 */
class WidgetRefreshDispatcher(
    private val action: () -> Unit,
    private val finish: () -> Unit
) {
    /**
     * Execute [action] and guarantee [finish] is called.
     *
     * Inert — no behavior yet. GREEN phase adds try/finally.
     */
    fun dispatch() {
        // Inert — no behavior yet.
    }
}
