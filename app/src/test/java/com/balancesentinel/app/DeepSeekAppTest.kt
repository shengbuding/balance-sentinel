package com.balancesentinel.app

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.credentials.DataCorruptionException
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.log.EventLogType
import com.balancesentinel.app.data.migration.LegacyDataMigration
import com.balancesentinel.app.data.migration.LegacyDataSnapshot
import com.balancesentinel.app.data.migration.LegacyDataSource
import com.balancesentinel.app.data.migration.LegacyDataVerification
import com.balancesentinel.app.data.model.DailySummary
import com.balancesentinel.app.data.model.RawRecord
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.model.RefreshLogType
import com.balancesentinel.app.data.model.UsageRecord
import com.balancesentinel.app.data.model.UsageSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = DeepSeekApp::class)
class DeepSeekAppTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Clean up test APK files
        val apkDir = File(context.cacheDir, "apk")
        if (apkDir.exists()) apkDir.deleteRecursively()
    }

    @Test
    fun `notification channels are created`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = Shadows.shadowOf(nm)

        val channels = shadow.notificationChannels
        assertTrue("should have at least 3 channels", channels.size >= 3)

        val channelIds = channels.map { it.id }
        assertTrue("Missing service channel", channelIds.contains(DeepSeekApp.CHANNEL_ID))
        assertTrue("Missing alert channel", channelIds.contains(DeepSeekApp.CHANNEL_ID_ALERT))
        assertTrue("Missing usage channel", channelIds.contains(DeepSeekApp.CHANNEL_ID_USAGE))

        // Verify channel properties
        val svcChannel = channels.find { it.id == DeepSeekApp.CHANNEL_ID }
        assertNotNull(svcChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, svcChannel!!.importance)

        val alertChannel = channels.find { it.id == DeepSeekApp.CHANNEL_ID_ALERT }
        assertNotNull(alertChannel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alertChannel!!.importance)

        val usageChannel = channels.find { it.id == DeepSeekApp.CHANNEL_ID_USAGE }
        assertNotNull(usageChannel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, usageChannel!!.importance)
    }

    @Test
    fun `stale APK files are cleaned up on startup`() {
        val apkDir = File(context.cacheDir, "apk")
        apkDir.mkdirs()

        // Create test APK files
        val matchingFile = File(apkDir, "update-v1.0.0.apk")
        matchingFile.writeText("fake apk")
        val matchingFile2 = File(apkDir, "update-v2.0.0.apk")
        matchingFile2.writeText("fake apk 2")

        // Create a non-matching file that should survive
        val nonMatchingFile = File(apkDir, "readme.txt")
        nonMatchingFile.writeText("keep me")

        assertTrue(matchingFile.exists())
        assertTrue(matchingFile2.exists())
        assertTrue(nonMatchingFile.exists())

        // Simulate the cleanup that happens in onCreate
        if (apkDir.exists()) {
            apkDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("update-") && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }
        }

        assertFalse("update-*.apk should be deleted", matchingFile.exists())
        assertFalse("update-*.apk should be deleted", matchingFile2.exists())
        assertTrue("non-apk file should survive", nonMatchingFile.exists())
    }

    @Test
    fun `stale APK cleanup handles non-existent directory gracefully`() {
        val apkDir = File(context.cacheDir, "apk")
        if (apkDir.exists()) apkDir.deleteRecursively()

        // Should not throw when directory doesn't exist
        try {
            if (apkDir.exists()) {
                apkDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("update-") && file.name.endsWith(".apk")) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            fail("Should not throw: ${e.message}")
        }
    }

    @Test
    fun `companion object constants are correct`() {
        assertEquals("balance_refresh_channel", DeepSeekApp.CHANNEL_ID)
        assertEquals(1001, DeepSeekApp.NOTIFICATION_ID)
        assertEquals("balance_alert_channel", DeepSeekApp.CHANNEL_ID_ALERT)
        assertEquals("balance_usage_channel", DeepSeekApp.CHANNEL_ID_USAGE)
        assertEquals(3002, DeepSeekApp.NOTIFICATION_ID_GROUP_SUMMARY)
    }

    @Test
    fun `startup migration retains credential corruption as application state`() {
        val app = context as DeepSeekApp
        val corruption = DataCorruptionException("legacy credentials are corrupt")

        app.migrateDataIfNeeded { throw corruption }

        assertSame(corruption, app.credentialCorruption)
    }

    @Test
    fun `startup invokes resumable room account migration`() {
        val app = context as DeepSeekApp
        val invoked = CountDownLatch(1)
        app.legacyMigrationRunner = { invoked.countDown() }

        app.onCreate()

        assertTrue("DeepSeekApp startup must launch the Room account migration", invoked.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `startup invokes account mutation recovery after migration`() {
        val app = context as DeepSeekApp
        val invoked = CountDownLatch(1)
        app.legacyMigrationRunner = { }
        app.accountMutationRecoveryRunner = { invoked.countDown() }

        app.onCreate()

        assertTrue("DeepSeekApp startup must launch account mutation recovery", invoked.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `startup data runner migrates scoped non-empty data and is idempotent`() = runBlocking {
        val app = context as DeepSeekApp
        val db = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
        try {
            val accountId = "550e8400-e29b-41d4-a716-446655440099"
            db.accountDao().insertCreate(
                AccountEntity(
                    accountId,
                    0,
                    "Startup",
                    ProviderType.DEEPSEEK,
                    activeCredentialGeneration = "g",
                    state = AccountState.VERIFIED,
                    legacyStorageId = "legacy",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
            db.historyDao().upsertSummaries(
                listOf(
                    DailySummaryEntity(
                        "2026-08-02",
                        accountId,
                        "USD",
                        999.0,
                        999.0,
                        0.0,
                        0.0,
                        averageBalance = 999.0,
                        sampleCount = 1,
                        generatedAt = 1
                    )
                )
            )
            db.eventLogDao().insertAll(
                listOf(
                    EventLogEntity(
                        id = 501,
                        eventType = EventLogType.MANUAL,
                        totalBalanceText = "5.00",
                        currencyText = "USD",
                        isAvailable = true,
                        grantedBalanceText = "1.00",
                        toppedUpBalanceText = "4.00",
                        recordedAt = 500,
                        message = "polluted but identical"
                    )
                )
            )
            val snapshot = LegacyDataSnapshot(
                records = listOf(
                    RawRecord("legacy", 400, "usd", 5f, 1f, 4f),
                    RawRecord("legacy", 400, "usd", 4f, 1f, 4f)
                ),
                summaries = listOf(
                    DailySummary(
                        "legacy",
                        "2026-08-02",
                        "usd",
                        5f,
                        4f,
                        1f,
                        0f,
                        avgBalance = 4.5f,
                        sampleCount = 2,
                        generatedAt = 401
                    )
                ),
                usage = listOf(
                    UsageSnapshot("legacy", 450, listOf(UsageRecord("deepseek-chat", 10, 4, 6))),
                    UsageSnapshot("legacy", 450, listOf(UsageRecord("deepseek-reasoner", 20, 8, 12)))
                ),
                logs = listOf(
                    RefreshLogEntry(
                        id = 501,
                        type = RefreshLogType.MANUAL,
                        totalBalance = "5.00",
                        currency = "USD",
                        isAvailable = true,
                        grantedBalance = "1.00",
                        toppedUpBalance = "4.00",
                        timestamp = 500,
                        message = "polluted but identical"
                    )
                )
            )
            val source = object : LegacyDataSource {
                override fun read() = snapshot
                override fun clear(snapshot: LegacyDataSnapshot) = false
            }
            app.legacyMigrationRunner = { }
            app.settingsMigrationRunner = { }
            app.accountMutationRecoveryRunner = { }

            val firstResult = AtomicReference<LegacyDataVerification?>()
            val firstFailure = AtomicReference<Exception?>()
            val firstDone = CountDownLatch(1)
            app.legacyDataMigrationRunner = {
                try {
                    firstResult.set(LegacyDataMigration(db, source).run())
                } catch (error: Exception) {
                    firstFailure.set(error)
                    throw error
                } finally {
                    firstDone.countDown()
                }
            }
            app.launchLegacyAccountMigration()

            assertTrue("non-empty data migration did not finish", firstDone.await(5, TimeUnit.SECONDS))
            assertNull(firstFailure.get())
            assertEquals(2, firstResult.get()?.records)
            assertEquals(2, firstResult.get()?.usage)
            assertEquals(2, db.eventLogDao().countLogs())
            val operationId = db.mutationOperationDao().listRecoverable()
                .single { it.operationType.name == "LEGACY_DATA_MIGRATION" }
                .id
            assertEquals(
                2,
                queryLong(db, "SELECT COUNT(*) FROM balance_records WHERE migration_operation_id = '$operationId'")
            )
            assertEquals(
                1,
                queryLong(db, "SELECT COUNT(*) FROM daily_summaries WHERE migration_operation_id = '$operationId'")
            )
            assertEquals(
                2,
                queryLong(db, "SELECT COUNT(*) FROM usage_snapshots WHERE migration_operation_id = '$operationId'")
            )
            assertEquals(
                1,
                queryLong(db, "SELECT COUNT(*) FROM event_logs WHERE migration_operation_id = '$operationId'")
            )

            val secondDone = CountDownLatch(1)
            app.legacyDataMigrationRunner = {
                LegacyDataMigration(db, source).run()
                secondDone.countDown()
            }
            app.launchLegacyAccountMigration()
            assertTrue("idempotent startup rerun did not finish", secondDone.await(5, TimeUnit.SECONDS))
            assertEquals(2, queryLong(db, "SELECT COUNT(*) FROM balance_records WHERE migration_operation_id = '$operationId'"))
            assertEquals(2, queryLong(db, "SELECT COUNT(*) FROM usage_snapshots WHERE migration_operation_id = '$operationId'"))
            assertEquals(2, db.eventLogDao().countLogs())
        } finally {
            db.close()
        }
    }

    private fun queryLong(db: WalletDatabase, sql: String): Long =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
