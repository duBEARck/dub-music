package com.example.dubmusic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers // Убедись, что этот импорт есть
import kotlinx.coroutines.launch
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import android.media.MediaMetadataRetriever

enum class PlaybackMode {
    NORMAL, SHUFFLE, REPEAT_ALL, REPEAT_ONE
}
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    // Инициализируем базу данных
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "dubmusic-database"
    ).build()

    private val dao = db.trackDao()

    // Эти переменные экраны будут "слушать". Когда база изменится, интерфейс перерисуется сам.
    val unprocessedTracks = dao.getUnprocessedTracks()
    val processedTracks = dao.getAllProcessedTracks()

    // Функция добавления файлов из проводника
    fun addUnprocessedFile(uriString: String, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var duration = 0L
            try {
                // Запускаем системный сканер файла
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(getApplication(), Uri.parse(uriString))

                // Вытаскиваем длину трека
                val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = timeString?.toLong() ?: 0L

                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace() // Если файл битый, длина останется 0
            }

            // Сохраняем в базу ВМЕСТЕ С ДЛИНОЙ
            dao.insertTrack(TrackEntity(uri = uriString, fileName = fileName, durationMs = duration))
        }
    }

    fun processTrack(track: TrackEntity, title: String, artist: String, album: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedTrack = track.copy(
                title = title,
                artist = artist.ifBlank { "Неизвестный исполнитель" },
                album = album.ifBlank { null },
                isProcessed = true
            )
            dao.updateTrack(updatedTrack)
        }
    }

    fun hideTrack(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateTrack(track.copy(isHidden = true))
        }
    }

    // Получаем список всех скрытых треков
    val hiddenTracks = dao.getHiddenTracks()

    // Функция восстановления трека
    fun unhideTrack(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateTrack(track.copy(isHidden = false))
        }
    }

    // --- ЛОГИКА КАСТОМНЫХ ПЛЕЙЛИСТОВ ---
    val allPlaylists = dao.getAllPlaylists()

    fun createPlaylist(name: String, imageUri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertPlaylist(PlaylistEntity(name = name, imageUri = imageUri))
        }
    }

    fun updatePlaylist(playlist: PlaylistEntity, newName: String, newImageUri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updatePlaylist(playlist.copy(name = newName, imageUri = newImageUri))
        }
    }

    // Получить треки для конкретного плейлиста
    fun getTracksForPlaylist(playlistId: Int): Flow<List<TrackEntity>> {
        return dao.getTracksForPlaylist(playlistId)
    }
    // Получить плейлисты для конкретного трека
    fun getPlaylistsForTrack(trackUri: String): Flow<List<Int>> {
        return dao.getPlaylistsForTrack(trackUri)
    }

    // Вспомогательная функция для форматирования суммарного времени плейлиста
    fun formatTotalDuration(tracks: List<TrackEntity>): String {
        val totalMs = tracks.sumOf { it.durationMs }

        val totalSeconds = totalMs / 1000
        val minutes = totalSeconds / 60
        val hours = minutes / 60
        return if (hours > 0) "${hours} ч ${minutes % 60} мин" else "$minutes мин"
    }
    //Вспомогательная функция для форматироавния времени одного трека
    fun formatTrackDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
    fun addTrackToPlaylist(track: TrackEntity, playlist: PlaylistEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Выгружаем текущие связи, чтобы узнать позиции
            val currentRefs = dao.getPlaylistTrackRefs(playlist.playlistId)

            // Ищем самую минимальную позицию на данный момент
            val minPos = currentRefs.minOfOrNull { it.position } ?: 0

            val crossRef = PlaylistTrackCrossRef(
                playlistId = playlist.playlistId,
                trackUri = track.uri,
                position = minPos - 1 // Делаем значение меньше минимального, чтобы трек встал в самый верх
            )
            dao.insertTrackIntoPlaylist(crossRef)
        }
    }

    // НОВАЯ ФУНКЦИЯ ДЛЯ СМЕНЫ МЕСТ
    fun moveTrackInPlaylist(playlistId: Int, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val refs = dao.getPlaylistTrackRefs(playlistId) // Они уже отсортированы ASC
            if (fromIndex in refs.indices && toIndex in refs.indices) {
                val item1 = refs[fromIndex]
                val item2 = refs[toIndex]

                // Жестко принудительно меняем позиции через SQL
                dao.updateTrackPosition(playlistId, item1.trackUri, item2.position)
                dao.updateTrackPosition(playlistId, item2.trackUri, item1.position)
            }
        }
    }

    // --- ЛОГИКА ПЛЕЕРА ---
    private var currentQueue: List<TrackEntity> = emptyList()
    private var currentQueueIndex: Int = -1
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null // Для фонового таймера прогресса

    private val _currentTrack = MutableStateFlow<TrackEntity?>(null)
    val currentTrack: StateFlow<TrackEntity?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    //режимы воспроизведения (случайный порядок и прочие)
    private val _playbackMode = MutableStateFlow(PlaybackMode.NORMAL)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    fun togglePlaybackMode(mode: PlaybackMode) {
        // Если нажали на ту же кнопку второй раз — отключаем режим (возвращаем NORMAL)
        if (_playbackMode.value == mode) {
            _playbackMode.value = PlaybackMode.NORMAL
        } else {
            // Иначе включаем выбранный режим (он автоматически заменит предыдущий)
            _playbackMode.value = mode
        }
    }
    // Строки для отображения времени (например, "1:05")
    private val _currentTime = MutableStateFlow("0:00")
    val currentTime: StateFlow<String> = _currentTime.asStateFlow()

    private val _totalTime = MutableStateFlow("0:00")
    val totalTime: StateFlow<String> = _totalTime.asStateFlow()

    // Вспомогательная функция: переводит миллисекунды в красивую строку
    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    // Прогресс трека от 0.0 до 1.0
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    fun playTrack(track: TrackEntity, playlist: List<TrackEntity>? = null) {
        // Запоминаем плейлист, если его передали
        if (playlist != null) {
            currentQueue = playlist
            currentQueueIndex = currentQueue.indexOf(track)
        } else {
            // Если не передали (например, нажали кнопку "следующий"), ищем позицию в текущем списке
            currentQueueIndex = currentQueue.indexOf(track)
        }

        // Очищаем старый плеер
        mediaPlayer?.release()
        progressJob?.cancel()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(getApplication(), Uri.parse(track.uri))
                prepare()
                start()
            }

            // Обновляем состояния
            _currentTrack.value = track
            _isPlaying.value = true
            _totalTime.value = formatTime(mediaPlayer?.duration ?: 0)

            startProgressUpdate()

            // Слушатель окончания трека
            mediaPlayer?.setOnCompletionListener {
                // Останавливаем ТОЛЬКО если режим NORMAL и это последняя песня
                if (_playbackMode.value == PlaybackMode.NORMAL && currentQueueIndex >= currentQueue.size - 1) {
                    _isPlaying.value = false
                    _progress.value = 0f
                    _currentTime.value = "0:00"
                    progressJob?.cancel()
                } else {
                    // Во всех остальных случаях просто вызываем playNext (он сам разберется по режиму)
                    playNext()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }


    }

    fun playNext() {
        if (currentQueue.isEmpty() || currentQueueIndex == -1) return

        when (_playbackMode.value) {
            PlaybackMode.REPEAT_ONE -> {
                // Запускаем тот же самый трек
                playTrack(currentQueue[currentQueueIndex])
            }
            PlaybackMode.SHUFFLE -> {
                // Выбираем случайную позицию из списка
                val nextIndex = currentQueue.indices.random()
                playTrack(currentQueue[nextIndex])
            }
            PlaybackMode.REPEAT_ALL -> {
                // Если дошли до конца, перекидываем на 0
                val nextIndex = if (currentQueueIndex < currentQueue.size - 1) currentQueueIndex + 1 else 0
                playTrack(currentQueue[nextIndex])
            }
            PlaybackMode.NORMAL -> {
                // Играем следующий или останавливаем, если это был последний
                if (currentQueueIndex < currentQueue.size - 1) {
                    playTrack(currentQueue[currentQueueIndex + 1])
                } else {
                    mediaPlayer?.pause()
                    _isPlaying.value = false
                }
            }
        }
    }

    fun playPrev() {
        if (currentQueue.isEmpty() || currentQueueIndex == -1) return

        val currentPosition = mediaPlayer?.currentPosition ?: 0
        // Если прошло больше 5 секунд - всегда начинаем заново, независимо от режима
        if (currentPosition > 5000) {
            seekTo(0f)
            mediaPlayer?.start()
            _isPlaying.value = true
        } else {
            when (_playbackMode.value) {
                PlaybackMode.REPEAT_ONE -> playTrack(currentQueue[currentQueueIndex])
                PlaybackMode.SHUFFLE -> {
                    val prevIndex = currentQueue.indices.random()
                    playTrack(currentQueue[prevIndex])
                }
                PlaybackMode.REPEAT_ALL -> {
                    // Если мы на 0, перекидываем в самый конец
                    val prevIndex = if (currentQueueIndex > 0) currentQueueIndex - 1 else currentQueue.size - 1
                    playTrack(currentQueue[prevIndex])
                }
                PlaybackMode.NORMAL -> {
                    if (currentQueueIndex > 0) {
                        playTrack(currentQueue[currentQueueIndex - 1])
                    } else {
                        seekTo(0f)
                        mediaPlayer?.start()
                        _isPlaying.value = true
                    }
                }
            }
        }
    }

    fun seekTo(fraction: Float) {
        mediaPlayer?.let {
            val newPosition = (it.duration * fraction).toInt()
            it.seekTo(newPosition)
            _progress.value = fraction
            _currentTime.value = formatTime(newPosition) // Обновляем время при перемотке
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying && it.duration > 0) {
                            val pos = it.currentPosition
                            _progress.value = pos.toFloat() / it.duration.toFloat()
                            _currentTime.value = formatTime(pos) // Таймер тикает
                        }
                    } catch (e: Exception) { /* Игнорируем скачки */ }
                }
                delay(500)
            }
        }
    }

    fun togglePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                progressJob?.cancel() // Останавливаем таймер
            } else {
                it.start()
                _isPlaying.value = true
                startProgressUpdate() // Возобновляем таймер
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        progressJob?.cancel()
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deletePlaylist(playlist)
        }
    }
}