package com.balancesentinel.app.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WalletDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun exportedSchemaIdentityMatchesRuntimeDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "wallet-schema-identity-${System.nanoTime()}"
        context.deleteDatabase(databaseName)
        helper.createDatabase(databaseName, WalletDatabase.VERSION).use { sqlite ->
            val identity = sqlite.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals(WalletDatabase.SCHEMA_IDENTITY_HASH, identity)
        }
        context.deleteDatabase(databaseName)
    }
}
