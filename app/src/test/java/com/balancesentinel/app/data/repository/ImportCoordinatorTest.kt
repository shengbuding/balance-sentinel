package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCoordinatorTest {
    @Test fun `changed account or settings makes plan stale until repreview`() = runTest {
        var accounts = listOf(AccountInfo("a", "A", "k"))
        var settings = ConfigSettings(30, false, 0f, false, 0f, 0, 10)
        val coordinator = ImportCoordinator(
            readAccounts = { accounts },
            readSettings = { settings },
            persistAccounts = { accounts = it },
            publishSettings = { settings = it }
        )
        val plan = coordinator.preview(accounts, settings)
        accounts = listOf(accounts.single().copy(label = "changed"))
        assertTrue(coordinator.apply(plan) is ImportApplyResult.StalePlan)
        val fresh = coordinator.preview(accounts, settings)
        assertTrue(coordinator.apply(fresh) is ImportApplyResult.Applied)
    }

    @Test fun `stage readback and publish failures restore all preimages`() = runTest {
        val old = listOf(AccountInfo("a", "old", "secret"))
        var accounts = old
        var settings = ConfigSettings(30, false, 0f, false, 0f, 0, 10)
        var fail = true
        val coordinator = ImportCoordinator(readAccounts = { accounts }, readSettings = { settings }, persistAccounts = { next -> if (fail) error("failure") else accounts = next }, publishSettings = { settings = it })
        val result = coordinator.apply(coordinator.preview(old, settings).copy(accounts = listOf(AccountInfo("b", "new", "newsecret"))))
        assertTrue(result is ImportApplyResult.Failed)
        assertEquals(old, accounts)
        assertEquals(ConfigSettings(30, false, 0f, false, 0f, 0, 10), settings)
    }
}
