package `in`.synthora.musicbox.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import `in`.synthora.musicbox.models.Track

/**
 * Local cache for the phone player: the restored queue, fetched lyrics and play counts.
 * Music, likes and playlists live in the YouTube account behind the server.
 *
 * Version 11 dropped the local library (downloads, local playlists and favourites) and the
 * obsolete provider columns; older databases are recreated.
 */
@Database(
    entities = [TrackEntity::class],
    version = 11,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Room entity for storing track data.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey
    val uuid: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,

    @ColumnInfo(name = "duration_sec")
    val durationSec: Int,

    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,

    @ColumnInfo(name = "yt_video_id")
    val ytVideoId: String? = null,

    @ColumnInfo(name = "synced_lyrics")
    val syncedLyrics: String? = null,

    @ColumnInfo(name = "plain_lyrics")
    val plainLyrics: String? = null,

    @ColumnInfo(name = "romanized_synced_lyrics")
    val romanizedSyncedLyrics: String? = null,

    @ColumnInfo(name = "romanized_plain_lyrics")
    val romanizedPlainLyrics: String? = null,

    @ColumnInfo(name = "is_favourite", defaultValue = "0")
    val isFavourite: Boolean = false,

    @ColumnInfo(name = "play_count", defaultValue = "0")
    val playCount: Int = 0,


    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: String? = null,

    @ColumnInfo(name = "album_id")
    val albumId: String? = null,

    @ColumnInfo(name = "artist_id")
    val artistId: String? = null,

    @ColumnInfo(name = "is_stream", defaultValue = "0")
    val isStream: Boolean = false,

    @ColumnInfo(name = "lyrics_offset_ms", defaultValue = "0")
    val lyricsOffsetMs: Long = 0L
)

/**
 * Extension function to convert TrackEntity to Track model.
 */
fun TrackEntity.toTrack(): Track {
    return Track(
        uuid = uuid,
        title = title,
        artist = artist,
        thumbnailUri = thumbnailUri,
        durationSec = durationSec,
        localUri = localUri,
        ytVideoId = ytVideoId,
        syncedLyrics = syncedLyrics,
        plainLyrics = plainLyrics,
        romanizedSyncedLyrics = romanizedSyncedLyrics,
        romanizedPlainLyrics = romanizedPlainLyrics,
        isFavourite = isFavourite,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        albumId = albumId,
        artistId = artistId,
        isStream = isStream,
        lyricsOffsetMs = lyricsOffsetMs
    )
}

/**
 * Extension function to convert Track model to TrackEntity.
 */
fun Track.toEntity(): TrackEntity {
    return TrackEntity(
        uuid = uuid,
        title = title,
        artist = artist,
        thumbnailUri = thumbnailUri,
        durationSec = durationSec,
        localUri = localUri,
        ytVideoId = ytVideoId,
        syncedLyrics = syncedLyrics,
        plainLyrics = plainLyrics,
        romanizedSyncedLyrics = romanizedSyncedLyrics,
        romanizedPlainLyrics = romanizedPlainLyrics,
        isFavourite = isFavourite,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        albumId = albumId,
        artistId = artistId,
        isStream = isStream,
        lyricsOffsetMs = lyricsOffsetMs
    )
}

/**
 * Data Access Object for Track operations.
 */
@Dao
interface TrackDao {

    @Upsert
    suspend fun insertTrack(track: TrackEntity)

    @Upsert
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE uuid = :uuid")
    suspend fun getTrackByUuid(uuid: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE uuid IN (:uuids)")
    suspend fun getTracksByUuids(uuids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE last_played_at IS NOT NULL ORDER BY last_played_at DESC LIMIT :limit")
    suspend fun getRecentlyPlayed(limit: Int = 10): List<TrackEntity>

    @Query("UPDATE tracks SET lyrics_offset_ms = :offsetMs WHERE uuid = :uuid")
    suspend fun updateLyricsOffset(uuid: String, offsetMs: Long)

    @Query("UPDATE tracks SET local_uri = :streamUrl WHERE uuid = :uuid")
    suspend fun updateTrackStreamUrl(uuid: String, streamUrl: String)

    @Query("UPDATE tracks SET play_count = play_count + 1, last_played_at = :lastPlayedAt WHERE uuid = :uuid")
    suspend fun incrementPlayCount(uuid: String, lastPlayedAt: String)

    @Query("DELETE FROM tracks WHERE uuid IN (:uuids)")
    suspend fun deleteTracks(uuids: List<String>)
}
