package com.balancesentinel.app.data.local.monitoring

import android.database.sqlite.SQLiteConstraintException
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitoringSessionDaoTest {
    private lateinit var database: WalletDatabase

    @Before
    fun setUp() {
        database = createWalletTestDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `one DATA_SYNC session is open until close or recovery clears its active slot`() = runTest {
        val dao = database.monitoringSessionDao()
        dao.insertStart(openSession("first", "process-1", 10))

        assertConstraint { dao.insertStart(openSession("blocked", "process-2", 20)) }
        assertEquals(
            1,
            dao.endCurrent("first", 30, MonitoringSessionEndReason.USER_STOPPED)
        )
        val first = dao.get("first")
        assertEquals(30L, first?.endedAt)
        assertNull(first?.activeSlot)
        assertEquals(MonitoringSessionEndReason.USER_STOPPED, first?.endReason)

        dao.insertStart(openSession("recovered", "old-process", 40))
        assertEquals(1, dao.endOpenForRecovery("new-process", 50))
        val recovered = dao.get("recovered")
        assertEquals(50L, recovered?.endedAt)
        assertEquals(50L, recovered?.recoveredAt)
        assertNull(recovered?.activeSlot)
        assertEquals(MonitoringSessionEndReason.PROCESS_RECOVERY, recovered?.endReason)

        dao.insertStart(openSession("after-recovery", "new-process", 60))
        assertEquals(MonitoringSessionEntity.DATA_SYNC_SLOT, dao.get("after-recovery")?.activeSlot)
    }

    @Test
    fun `overlap query covers closed and open branches with half-open boundaries`() = runTest {
        val dao = database.monitoringSessionDao()
        listOf(
            closedSession("old", 10, 90),
            closedSession("ends-at-cutoff", 20, 100),
            closedSession("crosses-cutoff", 90, 110),
            closedSession("inside", 120, 150),
            closedSession("ends-after-now", 170, 220),
            openSession("open", "process", 130, active = false),
            openSession("starts-at-now", "process", 200, active = false),
            closedSession("future", 210, 230)
        ).forEach { dao.insertStart(it) }

        assertEquals(
            listOf("crosses-cutoff", "inside", "open", "ends-after-now"),
            dao.listOverlapping(cutoff = 100, now = 200).map { it.id }
        )
    }

    @Test
    fun `pruning removes only closed rows ending through the effective cutoff`() = runTest {
        val dao = database.monitoringSessionDao()
        listOf(
            closedSession("before", 10, 99),
            closedSession("boundary", 20, 100),
            closedSession("crossing", 90, 101),
            openSession("open", "process", 30, active = false)
        ).forEach { dao.insertStart(it) }

        assertEquals(2, dao.pruneClosedThrough(100))
        assertNull(dao.get("before"))
        assertNull(dao.get("boundary"))
        assertEquals(101L, dao.get("crossing")?.endedAt)
        assertNull(dao.get("open")?.endedAt)
    }

    private fun openSession(
        id: String,
        processId: String,
        startedAt: Long,
        active: Boolean = true
    ) = MonitoringSessionEntity(
        id = id,
        processSessionId = processId,
        startedAt = startedAt,
        activeSlot = if (active) MonitoringSessionEntity.DATA_SYNC_SLOT else null
    )

    private fun closedSession(id: String, startedAt: Long, endedAt: Long) =
        MonitoringSessionEntity(
            id = id,
            processSessionId = "closed-process",
            startedAt = startedAt,
            endedAt = endedAt,
            endReason = MonitoringSessionEndReason.SERVICE_DESTROYED
        )

    private suspend fun assertConstraint(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected SQLiteConstraintException, got $failure", failure is SQLiteConstraintException)
    }
}
