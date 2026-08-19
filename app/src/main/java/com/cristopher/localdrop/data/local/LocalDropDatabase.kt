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

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val lastSeen: Long,
    val paired: Boolean = false
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
interface SettingsDao {
    @Query("SELECT * FROM local_settings WHERE id = 1") fun observe(): Flow<SettingsEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(settings: SettingsEntity)
}

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY lastSeen DESC") fun observeAll(): Flow<List<PairedDeviceEntity>>
    @Query("SELECT * FROM paired_devices WHERE id = :id LIMIT 1") suspend fun findById(id: String): PairedDeviceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(device: PairedDeviceEntity)
    @Query("UPDATE paired_devices SET paired = 1 WHERE id = :id") suspend fun markPaired(id: String)
}

@Database(entities = [HistoryEntity::class, PairedDeviceEntity::class, SettingsEntity::class], version = 2, exportSchema = false)
abstract class LocalDropDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
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
        fun create(context: android.content.Context): LocalDropDatabase = Room.databaseBuilder(context, LocalDropDatabase::class.java, "localdrop.db")
            .addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build()
    }
}
