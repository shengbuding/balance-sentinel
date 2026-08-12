package com.balancesentinel.app.data.local

import android.content.Context
import androidx.room.Room

object WalletDatabaseProvider {
    private const val DATABASE_NAME = "wallet-sentinel.db"

    @Volatile
    private var instance: WalletDatabase? = null

    fun get(context: Context): WalletDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            WalletDatabase::class.java,
            DATABASE_NAME
        ).addMigrations(
            WalletDatabase.MIGRATION_1_2,
            WalletDatabase.MIGRATION_2_3,
            WalletDatabase.MIGRATION_3_4,
            WalletDatabase.MIGRATION_4_5
        )
            .build().also { instance = it }
    }

    internal fun installForTests(database: WalletDatabase) {
        instance = database
    }

    internal fun clearForTests() {
        instance?.close()
        instance = null
    }
}
