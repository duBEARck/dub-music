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
import java.io.File
import java.io.FileOutputStream
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

    // Постоянное хранилище настроек Android (не стирается при закрытии приложения) для фото исполнителя
    private val prefs = getApplication<Application>().getSharedPreferences("artist_photos", android.content.Context.MODE_PRIVATE)
    private var cachedTracks: List<TrackEntity> = emptyList()

    // --- ГЛОБАЛЬНАЯ ПАМЯТЬ ДЛЯ ПЕРЕХОДОВ ИЗ ПЛЕЕРА ---
    private val _openedArtistName = MutableStateFlow<String?>(null)
    val openedArtistName: StateFlow<String?> = _openedArtistName.asStateFlow()

    private val _openedAlbumTitle = MutableStateFlow<String?>(null)
    val openedAlbumTitle: StateFlow<String?> = _openedAlbumTitle.asStateFlow()

    fun openArtist(name: String?) { _openedArtistName.value = name }
    fun openAlbum(title: String?) { _openedAlbumTitle.value = title }

    init {
        // Запускаем сборку библиотеки сразу при создании ViewModel и при любых изменениях в базе
        viewModelScope.launch {
            processedTracks.collect { tracks ->
                cachedTracks = tracks
                buildLibrary(tracks)
            }
        }
    }

    // Функция добавления файлов из проводника
    fun addUnprocessedFile(uriString: String, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var duration = 0L
            var extractedTitle: String? = null
            var extractedArtist: String? = null
            var extractedAlbum: String? = null
            var extractedYear: Int? = null

            try {
                // Запускаем системный сканер файла
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(getApplication(), Uri.parse(uriString))

                // Вытаскиваем длину трека
                val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = timeString?.toLong() ?: 0L

                // --- НОВОЕ: Сразу вытаскиваем все метаданные ---
                extractedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                extractedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                extractedAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val yearString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                extractedYear = yearString?.toIntOrNull() // Превращаем текст в число

                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace() // Если файл битый, длина останется 0
            }

            // Сохраняем в базу ВСЁ ВМЕСТЕ
            dao.insertTrack(
                TrackEntity(
                    uri = uriString,
                    fileName = fileName,
                    durationMs = duration,
                    title = extractedTitle,
                    artist = extractedArtist,
                    album = extractedAlbum,
                    year = extractedYear,
                    isDemo = false
                )
            )
        }
    }
    fun setArtistPhoto(artistName: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                // Создаем папку artist_photos во внутренней памяти приложения
                val photosDir = File(context.filesDir, "artist_photos")
                if (!photosDir.exists()) photosDir.mkdirs()

                // Создаем файл для фото этого исполнителя
                val photoFile = File(photosDir, "${artistName.hashCode()}.jpg")

                // Копируем байты из галереи прямо в наш файл
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(photoFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Запоминаем абсолютный путь к локальному файлу в настройки
                prefs.edit().putString(artistName, photoFile.absolutePath).apply()

                // Обновляем библиотеку в главном потоке
                withContext(Dispatchers.Main) {
                    buildLibrary(cachedTracks)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun processTrack(track: TrackEntity, title: String, artist: String, album: String, year: Int? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedTrack = track.copy(
                title = title,
                artist = artist.ifBlank { "Неизвестный исполнитель" },
                album = album.ifBlank { null },
                year = year ?: track.year, // Обновляем год
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
            val localPath = savePlaylistPhotoLocally(imageUri)
            dao.insertPlaylist(PlaylistEntity(name = name, imageUri = localPath))
        }
    }

    fun updatePlaylist(playlist: PlaylistEntity, newName: String, newImageUri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            // Если ссылка начинается с "content://", значит мы выбрали новое фото из галереи
            val finalPath = if (newImageUri?.startsWith("content://") == true) {
                savePlaylistPhotoLocally(newImageUri)
            } else {
                newImageUri // Иначе оставляем старый локальный путь (или null)
            }
            dao.updatePlaylist(playlist.copy(name = newName, imageUri = finalPath))
        }
    }

    // --- НОВАЯ УНИВЕРСАЛЬНАЯ ФУНКЦИЯ СОХРАНЕНИЯ ---
    private fun savePlaylistPhotoLocally(uriString: String?): String? {
        if (uriString == null) return null
        return try {
            val context = getApplication<Application>()
            val uri = Uri.parse(uriString)

            // Создаем папку playlist_photos
            val photosDir = File(context.filesDir, "playlist_photos")
            if (!photosDir.exists()) photosDir.mkdirs()

            // Генерируем уникальное имя файла по времени
            val photoFile = File(photosDir, "playlist_${System.currentTimeMillis()}.jpg")

            // Копируем байты из галереи в наше приложение
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(photoFile).use { output ->
                    input.copyTo(output)
                }
            }
            photoFile.absolutePath // Возвращаем путь к нашему локальному файлу
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
    private val _currentQueueFlow = MutableStateFlow<List<TrackEntity>>(emptyList())
    private val _currentQueueTitle = MutableStateFlow("Очередь")
    val currentQueueTitle: StateFlow<String> = _currentQueueTitle.asStateFlow()
    val currentQueueFlow: StateFlow<List<TrackEntity>> = _currentQueueFlow.asStateFlow()
    var musicService: MusicService? = null
    private var progressJob: Job? = null // Для фонового таймера прогресса

    private val _currentTrack = MutableStateFlow<TrackEntity?>(null)
    val currentTrack: StateFlow<TrackEntity?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    //режимы воспроизведения (случайный порядок и прочие)
    private val _playbackMode = MutableStateFlow(PlaybackMode.REPEAT_ALL)
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

    // Добавили параметр forcedTitle
    fun playTrack(track: TrackEntity, playlist: List<TrackEntity>? = null, forcedTitle: String? = null) {
        if (playlist != null) {
            currentQueue = playlist
            _currentQueueFlow.value = playlist
            currentQueueIndex = currentQueue.indexOf(track)

            if (forcedTitle != null) {
                // Если передали конкретное название (например, из кастомного плейлиста)
                _currentQueueTitle.value = forcedTitle
            } else {
                // Умное авто-определение!
                val isSameAlbum = playlist.isNotEmpty() && playlist.all { it.album == playlist.first().album }
                val isSameArtist = playlist.isNotEmpty() && playlist.all { it.artist == playlist.first().artist }

                _currentQueueTitle.value = when {
                    isSameAlbum && !playlist.first().album.isNullOrBlank() -> playlist.first().album!!
                    isSameArtist && !playlist.first().artist.isNullOrBlank() -> "Треки: ${playlist.first().artist}"
                    playlist.size == cachedTracks.size -> "Все скачанные треки"
                    else -> "Моя музыка"
                }
            }
        } else {
            currentQueueIndex = currentQueue.indexOf(track)
        }

        progressJob?.cancel()

        musicService?.currentTrackItem = track
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

        musicService?.updateNotification(
            title = track.title ?: track.fileName,
            artist = track.artist ?: "Неизвестный исполнитель",
            isPlaying = true
        )

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
            val tracks = getTracksForPlaylist(playlistId).first()
            // Получаем реальное имя плейлиста из базы
            val pName = allPlaylists.first().find { it.playlistId == playlistId }?.name ?: "Плейлист"

            if (tracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    playTrack(tracks.first(), tracks, forcedTitle = pName) // Передаем имя!
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

    private val _artistsList = MutableStateFlow<List<Artist>>(emptyList())
    val artistsList = _artistsList.asStateFlow()

    private fun buildLibrary(tracks: List<TrackEntity>) {
        // 1. Группируем общую кучу треков по именам артистов
        val groupedByArtist = tracks.groupBy { it.artist ?: "Неизвестный исполнитель" }

        val newArtistsList = groupedByArtist.map { (artistName, artistTracks) ->

            // 2. Внутри каждого артиста группируем его треки по альбомам
            val groupedByAlbum = artistTracks.groupBy { it.album ?: "Неизвестный альбом" }

            val albumList = groupedByAlbum.map { (albumTitle, albumTracks) ->
                val releaseYear = albumTracks.firstNotNullOfOrNull { it.year }

                // --- СОРТИРУЕМ ВСЕ ТРЕКИ ПО СОХРАНЕННОМУ ПОРЯДКУ ---
                val sortedTracks = albumTracks.sortedBy { it.albumOrder }

                // Фильтруем треки по типам
                val regular = albumTracks.filter { !it.isDemo && !it.isUnreleased }
                val demos = albumTracks.filter { it.isDemo }
                val unreleased = albumTracks.filter { it.isUnreleased }

                // Ключ для хранения обложки альбома в prefs
                val albumPhotoKey = "album_photo_${artistName}_${albumTitle}"

                Album(
                    title = albumTitle,
                    artist = artistName,
                    year = releaseYear,
                    coverUri = prefs.getString(albumPhotoKey, null), // Читаем сохраненный путь к обложке
                    regularTracks = regular,
                    demoTracks = demos,
                    unreleasedTracks = unreleased,
                    hasDemosEnabled = demos.isNotEmpty(),
                    hasUnreleasedEnabled = unreleased.isNotEmpty()
                )
            }.sortedBy { it.year ?: 0 } // <-- Сортируем альбомы по хронологии!

            Artist(
                name = artistName,
                photoUri = prefs.getString(artistName, null), // <--- Читаем из памяти устройства!
                albums = albumList,
                topTracks = artistTracks.take(5),
                allTracks = artistTracks
            )
        }.sortedBy { it.name } // Сортируем самих артистов по алфавиту для вкладки Статистика

        // Отдаем готовый список интерфейсу
        _artistsList.value = newArtistsList
    }

    // Сохранение обложки альбома вечно в память устройства
    fun setAlbumPhoto(artistName: String, albumTitle: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val photosDir = File(context.filesDir, "album_photos")
                if (!photosDir.exists()) photosDir.mkdirs()

                val photoFile = File(photosDir, "${artistName.hashCode()}_${albumTitle.hashCode()}.jpg")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(photoFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val albumPhotoKey = "album_photo_${artistName}_${albumTitle}"
                prefs.edit().putString(albumPhotoKey, photoFile.absolutePath).apply()

                withContext(Dispatchers.Main) {
                    buildLibrary(cachedTracks)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Сохранение изменений альбома (Год + Статусы треков)
    fun updateAlbumDetails(
        album: Album,
        newYear: Int?,
        trackTypesMap: Map<String, String>,
        orderedUris: List<String> // <--- НОВЫЙ ПАРАМЕТР ПОРЯДКА
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val allAlbumTracks = album.regularTracks + album.demoTracks + album.unreleasedTracks

            allAlbumTracks.forEach { track ->
                val type = trackTypesMap[track.uri] ?: "REGULAR"
                val orderIndex = orderedUris.indexOf(track.uri) // Узнаем новую позицию трека

                val updatedTrack = track.copy(
                    year = newYear,
                    isDemo = (type == "DEMO"),
                    isUnreleased = (type == "UNRELEASED"),
                    albumOrder = if (orderIndex != -1) orderIndex else track.albumOrder
                )
                dao.updateTrack(updatedTrack)
            }
        }
    }

    // Получить путь к обложке альбома по имени артиста и названию альбома
    fun getAlbumCoverPath(artistName: String?, albumTitle: String?): String? {
        if (artistName.isNullOrBlank() || albumTitle.isNullOrBlank()) return null
        val albumPhotoKey = "album_photo_${artistName}_${albumTitle}"
        return prefs.getString(albumPhotoKey, null)
    }
}