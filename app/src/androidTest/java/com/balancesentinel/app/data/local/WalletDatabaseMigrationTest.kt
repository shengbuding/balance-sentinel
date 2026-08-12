package com.balancesentinel.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(AndroidJUnit4::class)
class WalletDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WalletDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun freshRuntimeDatabaseMatchesCommittedExportedSchemaIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "wallet-schema-identity-${System.nanoTime()}"
        context.deleteDatabase(databaseName)
        val exportedIdentity = exportedSchemaIdentity()
        val database = Room.databaseBuilder(context, WalletDatabase::class.java, databaseName)
            .addMigrations(
                WalletDatabase.MIGRATION_1_2,
                WalletDatabase.MIGRATION_2_3,
                WalletDatabase.MIGRATION_3_4,
                WalletDatabase.MIGRATION_4_5
            )
            .build()
        try {
            val runtimeIdentity = database.openHelper.writableDatabase.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals(exportedIdentity, runtimeIdentity)
        } finally {
            database.close()
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationsReachTheCommittedExportedSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "wallet-migration-${System.nanoTime()}"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, 1).close()

        helper.runMigrationsAndValidate(
            databaseName,
            WalletDatabase.VERSION,
            true,
            WalletDatabase.MIGRATION_1_2,
            WalletDatabase.MIGRATION_2_3,
            WalletDatabase.MIGRATION_3_4,
            WalletDatabase.MIGRATION_4_5
        ).use { sqlite ->
            val identity = sqlite.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val exportedIdentity = exportedSchemaIdentity()
            assertEquals(exportedIdentity, identity)
        }
        context.deleteDatabase(databaseName)
    }

    private fun exportedSchemaIdentity(): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(
            "com.balancesentinel.app.data.local.WalletDatabase/${WalletDatabase.VERSION}.json"
        ).bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
                .getValue("database").jsonObject
                .getValue("identityHash").jsonPrimitive.content
        }
}
