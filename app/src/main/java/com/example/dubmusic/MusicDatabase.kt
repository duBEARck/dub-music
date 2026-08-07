package com.example.dubmusic

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    val fileName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = "Неизвестный альбом",
    val year: Int? = null,
    val isProcessed: Boolean = false,
    val isHidden: Boolean = false,
    val durationMs: Long = 0L, // Для подсчета времени
    val isDemo: Boolean = false, // <--- флаг для демо-версий треков из альбома
    val isUnreleased: Boolean = false, // <--- флаг невыпущенного трека
    val albumOrder: Int = 0, // <--- НОВОЕ ПОЛЕ ДЛЯ ПОРЯДКА В АЛЬБОМЕ
    val dateAdded: Long = System.currentTimeMillis(), // для статистики
    val lyrics: String? = null // <--- текст песни
)

// --- НОВАЯ ТАБЛИЦА: ИСТОРИЯ ПРОСЛУШИВАНИЙ ---
@Entity(
    tableName = "listening_history",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["uri"], childColumns = ["trackUri"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("trackUri")]
)
data class ListeningHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val trackUri: String,
    val timestamp: Long = System.currentTimeMillis(), // Точное время, когда трек играл
    val durationPlayedMs: Long // Сколько миллисекунд он играл (для подсчета часов)
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
data class ArtistDaysStat(
    val artist: String,
    val daysCount: Int
)
data class TrackStat(
    @Embedded val track: TrackEntity,
    val statValue: Long
)

data class ArtistStat(
    val artist: String,
    val statValue: Long
)

data class HeatmapStat(
    val dateLabel: String,
    val totalMs: Long
)

data class TrackHistoryInfo(
    val trackArtist: String?,
    val timestamp: Long
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

    @Insert
    fun insertListeningHistory(history: ListeningHistoryEntity)

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

    // СТАТИСТИКА
    // 1. Тотал времени (сумма всех прослушанных миллисекунд за период)
    @Query("SELECT SUM(durationPlayedMs) FROM listening_history WHERE timestamp BETWEEN :startTime AND :endTime")
    fun getTotalListenTime(startTime: Long, endTime: Long): Flow<Long?>

    // 2. Сколько треков добавлено за период (СЧИТАЕМ ТОЛЬКО ОБРАБОТАННЫЕ!)
    @Query("SELECT COUNT(*) FROM tracks WHERE isProcessed = 1 AND dateAdded BETWEEN :startTime AND :endTime")
    fun getTracksAddedCount(startTime: Long, endTime: Long): Flow<Int>

    // 3. Топ 5 треков за период (сортируем по суммарному времени прослушивания)
    @Query("""
        SELECT t.*, SUM(h.durationPlayedMs) as statValue FROM tracks t
        INNER JOIN listening_history h ON t.uri = h.trackUri
        WHERE h.timestamp BETWEEN :startTime AND :endTime
        GROUP BY t.uri
        ORDER BY statValue DESC
        LIMIT 5
    """)
    fun getTopTracks(startTime: Long, endTime: Long): Flow<List<TrackStat>>

    // 4-5. Для правильного подсчета топа артистов в Kotlin (учитывая фиты)
    @Query("""
        SELECT t.*, SUM(h.durationPlayedMs) as statValue FROM tracks t
        INNER JOIN listening_history h ON t.uri = h.trackUri
        WHERE h.timestamp BETWEEN :startTime AND :endTime
        GROUP BY t.uri
    """)
    fun getAllTrackStatsForPeriod(startTime: Long, endTime: Long): Flow<List<TrackStat>>

    @Query("""
        SELECT t.*, COUNT(h.id) as statValue FROM tracks t
        INNER JOIN listening_history h ON t.uri = h.trackUri
        WHERE h.timestamp BETWEEN :startTime AND :endTime
        GROUP BY t.uri
    """)
    fun getAllTrackCountsForPeriod(startTime: Long, endTime: Long): Flow<List<TrackStat>>

    @Query("""
        SELECT t.artist as trackArtist, h.timestamp 
        FROM listening_history h
        INNER JOIN tracks t ON h.trackUri = t.uri
    """)
    fun getFullHistoryArtists(): Flow<List<TrackHistoryInfo>>

    // 6. ДИНАМИЧЕСКИЙ ТОП АРТИСТА (треки сортируются лично под пользователя, включая фиты)
    @Query("""
        SELECT t.* FROM tracks t
        LEFT JOIN listening_history h ON t.uri = h.trackUri
        WHERE t.artist LIKE '%' || :artistName || '%'
        GROUP BY t.uri
        ORDER BY SUM(COALESCE(h.durationPlayedMs, 0)) DESC
    """)
    fun getDynamicArtistTopTracks(artistName: String): Flow<List<TrackEntity>>

    // 7. Топ 5 треков по КОЛИЧЕСТВУ прослушиваний
    @Query("""
        SELECT t.*, COUNT(h.id) as statValue FROM tracks t
        INNER JOIN listening_history h ON t.uri = h.trackUri
        WHERE h.timestamp BETWEEN :startTime AND :endTime
        GROUP BY t.uri
        ORDER BY statValue DESC
        LIMIT 5
    """)
    fun getTopTracksByCount(startTime: Long, endTime: Long): Flow<List<TrackStat>>

    // 8. Топ 5 исполнителей по КОЛИЧЕСТВУ прослушиваний
    @Query("""
        SELECT t.artist, COUNT(h.id) as statValue FROM tracks t
        INNER JOIN listening_history h ON t.uri = h.trackUri
        WHERE h.timestamp BETWEEN :startTime AND :endTime AND t.artist IS NOT NULL
        GROUP BY t.artist
        ORDER BY statValue DESC
        LIMIT 5
    """)
    fun getTopArtistsByCount(startTime: Long, endTime: Long): Flow<List<ArtistStat>>

    // --- ТЕПЛОВАЯ КАРТА (Группировка по времени) ---
    @Query("""
        SELECT strftime('%d.%m.%Y', timestamp / 1000, 'unixepoch', 'localtime') as dateLabel, 
               SUM(durationPlayedMs) as totalMs
        FROM listening_history
        GROUP BY date(timestamp / 1000, 'unixepoch', 'localtime')
        ORDER BY timestamp DESC
    """)
    fun getHeatmapDays(): Flow<List<HeatmapStat>>

    @Query("""
        SELECT strftime('%W нед. %Y', timestamp / 1000, 'unixepoch', 'localtime') as dateLabel, 
               SUM(durationPlayedMs) as totalMs
        FROM listening_history
        GROUP BY strftime('%Y-%W', timestamp / 1000, 'unixepoch', 'localtime')
        ORDER BY timestamp DESC
    """)
    fun getHeatmapWeeks(): Flow<List<HeatmapStat>>

    @Query("""
        SELECT strftime('%m.%Y', timestamp / 1000, 'unixepoch', 'localtime') as dateLabel, 
               SUM(durationPlayedMs) as totalMs
        FROM listening_history
        GROUP BY strftime('%Y-%m', timestamp / 1000, 'unixepoch', 'localtime')
        ORDER BY timestamp DESC
    """)
    fun getHeatmapMonths(): Flow<List<HeatmapStat>>

    // Вытягиваем просто хронологический список того, что слушали
    @Query("SELECT trackUri FROM listening_history ORDER BY timestamp ASC")
    fun getHistoryUris(): Flow<List<String>>

    // Вспомогательная функция (используем Flow, чтобы обойти баг KSP)
    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    fun getTrackByUri(uri: String): Flow<TrackEntity?>

    @Delete
    fun deletePlaylist(playlist: PlaylistEntity)
}

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class, ListeningHistoryEntity::class], // Добавили сюда
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}