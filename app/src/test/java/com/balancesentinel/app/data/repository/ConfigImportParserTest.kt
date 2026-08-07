package com.balancesentinel.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.io.BoundedInput
import com.balancesentinel.app.data.local.WalletDatabase
import com.balancesentinel.app.data.local.createWalletTestDatabase
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.testAccount
import com.balancesentinel.app.data.model.AccountInfo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConfigImportParserTest {
    private val parser = ConfigImportParser()
    private lateinit var context: Context
    private lateinit var database: WalletDatabase
    private lateinit var repository: RoomSettingsRepository
    private lateinit var accountStorage: android.content.SharedPreferences
    private lateinit var accountManager: ApiKeyManager
    private lateinit var widgetPrefs: WidgetPrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = createWalletTestDatabase()
        repository = RoomSettingsRepository(database)
        accountStorage = context.getSharedPreferences("parser-pipeline-${System.nanoTime()}", Context.MODE_PRIVATE)
        accountManager = ApiKeyManager(context, accountStorage)
        widgetPrefs = WidgetPrefs(context)
        widgetPrefs.resetAll()
    }

    @After
    fun tearDown() {
        accountStorage.edit().clear().commit()
        widgetPrefs.resetAll()
        database.close()
    }

    @Test fun `bounded UTF-8 reader preserves a code point split across read chunks`() {
        val expected = "x".repeat(DEFAULT_BUFFER_SIZE - 1) + "\u20ac"

        val actual = BoundedInput.readUtf8(
            ByteArrayInputStream(expected.toByteArray(Charsets.UTF_8)),
            expected.toByteArray(Charsets.UTF_8).size
        )

        assertEquals(expected, actual)
    }

    @Test fun `exact limits are accepted`() {
        assertEquals(256, parser.parse(ByteArrayInputStream(validJson(accounts = 256))).accounts.size)
        assertEquals(16 * 1024, parser.parse(ByteArrayInputStream(validJson(accounts = 1, label = "x".repeat(16 * 1024)))).accounts.single().label.length)
        assertEquals(256 * 1024, parser.parse(ByteArrayInputStream(validJson(accounts = 1, script = "s".repeat(256 * 1024)))).accounts.single().usageScript!!.length)
        assertEquals(0, parser.parse(ByteArrayInputStream(validJson(depth = 30))).accounts.size)
    }

    @Test fun `each limit plus one is rejected`() {
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream(validJson(accounts = 257))) }
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream(validJson(accounts = 1, label = "x".repeat(16 * 1024 + 1)))) }
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream(validJson(accounts = 1, script = "s".repeat(256 * 1024 + 1)))) }
        assertThrows(BoundedInputLimitExceeded::class.java) { parser.parse(ByteArrayInputStream("[${"[".repeat(33)}0${"]".repeat(33)}]".toByteArray())) }
    }

    @Test
    fun `oversized imports leave credentials settings revision and accounts unchanged`() = runTest {
        val local = AccountInfo(
            id = accountManager.computeId("sk-parser-preimage"),
            label = "Local",
            apiKey = "sk-parser-preimage"
        )
        accountManager.replaceAll(listOf(local))
        database.accountDao().insertCreate(testAccount(local.id))
        val beforeSettings = SettingsSnapshot(
            appSettings = AppSettingsEntity(
                alertEnabled = false,
                alertThreshold = 3.0,
                updatedAt = 1
            )
        )
        repository.publishSnapshot(beforeSettings, publishedAt = 1)
        val beforeAccounts = accountManager.getAccounts()
        val beforeRevision = repository.currentRevision()
        val beforeCredentials = beforeAccounts.map { it.apiKey to it.extraCredentials }
        val planner = BackupImportPlanner(accountManager, widgetPrefs, repository)
        val cases = listOf(
            "accounts" to validJson(accounts = 257),
            "label" to validJson(accounts = 1, label = "x".repeat(16 * 1024 + 1)),
            "script" to validJson(accounts = 1, script = "s".repeat(256 * 1024 + 1)),
            "depth" to validJson(depth = 31)
        )

        for ((name, bytes) in cases) {
            val file = File(context.cacheDir, "parser-$name-${System.nanoTime()}.json")
            file.writeBytes(bytes)
            try {
                val config = ConfigManager.importFromUri(context, Uri.fromFile(file))
                if (config != null) {
                    val plan = planner.plan(config, accountManager.getAccounts(), ImportMode.MERGE)
                    planner.applyAsync(plan, confirmedFullReplace = false)
                }
                assertNull("$name should be rejected", config)
            } finally {
                file.delete()
            }
            assertEquals(beforeAccounts, accountManager.getAccounts())
            assertEquals(beforeCredentials, accountManager.getAccounts().map { it.apiKey to it.extraCredentials })
            assertEquals(beforeSettings, repository.readSnapshot())
            assertEquals(beforeRevision, repository.currentRevision())
        }
    }

    private fun validJson(
        accounts: Int = 0,
        label: String = "account",
        script: String? = null,
        depth: Int = 0
    ): ByteArray {
        val rows = (0 until accounts).joinToString(",") { i ->
            val key = "key-$i"
            val id = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            "{\"id\":\"$id\",\"label\":\"$label\",\"apiKey\":\"$key\"${script?.let { ",\"usageScript\":\"$it\"" } ?: ""}}"
        }
        val nested = if (depth == 0) "" else ",\"padding\":${"[".repeat(depth)}0${"]".repeat(depth)}"
        return "{\"version\":2$nested,\"credentialsIncluded\":true,\"exportedAt\":\"now\",\"appVersion\":\"1\",\"accounts\":[$rows],\"settings\":{\"refreshIntervalSeconds\":30,\"alertEnabled\":false,\"alertThreshold\":0,\"changeAlertEnabled\":false,\"changeAlertThreshold\":0,\"changeAlertPeriodMinutes\":0,\"logMaxEntries\":10}}".toByteArray()
    }
}
