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
import kotlinx.coroutines.flow.first
import android.media.session.MediaSession
import android.media.session.PlaybackState
import kotlinx.coroutines.withContext

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
    var musicService: MusicService? = null
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
        if (playlist != null) {
            currentQueue = playlist
            currentQueueIndex = currentQueue.indexOf(track)
        } else {
            currentQueueIndex = currentQueue.indexOf(track)
        }

        progressJob?.cancel()

        // 1. Сначала подготавливаем плеер и запускаем музыку в сервисе
        musicService?.currentTrackItem = track // <--- Сохраняем объект в бессмертный Сервис!
        musicService?.playTrack(track.uri)

        musicService?.onTrackCompletion = {
            if (_playbackMode.value == PlaybackMode.NORMAL && currentQueueIndex >= currentQueue.size - 1) {
                _isPlaying.value = false
                _progress.value = 0f
                _currentTime.value = "0:00"
                progressJob?.cancel()
            } else {
                playNext()
            }
        }

        _currentTrack.value = track
        _isPlaying.value = true
        _totalTime.value = formatTime(musicService?.getDuration() ?: 0)

        startProgressUpdate()

        // 2. СРАЗУ собираем красивое уведомление со всеми метаданными
        musicService?.updateNotification(
            title = track.title ?: track.fileName,
            artist = track.artist ?: "Неизвестный исполнитель",
            isPlaying = true
        )

        // 3. И только теперь официально привязываем сервис к шторке
        val intent = android.content.Intent(getApplication(), MusicService::class.java)
        getApplication<android.app.Application>().startForegroundService(intent)
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
                    musicService?.pause()
                    _isPlaying.value = false
                }
            }
        }
    }

    fun playPrev() {
        if (currentQueue.isEmpty() || currentQueueIndex == -1) return

        val currentPosition = musicService?.getCurrentPosition() ?: 0
        // Если прошло больше 5 секунд - всегда начинаем заново, независимо от режима
        if (currentPosition > 5000) {
            _isPlaying.value = true      // 1. Сначала говорим, что музыка играет
            musicService?.resume()       // 2. Запускаем плеер
            seekTo(0f)                  // 3. Перематываем (это автоматически обновит шторку!)
        } else {
            // Прошло меньше 5 секунд -> переключаем на предыдущий
            when (_playbackMode.value) {
                PlaybackMode.REPEAT_ONE -> playTrack(currentQueue[currentQueueIndex])
                PlaybackMode.SHUFFLE -> {
                    val prevIndex = currentQueue.indices.random()
                    playTrack(currentQueue[prevIndex])
                }

                PlaybackMode.REPEAT_ALL -> {
                    val prevIndex =
                        if (currentQueueIndex > 0) currentQueueIndex - 1 else currentQueue.size - 1
                    playTrack(currentQueue[prevIndex])
                }

                PlaybackMode.NORMAL -> {
                    if (currentQueueIndex > 0) {
                        playTrack(currentQueue[currentQueueIndex - 1])
                    } else {
                        _isPlaying.value = true
                        musicService?.resume()
                        seekTo(0f)
                    }
                }
            }
        }
    }

        fun seekTo(fraction: Float) {
            val duration = musicService?.getDuration() ?: 0
            val newPosition = (duration * fraction).toInt()
            musicService?.seekTo(newPosition)
            _progress.value = fraction
            _currentTime.value = formatTime(newPosition)

            // Обновляем шторку, чтобы она узнала о перемещении во времени
            _currentTrack.value?.let { track ->
                musicService?.updateNotification(
                    title = track.title ?: track.fileName,
                    artist = track.artist ?: "Неизвестный исполнитель",
                    isPlaying = _isPlaying.value
                )
            }
        }

    private fun startProgressUpdate() {
        progressJob = viewModelScope.launch {
            while (true) {
                if (_isPlaying.value) {
                    val current = musicService?.getCurrentPosition() ?: 0
                    val total = musicService?.getDuration() ?: 1
                    _progress.value = if (total > 0) current.toFloat() / total else 0f
                    _currentTime.value = formatTime(current)
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun togglePlayback() {
        if (_isPlaying.value) {
            musicService?.pause()
            _isPlaying.value = false
        } else {
            musicService?.resume()
            _isPlaying.value = true
        }
        // Обновляем кнопку Play/Pause в шторке
        _currentTrack.value?.let { track ->
            musicService?.updateNotification(
                title = track.title ?: track.fileName,
                artist = track.artist ?: "Неизвестный исполнитель",
                isPlaying = _isPlaying.value
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deletePlaylist(playlist)
        }
    }

    fun playPlaylistDirectly(playlistId: Int) {
        viewModelScope.launch {
            // Берем актуальный список треков из базы (единоразово)
            val tracks = getTracksForPlaylist(playlistId).first()

            if (tracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    playTrack(tracks.first(), tracks) // Запускаем первую песню и передаем очередь
                }
            }
        }
    }

    fun syncWithService() {
        val service = musicService ?: return

        // Берем 100% готовый трек прямо из памяти Сервиса!
        val track = service.currentTrackItem ?: return

        _currentTrack.value = track
        _isPlaying.value = service.isPlaying()
        _totalTime.value = formatTime(service.getDuration())

        if (_isPlaying.value) {
            startProgressUpdate()
        }
    }
}