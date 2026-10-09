package `in`.synthora.musicbox.database

import android.content.Context
import androidx.room.*

@Entity(tableName = "download_manifest", primaryKeys = ["kind", "key"])
data class DownloadManifestRow(val kind: String, val key: String, val state: String, val payload: String)
@Entity(tableName = "download_migration")
data class DownloadMigration(@PrimaryKey val id: Int = 1)
@Dao
interface DownloadManifestDao {
    @Query("SELECT * FROM download_manifest") suspend fun read(): List<DownloadManifestRow>
    @Query("SELECT COUNT(*) FROM download_migration") suspend fun migrated(): Int
    @Query("DELETE FROM download_manifest WHERE kind = :kind AND `key` = :key") suspend fun remove(kind: String, key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(rows: List<DownloadManifestRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markMigrated(row: DownloadMigration)
    @Transaction suspend fun replace(rows: List<DownloadManifestRow>) {
        val existing = read().associateBy { it.kind to it.key }
        val wanted = rows.associateBy { it.kind to it.key }
        (existing.keys - wanted.keys).forEach { remove(it.first, it.second) }
        insert(rows.filter { existing[it.kind to it.key] != it })
        markMigrated(DownloadMigration())
    }
}

/** Isolated database avoids destructive migrations of the existing music/lyrics database. */
@Database(entities = [DownloadManifestRow::class, DownloadMigration::class], version = 1, exportSchema = false)
abstract class DownloadManifestDatabase : RoomDatabase() {
    abstract fun manifests(): DownloadManifestDao
    companion object {
        @Volatile private var instance: DownloadManifestDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                DownloadManifestDatabase::class.java, "download_manifests.db").build().also { instance = it }
        }
    }
}
