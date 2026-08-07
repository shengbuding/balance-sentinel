package com.balancesentinel.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.balancesentinel.app.data.local.account.AccountDao
import com.balancesentinel.app.data.local.account.AccountEntity
import com.balancesentinel.app.data.local.history.BalanceRecordEntity
import com.balancesentinel.app.data.local.history.DailySummaryEntity
import com.balancesentinel.app.data.local.history.HistoryDao
import com.balancesentinel.app.data.local.log.EventLogDao
import com.balancesentinel.app.data.local.log.EventLogEntity
import com.balancesentinel.app.data.local.maintenance.MaintenanceCheckpointDao
import com.balancesentinel.app.data.local.maintenance.MaintenanceCheckpointEntity
import com.balancesentinel.app.data.local.metadata.AppMetadataDao
import com.balancesentinel.app.data.local.metadata.AppMetadataEntity
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionDao
import com.balancesentinel.app.data.local.monitoring.MonitoringSessionEntity
import com.balancesentinel.app.data.local.monitoring.MonitoringStateDao
import com.balancesentinel.app.data.local.monitoring.MonitoringStateEntity
import com.balancesentinel.app.data.local.mutation.MutationOperationDao
import com.balancesentinel.app.data.local.mutation.MutationOperationEntity
import com.balancesentinel.app.data.local.refresh.RefreshAccountResultEntity
import com.balancesentinel.app.data.local.refresh.RefreshRunDao
import com.balancesentinel.app.data.local.refresh.RefreshRunEntity
import com.balancesentinel.app.data.local.settings.AccountAlertSettingEntity
import com.balancesentinel.app.data.local.settings.AlertRuntimeStateEntity
import com.balancesentinel.app.data.local.settings.AppSettingsDao
import com.balancesentinel.app.data.local.settings.AppSettingsEntity
import com.balancesentinel.app.data.local.settings.NotificationWalletSelectionEntity
import com.balancesentinel.app.data.local.settings.SettingsDao
import com.balancesentinel.app.data.local.settings.SnoozeStateEntity
import com.balancesentinel.app.data.local.update.DownloadOperationDao
import com.balancesentinel.app.data.local.update.DownloadOperationEntity
import com.balancesentinel.app.data.local.usage.UsageDao
import com.balancesentinel.app.data.local.usage.UsageRecordEntity
import com.balancesentinel.app.data.local.usage.UsageSnapshotEntity

@Database(
    entities = [
        AccountEntity::class,
        MutationOperationEntity::class,
        AppMetadataEntity::class,
        AppSettingsEntity::class,
        AccountAlertSettingEntity::class,
        NotificationWalletSelectionEntity::class,
        AlertRuntimeStateEntity::class,
        SnoozeStateEntity::class,
        BalanceRecordEntity::class,
        DailySummaryEntity::class,
        UsageSnapshotEntity::class,
        UsageRecordEntity::class,
        EventLogEntity::class,
        RefreshRunEntity::class,
        RefreshAccountResultEntity::class,
        DownloadOperationEntity::class,
        MaintenanceCheckpointEntity::class,
        MonitoringStateEntity::class,
        MonitoringSessionEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun mutationOperationDao(): MutationOperationDao
    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun settingsDao(): SettingsDao
    abstract fun historyDao(): HistoryDao
    abstract fun usageDao(): UsageDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun refreshRunDao(): RefreshRunDao
    abstract fun downloadOperationDao(): DownloadOperationDao
    abstract fun maintenanceCheckpointDao(): MaintenanceCheckpointDao
    abstract fun monitoringStateDao(): MonitoringStateDao
    abstract fun monitoringSessionDao(): MonitoringSessionDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_balance_records_recorded_at_id` " +
                        "ON `balance_records` (`recorded_at`, `id`)"
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `balance_records` ADD COLUMN `migration_operation_id` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `balance_records` ADD COLUMN `migration_source_ordinal` INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `migration_operation_id` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `migration_source_ordinal` INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE `usage_snapshots` ADD COLUMN `migration_operation_id` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `usage_snapshots` ADD COLUMN `migration_source_ordinal` INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE `event_logs` ADD COLUMN `migration_operation_id` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `event_logs` ADD COLUMN `migration_source_ordinal` INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE `event_logs` ADD COLUMN `legacy_source_id` INTEGER DEFAULT NULL")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_balance_records_migration_operation_id_migration_source_ordinal` " +
                        "ON `balance_records` (`migration_operation_id`, `migration_source_ordinal`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_summaries_migration_operation_id_migration_source_ordinal` " +
                        "ON `daily_summaries` (`migration_operation_id`, `migration_source_ordinal`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_usage_snapshots_migration_operation_id_migration_source_ordinal` " +
                        "ON `usage_snapshots` (`migration_operation_id`, `migration_source_ordinal`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_logs_migration_operation_id_migration_source_ordinal` " +
                        "ON `event_logs` (`migration_operation_id`, `migration_source_ordinal`)"
                )
            }
        }
    }
}
