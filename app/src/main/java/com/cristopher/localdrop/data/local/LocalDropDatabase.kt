package com.cristopher.localdrop.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transfer_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val size: Long,
    val timestamp: Long,
    val deviceName: String,
    val direction: String,
    val state: String,
    val error: String? = null,
    val sha256: String? = null
)

@Entity(tableName = "queued_transfers")
data class QueuedTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String = "",
    val uri: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val createdAt: Long,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val lastSeen: Long,
    val paired: Boolean = false,
    val publicKey: String? = null,
    val fingerprint: String? = null
)

@Entity(tableName = "local_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val deviceName: String,
    val port: Int,
    val defaultFolder: String?,
    val autoDiscovery: Boolean,
    val confirmIncoming: Boolean,
    val verifyIntegrity: Boolean = true
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC") fun observeAll(): Flow<List<HistoryEntity>>
    @Insert suspend fun insert(item: HistoryEntity)
    @Query("DELETE FROM transfer_history WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface TransferQueueDao {
    @Query("SELECT * FROM queued_transfers WHERE state = 'PENDING' ORDER BY createdAt ASC LIMIT 1") suspend fun nextPending(): QueuedTransferEntity?
    @Query("SELECT * FROM queued_transfers WHERE batchId = :batchId AND state = 'PENDING' ORDER BY createdAt ASC") suspend fun pendingBatch(batchId: String): List<QueuedTransferEntity>
    @Insert suspend fun insertAll(items: List<QueuedTransferEntity>)
    @Query("UPDATE queued_transfers SET state = 'PENDING', lastError = NULL WHERE state = 'RUNNING'") suspend fun resetRunning()
    @Query("UPDATE queued_transfers SET state = 'RUNNING', attempts = attempts + 1 WHERE id IN (:ids)") suspend fun markRunning(ids: List<Long>)
    @Query("UPDATE queued_transfers SET state = :state, lastError = :error WHERE id IN (:ids)") suspend fun markFinished(ids: List<Long>, state: String, error: String?)
    @Query("UPDATE queued_transfers SET state = 'PENDING', lastError = :error WHERE id IN (:ids)") suspend fun retry(ids: List<Long>, error: String)
    @Query("DELETE FROM queued_transfers WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM local_settings WHERE id = 1") fun observe(): Flow<SettingsEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(settings: SettingsEntity)
}

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY lastSeen DESC") fun observeAll(): Flow<List<PairedDeviceEntity>>
    @Query("SELECT * FROM paired_devices WHERE id = :id LIMIT 1") suspend fun findById(id: String): PairedDeviceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(device: PairedDeviceEntity)
    @Query("UPDATE paired_devices SET paired = 1, publicKey = :publicKey, fingerprint = :fingerprint WHERE id = :id") suspend fun markPaired(id: String, publicKey: String, fingerprint: String)
}

@Database(entities = [HistoryEntity::class, QueuedTransferEntity::class, PairedDeviceEntity::class, SettingsEntity::class], version = 5, exportSchema = false)
abstract class LocalDropDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun transferQueueDao(): TransferQueueDao
    abstract fun settingsDao(): SettingsDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transfer_history ADD COLUMN error TEXT")
                database.execSQL("ALTER TABLE transfer_history ADD COLUMN sha256 TEXT")
                database.execSQL("ALTER TABLE paired_devices ADD COLUMN paired INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE local_settings ADD COLUMN verifyIntegrity INTEGER NOT NULL DEFAULT 1")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE paired_devices ADD COLUMN publicKey TEXT")
                database.execSQL("ALTER TABLE paired_devices ADD COLUMN fingerprint TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS queued_transfers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uri TEXT NOT NULL, fileName TEXT NOT NULL, size INTEGER NOT NULL, mimeType TEXT NOT NULL, deviceId TEXT NOT NULL, deviceName TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, createdAt INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0, lastError TEXT)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) { database.execSQL("ALTER TABLE queued_transfers ADD COLUMN batchId TEXT NOT NULL DEFAULT ''") }
        }
        fun create(context: android.content.Context): LocalDropDatabase = Room.databaseBuilder(context, LocalDropDatabase::class.java, "localdrop.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).fallbackToDestructiveMigration().build()
    }
}
