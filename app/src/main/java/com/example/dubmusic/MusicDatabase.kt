package com.example.dubmusic

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// 1. Описываем структуру таблицы (Сущность)
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String, // Системный путь будет уникальным ID
    val fileName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null, // Если null или пусто - это сингл
    val isProcessed: Boolean = false, // Обработан ли трек?
    val isHidden: Boolean = false     // Скрыт ли трек пользователем?
)

// 2. Описываем команды для базы данных (DAO)

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTrack(track: TrackEntity) // Без suspend!

    @Update
    fun updateTrack(track: TrackEntity) // Без suspend!

    @Query("SELECT * FROM tracks WHERE isProcessed = 0 AND isHidden = 0")
    fun getUnprocessedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isProcessed = 1")
    fun getAllProcessedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isHidden = 1")
    fun getHiddenTracks(): Flow<List<TrackEntity>>
}

// 3. Сама База Данных
@Database(entities = [TrackEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}