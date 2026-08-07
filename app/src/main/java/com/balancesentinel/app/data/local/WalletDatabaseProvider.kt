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
        ).addMigrations(WalletDatabase.MIGRATION_1_2)
            .build().also { instance = it }
    }
}
