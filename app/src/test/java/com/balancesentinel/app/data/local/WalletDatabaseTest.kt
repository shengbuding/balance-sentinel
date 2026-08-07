package com.balancesentinel.app.data.local

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.balancesentinel.app.data.api.ProviderType
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.account.AccountState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletDatabaseTest {
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
    fun `runtime pragma schema is the literal v4 contract`() = runTest {
        assertEquals(EXPECTED_SCHEMA, database.pragmaSchemaSnapshot())
    }

    @Test
    fun `committed Room export is the exact v4 contract`() {
        val schemaFile = walletSchemaFile()
        val databaseJson = Json.parseToJsonElement(schemaFile.readText())
            .jsonObject.getValue("database").jsonObject

        assertEquals(4, databaseJson.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(
            "464f2403aa5bd84c3a352d1e49b4786c",
            databaseJson.getValue("identityHash").jsonPrimitive.content
        )
        assertEquals(EXPECTED_SCHEMA, exportedSchemaSnapshot(databaseJson))
    }

    @Test
    fun `migration 2 to 4 preserves legacy rows with unclaimed identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wallet-v2-to-v3-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE accounts (id TEXT PRIMARY KEY NOT NULL)")
                        db.execSQL(
                            "CREATE TABLE balance_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, total_balance REAL NOT NULL)"
                        )
                        db.execSQL(
                            """
                            CREATE TABLE daily_summaries (
                                date TEXT NOT NULL, account_id TEXT NOT NULL, currency TEXT NOT NULL,
                                open_balance REAL NOT NULL, close_balance REAL NOT NULL,
                                consumed_balance REAL NOT NULL, topped_up_balance REAL NOT NULL,
                                granted_balance REAL NOT NULL DEFAULT 0.0, average_balance REAL NOT NULL,
                                sample_count INTEGER NOT NULL,
                                topped_up_balance_close REAL NOT NULL DEFAULT 0.0,
                                granted_balance_close REAL NOT NULL DEFAULT 0.0,
                                generated_at INTEGER NOT NULL,
                                PRIMARY KEY(date, account_id, currency)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "CREATE TABLE usage_snapshots (id TEXT PRIMARY KEY NOT NULL, captured_at INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE event_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, message TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val sqlite = helper.writableDatabase
            sqlite.execSQL("INSERT INTO accounts(id) VALUES ('legacy')")
            sqlite.execSQL("INSERT INTO balance_records(total_balance) VALUES (12.5)")
            sqlite.execSQL(
                """
                INSERT INTO daily_summaries(
                    date, account_id, currency, open_balance, close_balance,
                    consumed_balance, topped_up_balance, granted_balance, average_balance,
                    sample_count, topped_up_balance_close, granted_balance_close, generated_at
                ) VALUES ('2026-08-03', 'legacy', 'USD', 9.5, 8.5, 1.0, 2.0, 3.0, 9.0, 4, 5.0, 6.0, 7)
                """.trimIndent()
            )
            sqlite.execSQL("INSERT INTO usage_snapshots(id, captured_at) VALUES ('usage-old', 33)")
            sqlite.execSQL("INSERT INTO event_logs(message) VALUES ('log-old')")

            WalletDatabase.MIGRATION_2_3.migrate(sqlite)
            WalletDatabase.MIGRATION_3_4.migrate(sqlite)

            assertEquals(12.5, queryDouble(sqlite, "SELECT total_balance FROM balance_records"), 0.0)
            assertEquals(8.5, queryDouble(sqlite, "SELECT close_balance FROM daily_summaries"), 0.0)
            assertEquals("", queryText(sqlite, "SELECT identity_discriminator FROM daily_summaries"))
            assertEquals("usage-old", queryText(sqlite, "SELECT id FROM usage_snapshots"))
            assertEquals("log-old", queryText(sqlite, "SELECT message FROM event_logs"))
            listOf("balance_records", "daily_summaries", "usage_snapshots", "event_logs").forEach { table ->
                sqlite.query(
                    "SELECT migration_operation_id, migration_source_ordinal FROM $table"
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.isNull(0))
                    assertTrue(cursor.isNull(1))
                }
            }
            sqlite.query("SELECT legacy_source_id FROM event_logs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migration 3 to 4 preserves normal and scoped summary identities`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wallet-v3-to-v4-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE accounts (id TEXT PRIMARY KEY NOT NULL)")
                        db.execSQL(
                            """
                            CREATE TABLE daily_summaries (
                                date TEXT NOT NULL, account_id TEXT NOT NULL, currency TEXT NOT NULL,
                                open_balance REAL NOT NULL, close_balance REAL NOT NULL,
                                consumed_balance REAL NOT NULL, topped_up_balance REAL NOT NULL,
                                granted_balance REAL NOT NULL DEFAULT 0.0, average_balance REAL NOT NULL,
                                sample_count INTEGER NOT NULL,
                                topped_up_balance_close REAL NOT NULL DEFAULT 0.0,
                                granted_balance_close REAL NOT NULL DEFAULT 0.0,
                                generated_at INTEGER NOT NULL,
                                migration_operation_id TEXT DEFAULT NULL,
                                migration_source_ordinal INTEGER DEFAULT NULL,
                                PRIMARY KEY(date, account_id, currency)
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val sqlite = helper.writableDatabase
            sqlite.execSQL("INSERT INTO accounts(id) VALUES ('legacy')")
            sqlite.execSQL(
                """
                INSERT INTO daily_summaries(
                    date, account_id, currency, open_balance, close_balance,
                    consumed_balance, topped_up_balance, granted_balance, average_balance,
                    sample_count, topped_up_balance_close, granted_balance_close, generated_at
                ) VALUES ('2026-08-03', 'legacy', 'USD', 9.5, 8.5, 1.0, 2.0, 3.0, 9.0, 4, 5.0, 6.0, 7)
                """.trimIndent()
            )
            sqlite.execSQL(
                """
                INSERT INTO daily_summaries(
                    date, account_id, currency, open_balance, close_balance,
                    consumed_balance, topped_up_balance, granted_balance, average_balance,
                    sample_count, topped_up_balance_close, granted_balance_close, generated_at,
                    migration_operation_id, migration_source_ordinal
                ) VALUES ('2026-08-04', 'legacy', 'USD', 19.5, 18.5, 11.0, 12.0, 13.0, 19.0, 14, 15.0, 16.0, 17, 'operation-3', 6)
                """.trimIndent()
            )

            WalletDatabase.MIGRATION_3_4.migrate(sqlite)

            assertEquals(2L, queryLong(sqlite, "SELECT COUNT(*) FROM daily_summaries"))
            assertEquals(
                "",
                queryText(sqlite, "SELECT identity_discriminator FROM daily_summaries WHERE date = '2026-08-03'")
            )
            assertEquals(
                "legacy|operation-3|6",
                queryText(sqlite, "SELECT identity_discriminator FROM daily_summaries WHERE date = '2026-08-04'")
            )
            assertEquals(
                18.5,
                queryDouble(sqlite, "SELECT close_balance FROM daily_summaries WHERE date = '2026-08-04'"),
                0.0
            )
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    companion object {
        private val EXPECTED_SCHEMA = """
            account_alert_settings|account_id:TEXT:1:<null>:1,currency:TEXT:1:<null>:2,balance_alert_enabled:INTEGER:1:0:0,change_alert_enabled:INTEGER:1:0:0||account_id->accounts(id):CASCADE:NO ACTION
            accounts|id:TEXT:1:<null>:1,display_order:INTEGER:1:<null>:0,label:TEXT:1:<null>:0,provider_type:TEXT:1:<null>:0,provider_config_json:TEXT:1:'{}':0,active_credential_generation:TEXT:1:<null>:0,revision:INTEGER:1:0:0,state:TEXT:1:'PENDING':0,legacy_storage_id:TEXT:0:NULL:0,created_at:INTEGER:1:<null>:0,updated_at:INTEGER:1:<null>:0|index_accounts_display_order:0:display_order,index_accounts_legacy_storage_id:1:legacy_storage_id|
            alert_runtime_state|account_id:TEXT:1:<null>:1,currency:TEXT:1:<null>:2,last_alerted_balance:REAL:0:NULL:0,anchor_balance:REAL:0:NULL:0,anchor_at:INTEGER:0:NULL:0,last_change_alerted_balance:REAL:0:NULL:0,last_change_alerted_at:INTEGER:0:NULL:0||account_id->accounts(id):CASCADE:NO ACTION
            app_metadata|id:INTEGER:1:0:1,local_revision:INTEGER:1:0:0,active_data_generation:TEXT:1:'LEGACY':0,legacy_migration_stage:TEXT:1:'NONE':0,updated_at:INTEGER:1:<null>:0||
            app_settings|id:INTEGER:1:0:1,background_refresh_interval_seconds:INTEGER:0:900:0,foreground_monitoring_interval_seconds:INTEGER:1:30:0,alert_enabled:INTEGER:1:0:0,alert_threshold:REAL:1:0.0:0,change_alert_enabled:INTEGER:1:0:0,change_alert_threshold:REAL:1:0.0:0,change_alert_period_minutes:INTEGER:1:0:0,log_max_entries:INTEGER:1:100:0,snooze_duration_minutes:INTEGER:1:60:0,show_total_balance_in_notification:INTEGER:1:1:0,updated_at:INTEGER:1:<null>:0||
            balance_records|id:INTEGER:1:<null>:1,account_id:TEXT:1:<null>:0,currency:TEXT:1:<null>:0,recorded_at:INTEGER:1:<null>:0,total_balance:REAL:1:<null>:0,granted_balance:REAL:1:0.0:0,topped_up_balance:REAL:1:0.0:0,source:TEXT:1:'REFRESH':0,migration_operation_id:TEXT:0:NULL:0,migration_source_ordinal:INTEGER:0:NULL:0|index_balance_records_account_id_currency_recorded_at_id:0:account_id+currency+recorded_at+id,index_balance_records_migration_operation_id_migration_source_ordinal:1:migration_operation_id+migration_source_ordinal,index_balance_records_recorded_at_id:0:recorded_at+id|account_id->accounts(id):CASCADE:NO ACTION
            daily_summaries|date:TEXT:1:<null>:1,account_id:TEXT:1:<null>:2,currency:TEXT:1:<null>:3,open_balance:REAL:1:<null>:0,close_balance:REAL:1:<null>:0,consumed_balance:REAL:1:<null>:0,topped_up_balance:REAL:1:<null>:0,granted_balance:REAL:1:0.0:0,average_balance:REAL:1:<null>:0,sample_count:INTEGER:1:<null>:0,topped_up_balance_close:REAL:1:0.0:0,granted_balance_close:REAL:1:0.0:0,generated_at:INTEGER:1:<null>:0,identity_discriminator:TEXT:1:'':4,migration_operation_id:TEXT:0:NULL:0,migration_source_ordinal:INTEGER:0:NULL:0|index_daily_summaries_account_id_currency_date:0:account_id+currency+date,index_daily_summaries_migration_operation_id_migration_source_ordinal:1:migration_operation_id+migration_source_ordinal|account_id->accounts(id):CASCADE:NO ACTION
            download_operations|id:TEXT:1:<null>:1,owner_id:TEXT:1:<null>:0,tag:TEXT:1:<null>:0,source_url:TEXT:1:<null>:0,temporary_path:TEXT:1:<null>:0,target_path:TEXT:1:<null>:0,state:TEXT:1:'QUEUED':0,downloaded_bytes:INTEGER:1:0:0,total_bytes:INTEGER:0:NULL:0,error_code:TEXT:0:NULL:0,error_message:TEXT:0:NULL:0,active_tag:TEXT:0:NULL:0,active_target_path:TEXT:0:NULL:0,created_at:INTEGER:1:<null>:0,updated_at:INTEGER:1:<null>:0,completed_at:INTEGER:0:NULL:0|index_download_operations_active_tag:1:active_tag,index_download_operations_active_target_path:1:active_target_path|
            event_logs|id:INTEGER:1:<null>:1,account_id:TEXT:0:NULL:0,refresh_run_id:TEXT:0:NULL:0,event_type:TEXT:1:<null>:0,total_balance_text:TEXT:1:'':0,currency_text:TEXT:1:'':0,is_available:INTEGER:1:0:0,granted_balance_text:TEXT:1:'':0,topped_up_balance_text:TEXT:1:'':0,recorded_at:INTEGER:1:<null>:0,message:TEXT:1:'':0,interval_seconds:INTEGER:0:NULL:0,expected_at:INTEGER:0:NULL:0,alarm_method:TEXT:0:NULL:0,miss_reason:TEXT:0:NULL:0,migration_operation_id:TEXT:0:NULL:0,migration_source_ordinal:INTEGER:0:NULL:0,legacy_source_id:INTEGER:0:NULL:0|index_event_logs_migration_operation_id_migration_source_ordinal:1:migration_operation_id+migration_source_ordinal,index_event_logs_recorded_at_id:0:recorded_at+id|account_id->accounts(id):CASCADE:NO ACTION,refresh_run_id->refresh_runs(id):SET NULL:NO ACTION
            maintenance_checkpoint|id:INTEGER:1:0:1,last_completed_date:TEXT:0:NULL:0,zone_id:TEXT:1:'UTC':0,last_success_at:INTEGER:0:NULL:0||
            monitoring_sessions|id:TEXT:1:<null>:1,process_session_id:TEXT:1:<null>:0,started_at:INTEGER:1:<null>:0,ended_at:INTEGER:0:NULL:0,active_slot:TEXT:0:NULL:0,end_reason:TEXT:0:NULL:0,recovered_at:INTEGER:0:NULL:0|index_monitoring_sessions_active_slot:1:active_slot,index_monitoring_sessions_ended_at_started_at:0:ended_at+started_at,index_monitoring_sessions_process_session_id_ended_at:0:process_session_id+ended_at|
            monitoring_state|id:INTEGER:1:0:1,desired:INTEGER:1:0:0,observed_state:TEXT:1:'STOPPED':0,process_session_id:TEXT:0:NULL:0,lease_expires_at:INTEGER:0:NULL:0,current_monitoring_session_id:TEXT:0:NULL:0,foreground_session_started_at:INTEGER:0:NULL:0,foreground_session_ended_at:INTEGER:0:NULL:0,last_user_foreground_reset_at:INTEGER:0:NULL:0,state_reason:TEXT:0:NULL:0,updated_at:INTEGER:1:<null>:0||current_monitoring_session_id->monitoring_sessions(id):SET NULL:NO ACTION
            mutation_operations|id:TEXT:1:<null>:1,operation_type:TEXT:1:<null>:0,stage:TEXT:1:'PREPARED':0,targets_json:TEXT:1:'[]':0,staged_generation_manifest_json:TEXT:1:'[]':0,manifest_version:INTEGER:1:1:0,batch_cursor:INTEGER:1:0:0,baseline_revision:INTEGER:1:<null>:0,error_code:TEXT:0:NULL:0,error_message:TEXT:0:NULL:0,created_at:INTEGER:1:<null>:0,updated_at:INTEGER:1:<null>:0,published_at:INTEGER:0:NULL:0,completed_at:INTEGER:0:NULL:0|index_mutation_operations_operation_type_stage:0:operation_type+stage,index_mutation_operations_stage_updated_at:0:stage+updated_at|
            notification_wallet_selections|account_id:TEXT:1:<null>:1,currency:TEXT:1:<null>:2,display_order:INTEGER:1:<null>:0|index_notification_wallet_selections_display_order:1:display_order|account_id->accounts(id):CASCADE:NO ACTION
            refresh_account_results|run_id:TEXT:1:<null>:1,account_id:TEXT:1:<null>:2,account_revision:INTEGER:1:<null>:0,state:TEXT:1:'RUNNING':0,error_category:TEXT:0:NULL:0,error_code:TEXT:0:NULL:0,retryable:INTEGER:1:0:0,retry_after_at:INTEGER:0:NULL:0,data_timestamp:INTEGER:0:NULL:0,stale:INTEGER:1:0:0,attempt_count:INTEGER:1:0:0,started_at:INTEGER:1:<null>:0,completed_at:INTEGER:0:NULL:0|index_refresh_account_results_account_id_completed_at:0:account_id+completed_at,index_refresh_account_results_run_id_state:0:run_id+state|run_id->refresh_runs(id):CASCADE:NO ACTION
            refresh_runs|id:TEXT:1:<null>:1,source:TEXT:1:<null>:0,owner_process_session_id:TEXT:0:NULL:0,state:TEXT:1:'RUNNING':0,started_at:INTEGER:1:<null>:0,completed_at:INTEGER:0:NULL:0,account_count:INTEGER:1:0:0,success_count:INTEGER:1:0:0,failure_count:INTEGER:1:0:0,cancelled_count:INTEGER:1:0:0,error_code:TEXT:0:NULL:0|index_refresh_runs_owner_process_session_id_state:0:owner_process_session_id+state,index_refresh_runs_state_started_at:0:state+started_at|
            snooze_state|account_id:TEXT:1:<null>:1,snoozed_until:INTEGER:1:<null>:0||account_id->accounts(id):CASCADE:NO ACTION
            usage_records|snapshot_id:TEXT:1:<null>:1,record_ordinal:INTEGER:1:<null>:2,model_name:TEXT:1:<null>:0,total_tokens:INTEGER:1:0:0,prompt_tokens:INTEGER:1:0:0,completion_tokens:INTEGER:1:0:0|index_usage_records_snapshot_id_model_name:0:snapshot_id+model_name|snapshot_id->usage_snapshots(id):CASCADE:NO ACTION
            usage_snapshots|id:TEXT:1:<null>:1,account_id:TEXT:1:<null>:0,captured_at:INTEGER:1:<null>:0,identity_discriminator:TEXT:1:'':0,migration_operation_id:TEXT:0:NULL:0,migration_source_ordinal:INTEGER:0:NULL:0|index_usage_snapshots_account_id_captured_at_identity_discriminator:1:account_id+captured_at+identity_discriminator,index_usage_snapshots_migration_operation_id_migration_source_ordinal:1:migration_operation_id+migration_source_ordinal|account_id->accounts(id):CASCADE:NO ACTION
        """.trimIndent()
    }
}

internal fun createWalletTestDatabase(): WalletDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java).build()
}

internal fun testAccount(
    id: String,
    displayOrder: Int = 0,
    revision: Long = 0,
    state: AccountState = AccountState.VERIFIED
): AccountEntity = AccountEntity(
    id = id,
    displayOrder = displayOrder,
    label = "Account $id",
    providerType = ProviderType.OPENAI,
    providerConfigJson = "{}",
    activeCredentialGeneration = "credential-$id",
    revision = revision,
    state = state,
    createdAt = 100,
    updatedAt = 100
)

internal suspend fun WalletDatabase.execSql(sql: String, args: Array<Any?> = emptyArray()) {
    withContext(Dispatchers.IO) {
        openHelper.writableDatabase.execSQL(sql, args)
    }
}

internal suspend fun WalletDatabase.queryLong(sql: String, args: Array<Any?> = emptyArray()): Long =
    withContext(Dispatchers.IO) {
        openHelper.writableDatabase.query(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

internal suspend fun WalletDatabase.queryString(
    sql: String,
    args: Array<Any?> = emptyArray()
): String? = withContext(Dispatchers.IO) {
    openHelper.writableDatabase.query(sql, args).use { cursor ->
        check(cursor.moveToFirst())
        if (cursor.isNull(0)) null else cursor.getString(0)
    }
}

private suspend fun WalletDatabase.pragmaSchemaSnapshot(): String = withContext(Dispatchers.IO) {
    val sqlite = openHelper.writableDatabase
    val tables = sqlite.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"
    ).use { cursor -> cursor.strings("name") }

    tables.joinToString("\n") { table ->
        val columns = sqlite.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    val defaultValue = if (cursor.isNull(defaultIndex)) {
                        "<null>"
                    } else {
                        cursor.getString(defaultIndex)
                    }
                    add(
                        "${cursor.string("name")}:${cursor.string("type")}:" +
                            "${cursor.int("notnull")}:$defaultValue:${cursor.int("pk")}"
                    )
                }
            }
        }.joinToString(",")
        val indexes = sqlite.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val originIndex = cursor.getColumnIndex("origin")
                    val isCreatedIndex = originIndex < 0 || cursor.getString(originIndex) == "c"
                    val name = cursor.string("name")
                    if (isCreatedIndex && !name.startsWith("sqlite_autoindex_")) {
                        val indexedColumns = sqlite.query("PRAGMA index_info(`$name`)").use {
                            indexCursor -> indexCursor.strings("name").joinToString("+")
                        }
                        add("$name:${cursor.int("unique")}:$indexedColumns")
                    }
                }
            }.sorted()
        }.joinToString(",")
        val foreignKeys = sqlite.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        "${cursor.string("from")}->${cursor.string("table")}" +
                            "(${cursor.string("to")}):${cursor.string("on_delete")}:" +
                            cursor.string("on_update")
                    )
                }
            }.sorted()
        }.joinToString(",")
        "$table|$columns|$indexes|$foreignKeys"
    }
}

private fun walletSchemaFile(): File {
    val root = File(System.getProperty("user.dir"))
    return listOf(
        File(root, "app/schemas/com.balancesentinel.app.data.local.WalletDatabase/4.json"),
        File(root, "schemas/com.balancesentinel.app.data.local.WalletDatabase/4.json")
    ).first { it.isFile }
}

private fun exportedSchemaSnapshot(databaseJson: kotlinx.serialization.json.JsonObject): String {
    return databaseJson.getValue("entities").jsonArray
        .sortedBy { it.jsonObject.getValue("tableName").jsonPrimitive.content }
        .joinToString("\n") { element ->
            val entity = element.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            val primaryKeyColumns = entity.getValue("primaryKey").jsonObject
                .getValue("columnNames").jsonArray
                .mapIndexed { index, value -> value.jsonPrimitive.content to index + 1 }
                .toMap()
            val columns = entity.getValue("fields").jsonArray.joinToString(",") { fieldElement ->
                val field = fieldElement.jsonObject
                val name = field.getValue("columnName").jsonPrimitive.content
                val default = field["defaultValue"]?.jsonPrimitive?.content ?: "<null>"
                "$name:${field.getValue("affinity").jsonPrimitive.content}:" +
                    "${if (field["notNull"]?.jsonPrimitive?.boolean == true) 1 else 0}:" +
                    "$default:${primaryKeyColumns[name] ?: 0}"
            }
            val indexes = (entity["indices"]?.jsonArray ?: JsonArray(emptyList()))
                .map { indexElement ->
                    val index = indexElement.jsonObject
                    val name = index.getValue("name").jsonPrimitive.content
                    val unique = if (index.getValue("unique").jsonPrimitive.boolean) 1 else 0
                    val names = index.getValue("columnNames").jsonArray
                        .joinToString("+") { it.jsonPrimitive.content }
                    "$name:$unique:$names"
                }
                .sorted()
                .joinToString(",")
            val foreignKeys = (entity["foreignKeys"]?.jsonArray ?: JsonArray(emptyList()))
                .map { foreignKeyElement ->
                    val foreignKey = foreignKeyElement.jsonObject
                    val from = foreignKey.getValue("columns").jsonArray
                        .joinToString("+") { it.jsonPrimitive.content }
                    val target = foreignKey.getValue("table").jsonPrimitive.content
                    val to = foreignKey.getValue("referencedColumns").jsonArray
                        .joinToString("+") { it.jsonPrimitive.content }
                    val onDelete = foreignKey.getValue("onDelete").jsonPrimitive.content
                    val onUpdate = foreignKey.getValue("onUpdate").jsonPrimitive.content
                    "$from->$target($to):$onDelete:$onUpdate"
                }
                .sorted()
                .joinToString(",")
            "$table|$columns|$indexes|$foreignKeys"
        }
}

private fun Cursor.strings(column: String): List<String> = buildList {
    val index = getColumnIndexOrThrow(column)
    while (moveToNext()) add(getString(index))
}

private fun Cursor.string(column: String): String = requireNotNull(getString(getColumnIndexOrThrow(column)))

private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

private fun queryDouble(database: SupportSQLiteDatabase, sql: String): Double =
    database.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getDouble(0)
    }

private fun queryLong(database: SupportSQLiteDatabase, sql: String): Long =
    database.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun queryText(database: SupportSQLiteDatabase, sql: String): String =
    database.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }
