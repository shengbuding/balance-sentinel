package com.balancesentinel.app.service

import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.monitoring.MonitoringObservedState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitoringStateStoreTest {
    private lateinit var database: com.balancesentinel.app.data.local.WalletDatabase

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `legacy intent migrates an existing untouched placeholder row`() = runTest {
        val dao = database.monitoringStateDao()
        dao.getOrCreate(updatedAt = 1_000L)

        val store = MonitoringStateStore(dao, { 2_000L }, "process", { true })
        val migrated = store.get()

        assertTrue(migrated.desired)
        assertEquals(MonitoringObservedState.STARTING, migrated.observedState)
        assertEquals(MonitoringStateStore.LEGACY_RECOVERY_REASON, migrated.stateReason)
        assertEquals(2_000L, migrated.updatedAt)
    }

    @Test
    fun `explicit stop is not overwritten by legacy intent`() = runTest {
        val dao = database.monitoringStateDao()
        val store = MonitoringStateStore(dao, { 1_000L }, "process", { true })
        store.setDesired(false, at = 1_000L)

        val reloaded = MonitoringStateStore(dao, { 2_000L }, "new-process", { true }).get()

        assertFalse(reloaded.desired)
        assertEquals(MonitoringObservedState.STOPPED, reloaded.observedState)
        assertEquals("USER_STOPPED", reloaded.stateReason)
    }
}
