package com.example.dubmusic

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    val fileName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isProcessed: Boolean = false,
    val isHidden: Boolean = false,
    val durationMs: Long = 0L // Добавили для подсчета времени!
)

// 1. Таблица Плейлистов
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Int = 0,
    val name: String,
    val imageUri: String? = null // Ссылка на картинку из галереи
)

// 2. Таблица связи (Трек <-> Плейлист)
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackUri"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["playlistId"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["uri"], childColumns = ["trackUri"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("trackUri")]
)
data class PlaylistTrackCrossRef(
    val playlistId: Int,
    val trackUri: String,
    val position: Int // Для сохранения порядка
)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTrack(track: TrackEntity)

    @Update
    fun updateTrack(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE isProcessed = 0 AND isHidden = 0")
    fun getUnprocessedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isProcessed = 1")
    fun getAllProcessedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isHidden = 1")
    fun getHiddenTracks(): Flow<List<TrackEntity>>

    // --- КОМАНДЫ ПЛЕЙЛИСТОВ ---
    @Insert
    fun insertPlaylist(playlist: PlaylistEntity): Long

    @Update
    fun updatePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrackIntoPlaylist(crossRef: PlaylistTrackCrossRef)

    // Достаем треки конкретного плейлиста, отсортированные по позиции
    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_tracks pt ON t.uri = pt.trackUri 
        WHERE pt.playlistId = :playlistId 
        ORDER BY pt.position ASC
    """)
    fun getTracksForPlaylist(playlistId: Int): Flow<List<TrackEntity>>

    //узнаём, в каких плейлистах есть песня
    @Query("SELECT playlistId FROM playlist_tracks WHERE trackUri = :trackUri")
    fun getPlaylistsForTrack(trackUri: String): Flow<List<Int>>

    // Получить связи (позиции) для конкретного плейлиста
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun getPlaylistTrackRefs(playlistId: Int): List<PlaylistTrackCrossRef>

    // Жесткое SQL-обновление позиции конкретного трека
    @Query("UPDATE playlist_tracks SET position = :newPos WHERE playlistId = :playlistId AND trackUri = :trackUri")
    fun updateTrackPosition(playlistId: Int, trackUri: String, newPos: Int)

    @Delete
    fun deletePlaylist(playlist: PlaylistEntity)
}

// 3. Обновляем версию БД до 2
@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}