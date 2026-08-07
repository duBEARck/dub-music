package com.example.dubmusic

import android.R.attr.track
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.flatMapLatest

// Контейнер для вытащенных данных (ID3 данных из mp3 файла)
data class SmartMetadata(
    val realFileName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val coverArt: ByteArray?,
    val trackType: String?, // НОВОЕ ПОЛЕ (DEMO, UNRELEASED)
    val lyrics: String?
)
enum class PlaybackMode {
    NORMAL, SHUFFLE, REPEAT_ALL, REPEAT_ONE
}
enum class StatsPeriod { DAY, WEEK, MONTH, YEAR, ALL_TIME }
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    // 1. ОПИСЫВАЕМ МИГРАЦИЮ С ВЕРСИИ 3 НА 4
    private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Добавляем колонку в таблицу "tracks" (так она у тебя называется в TrackEntity)
            database.execSQL("ALTER TABLE tracks ADD COLUMN lyrics TEXT DEFAULT NULL")
        }
    }

    // 2. БИЛДЕР: Говорим ему использовать миграцию
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "dubmusic-database"
    )
        .addMigrations(MIGRATION_3_4)
        .build()

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

    // --- НАВИГАЦИЯ ДЛЯ ПЛЕЙЛИСТОВ (Для сброса по двойному клику) ---
    private val _openedPlaylist = MutableStateFlow<PlaylistEntity?>(null)
    val openedPlaylist = _openedPlaylist.asStateFlow()
    fun openPlaylist(playlist: PlaylistEntity?) { _openedPlaylist.value = playlist }

    private val _showAllTracksPlaylists = MutableStateFlow(false)
    val showAllTracksPlaylists = _showAllTracksPlaylists.asStateFlow()
    fun setShowAllTracksPlaylists(show: Boolean) { _showAllTracksPlaylists.value = show }

    private val _showHiddenTracks = MutableStateFlow(false)
    val showHiddenTracks = _showHiddenTracks.asStateFlow()
    fun setShowHiddenTracks(show: Boolean) { _showHiddenTracks.value = show }

    // --- ДИНАМИЧЕСКИЙ ТОП АРТИСТА ---
    fun getDynamicArtistTopTracks(artistName: String): Flow<List<TrackEntity>> {
        return dao.getDynamicArtistTopTracks(artistName)
    }

    fun openArtist(name: String?) {
        _openedArtistName.value = name
        _openedAlbumTitle.value = null // СБРОС АЛЬБОМА (чтобы не застрять в старом релизе)
    }

    fun openAlbum(title: String?, artistName: String? = null) {
        _openedAlbumTitle.value = title
        if (artistName != null) {
            _openedArtistName.value = artistName // Автоматически подтягиваем нужного артиста как фон
        }
    }

    init {
        // Запускаем сборку библиотеки сразу при создании ViewModel и при любых изменениях в базе
        viewModelScope.launch {
            processedTracks.collect { tracks ->
                cachedTracks = tracks
                buildLibrary(tracks)
            }
        }

        // --- РАЗОВАЯ ЧИСТКА СТАРЫХ ТРЕКОВ ---
        // (Можешь просто удалить этот блок после одного запуска приложения)
        viewModelScope.launch(Dispatchers.IO) {
            val unprocessed = dao.getUnprocessedTracks().firstOrNull() ?: emptyList()
            unprocessed.forEach { track ->
                val smartData = extractMetadata(track.uri)
                dao.updateTrack(track.copy(
                    fileName = smartData.realFileName,
                    title = smartData.title,
                    artist = smartData.artist,
                    album = smartData.album,
                    year = smartData.year
                ))
            }
        }
    }

    // Функция добавления файлов из проводника
    fun addUnprocessedFile(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var duration = 0L
            val smartData = extractMetadata(uriString) // Ошибок больше не будет!

            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(getApplication(), Uri.parse(uriString))
                val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = timeString?.toLong() ?: 0L
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Добавляем трек с флагами из MP3-файла
            dao.insertTrack(
                TrackEntity(
                    uri = uriString,
                    fileName = smartData.realFileName,
                    durationMs = duration,
                    title = smartData.title,
                    artist = smartData.artist,
                    album = smartData.album,
                    year = smartData.year,
                    isDemo = smartData.trackType == "DEMO",
                    isUnreleased = smartData.trackType == "UNRELEASED"
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

    fun processTrack(track: TrackEntity, title: String, artist: String, album: String, year: Int? = null, isDemo: Boolean = false, isUnreleased: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val uri = Uri.parse(track.uri)
            val tempFile = java.io.File(context.cacheDir, "temp_tag_edit.mp3")

            try {
                // 1. Копируем файл во временную память, чтобы случайно не сломать оригинал
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                // 2. Вскрываем библиотекой
                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                val tag = audioFile.tagOrCreateAndSetDefault

                // 3. Пишем стандартные теги
                tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, title.trim())
                tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, artist.trim())
                if (album.isNotBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, album.trim())
                if (year != null) tag.setField(org.jaudiotagger.tag.FieldKey.YEAR, year.toString())

                // 4. Вшиваем кастомный статус (Demo / Unreleased)
                val typeTag = when {
                    isDemo -> "DEMO"
                    isUnreleased -> "UNRELEASED"
                    else -> "NORMAL"
                }
                tag.setField(org.jaudiotagger.tag.FieldKey.COMMENT, "DUBMUSIC=$typeTag")

                audioFile.commit() // Сохраняем в tempFile

                // 5. Перезаписываем физический файл на жестком диске телефона
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Если нет прав на запись, мы просто пропустим этап с файлом,
                // но всё равно обновим приложение ниже, чтобы не бесить пользователя.
            } finally {
                tempFile.delete() // Убираем за собой мусор в любом случае
            }

            // 6. Обновляем локальную базу данных, чтобы интерфейс перерисовался мгновенно
            val updatedTrack = track.copy(
                title = title.trim(),
                artist = artist.ifBlank { "Неизвестный исполнитель" },
                album = album.ifBlank { null },
                year = year ?: track.year,
                isDemo = isDemo,
                isUnreleased = isUnreleased,
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

    // --- АНАЛИТИКА И СТАТИСТИКА ---
    private val _currentStatsPeriod = MutableStateFlow(StatsPeriod.MONTH)
    val currentStatsPeriod = _currentStatsPeriod.asStateFlow()

    fun setStatsPeriod(period: StatsPeriod) {
        _currentStatsPeriod.value = period
    }

    // Хелпер: отдает начало и конец выбранного периода в миллисекундах
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val periodBounds: StateFlow<Pair<Long, Long>> = _currentStatsPeriod
        .mapLatest { period ->
            val calendar = java.util.Calendar.getInstance()
            val endTime = calendar.timeInMillis // Конец всегда "сейчас"

            // Сбрасываем часы, минуты и секунды в ноль для точного начала
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)

            when (period) {
                StatsPeriod.DAY -> { /* Уже сброшено на начало сегодняшнего дня */ }
                StatsPeriod.WEEK -> {
                    calendar.firstDayOfWeek = java.util.Calendar.MONDAY // Жестко задаем понедельник
                    calendar.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                }
                StatsPeriod.MONTH -> {
                    calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                StatsPeriod.YEAR -> {
                    calendar.set(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                StatsPeriod.ALL_TIME -> {
                    calendar.timeInMillis = 0L // От начала времен
                }
            }
            Pair(calendar.timeInMillis, endTime)
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, Pair(0L, System.currentTimeMillis()))

    // Вспомогательная функция для красивого вывода часов и минут
    fun formatMsToHoursMinutes(totalMs: Long?): String {
        if (totalMs == null || totalMs == 0L) return "0 мин"
        val totalSeconds = totalMs / 1000
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
    }

    // --- АВТОМАТИЧЕСКИЕ ПОТОКИ ДАННЫХ ДЛЯ СТАТИСТИКИ ---
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val totalListenTime = periodBounds.flatMapLatest { bounds ->
        dao.getTotalListenTime(bounds.first, bounds.second)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), 0L)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val tracksAddedCount = periodBounds.flatMapLatest { bounds ->
        dao.getTracksAddedCount(bounds.first, bounds.second)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), 0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val topTracksStats = periodBounds.flatMapLatest { bounds ->
        dao.getTopTracks(bounds.first, bounds.second)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val topTracksByCountStats = periodBounds.flatMapLatest { bounds ->
        dao.getTopTracksByCount(bounds.first, bounds.second)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val topArtistsStats = periodBounds.flatMapLatest { bounds ->
        dao.getAllTrackStatsForPeriod(bounds.first, bounds.second).mapLatest { trackStats ->
            val artistMap = mutableMapOf<String, Long>()
            trackStats.forEach { ts ->
                // Разбиваем строку "Артист 1, Артист 2" на отдельных артистов
                val artists = ts.track.artist?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: listOf("Неизвестный исполнитель")
                artists.forEach { a ->
                    artistMap[a] = (artistMap[a] ?: 0L) + ts.statValue
                }
            }
            // Собираем обратно в список и сортируем
            artistMap.map { ArtistStat(it.key, it.value) }.sortedByDescending { it.statValue }.take(5)
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val topArtistsByCountStats = periodBounds.flatMapLatest { bounds ->
        dao.getAllTrackCountsForPeriod(bounds.first, bounds.second).mapLatest { trackStats ->
            val artistMap = mutableMapOf<String, Long>()
            trackStats.forEach { ts ->
                val artists = ts.track.artist?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: listOf("Неизвестный исполнитель")
                artists.forEach { a ->
                    artistMap[a] = (artistMap[a] ?: 0L) + ts.statValue
                }
            }
            artistMap.map { ArtistStat(it.key, it.value) }.sortedByDescending { it.statValue }.take(5)
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())

    // --- ГЛОБАЛЬНЫЕ МЕТРИКИ (НЕ ЗАВИСЯТ ОТ ФИЛЬТРА ВРЕМЕНИ) ---
    val loyaltyArtists = dao.getFullHistoryArtists().mapLatest { history ->
        val artistDays = mutableMapOf<String, MutableSet<String>>()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        history.forEach { item ->
            // Переводим миллисекунды в строку даты (чтобы считать только уникальные дни)
            val dateStr = sdf.format(java.util.Date(item.timestamp))
            val artists = item.trackArtist?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: listOf("Неизвестный исполнитель")

            artists.forEach { a ->
                if (artistDays[a] == null) artistDays[a] = mutableSetOf()
                artistDays[a]!!.add(dateStr)
            }
        }

        // Считаем размер Set (количество уникальных дней) для каждого артиста
        artistDays.map { ArtistDaysStat(it.key, it.value.size) }
            .sortedByDescending { it.daysCount }
            .take(5)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val bingeRecords = dao.getHistoryUris().mapLatest { uris ->
        val maxStreaks = mutableMapOf<String, Long>()
        var currentStreak = 0L
        var currentUri: String? = null

        // Пробегаемся по всей истории
        for (uri in uris) {
            if (uri == currentUri) {
                currentStreak++
            } else {
                // Если трек сменился, сохраняем рекорд предыдущего (если он больше старого рекорда)
                if (currentUri != null) {
                    maxStreaks[currentUri] = maxOf(maxStreaks[currentUri] ?: 0L, currentStreak)
                }
                currentUri = uri
                currentStreak = 1L
            }
        }
        // Не забываем сохранить самый последний трек в списке
        if (currentUri != null) {
            maxStreaks[currentUri] = maxOf(maxStreaks[currentUri] ?: 0L, currentStreak)
        }

        // Сортируем по убыванию, берем топ-5 и превращаем в TrackStat
        maxStreaks.entries
            .sortedByDescending { it.value }
            .take(5)
            .mapNotNull { entry ->
                val track = dao.getTrackByUri(entry.key).firstOrNull()
                if (track != null) TrackStat(track, entry.value) else null
            }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())

    // --- ТЕПЛОВАЯ КАРТА ---
    val heatmapDays = dao.getHeatmapDays().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())
    val heatmapWeeks = dao.getHeatmapWeeks().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())
    val heatmapMonths = dao.getHeatmapMonths().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(), emptyList())


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

    fun playTrack(track: TrackEntity, playlist: List<TrackEntity>? = null, forcedTitle: String? = null, isAutomatic: Boolean = false) {

        // --- СБРОС ПОВТОРА ОДНОГО ТРЕКА ПРИ РУЧНОМ ПЕРЕКЛЮЧЕНИИ ---
        if (!isAutomatic && _playbackMode.value == PlaybackMode.REPEAT_ONE) {
            _playbackMode.value = PlaybackMode.NORMAL
        }

        // --- ЗАПИСЬ СТАТИСТИКИ ПРЕДЫДУЩЕГО ТРЕКА ---
        _currentTrack.value?.let { prevTrack ->
            val playedMs = musicService?.getCurrentPosition()?.toLong() ?: 0L
            // Записываем историю, только если слушали дольше 10 секунд (отсекаем случайные скипы)
            if (playedMs > 10000) {
                viewModelScope.launch(Dispatchers.IO) {
                    dao.insertListeningHistory(
                        ListeningHistoryEntity(
                            trackUri = prevTrack.uri,
                            durationPlayedMs = playedMs
                        )
                    )
                }
            }
        }

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
                playNext(isAutomatic = true) // <-- ПЕРЕДАЕМ ФЛАГ АВТОМАТИКИ!
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

    fun playNext(isAutomatic: Boolean = false) {
        if (currentQueue.isEmpty() || currentQueueIndex == -1) return

        // Если переключили трек кнопкой "Вперед" — сбрасываем залипание на одной песне
        if (!isAutomatic && _playbackMode.value == PlaybackMode.REPEAT_ONE) {
            _playbackMode.value = PlaybackMode.NORMAL
        }

        when (_playbackMode.value) {
            PlaybackMode.REPEAT_ONE -> {
                // Запускаем тот же самый трек (работает только если isAutomatic = true)
                playTrack(currentQueue[currentQueueIndex], isAutomatic = true)
            }
            PlaybackMode.SHUFFLE -> {
                val nextIndex = currentQueue.indices.random()
                playTrack(currentQueue[nextIndex], isAutomatic = true)
            }
            PlaybackMode.REPEAT_ALL -> {
                val nextIndex = if (currentQueueIndex < currentQueue.size - 1) currentQueueIndex + 1 else 0
                playTrack(currentQueue[nextIndex], isAutomatic = true)
            }
            PlaybackMode.NORMAL -> {
                if (currentQueueIndex < currentQueue.size - 1) {
                    playTrack(currentQueue[currentQueueIndex + 1], isAutomatic = true)
                } else {
                    musicService?.pause()
                    _isPlaying.value = false
                }
            }
        }
    }

    fun playPrev() {
        if (currentQueue.isEmpty() || currentQueueIndex == -1) return

        // Назад мы всегда переключаем вручную, так что смело сбрасываем репит
        if (_playbackMode.value == PlaybackMode.REPEAT_ONE) {
            _playbackMode.value = PlaybackMode.NORMAL
        }

        val currentPosition = musicService?.getCurrentPosition() ?: 0

        // Если прошло больше 5 секунд - начинаем трек заново
        if (currentPosition > 5000) {
            _isPlaying.value = true
            musicService?.resume()
            seekTo(0f)
        } else {
            // Переключаем на предыдущий
            when (_playbackMode.value) {
                PlaybackMode.REPEAT_ONE -> playTrack(currentQueue[currentQueueIndex], isAutomatic = true)
                PlaybackMode.SHUFFLE -> {
                    val prevIndex = currentQueue.indices.random()
                    playTrack(currentQueue[prevIndex], isAutomatic = true)
                }
                PlaybackMode.REPEAT_ALL -> {
                    val prevIndex = if (currentQueueIndex > 0) currentQueueIndex - 1 else currentQueue.size - 1
                    playTrack(currentQueue[prevIndex], isAutomatic = true)
                }
                PlaybackMode.NORMAL -> {
                    if (currentQueueIndex > 0) {
                        playTrack(currentQueue[currentQueueIndex - 1], isAutomatic = true)
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
            // --- ДОБАВЛЕННЫЙ КОД ДЛЯ ВОСКРЕШЕНИЯ СЕРВИСА ---
            // Жестко заставляем систему поднять сервис из мертвых, если она его убила
            val intent = android.content.Intent(getApplication(), MusicService::class.java)
            getApplication<android.app.Application>().startService(intent)
            // -----------------------------------------------

            musicService?.resume()
            _isPlaying.value = true
        }

        // Обновляем уведомление
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

    // Вспомогательный класс для временного сбора данных артиста
    private class ArtistData {
        val albums = mutableListOf<Album>()
        val appearances = mutableSetOf<Album>() // Используем Set, чтобы альбомы не дублировались
        val allTracks = mutableSetOf<TrackEntity>()
    }

    private fun buildLibrary(tracks: List<TrackEntity>) {
        val artistDataMap = mutableMapOf<String, ArtistData>()

        // 1. Группируем треки по ГЛАВНОМУ артисту (первому до запятой)
        val groupedByPrimaryArtist = tracks.groupBy { track ->
            val trackArtists = track.artist?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            trackArtists?.firstOrNull() ?: "Неизвестный исполнитель"
        }

        groupedByPrimaryArtist.forEach { (primaryArtistName, artistTracks) ->
            // Все треки, у которых поле album пустое (null), собираются в папку "Синглы"
            val groupedByAlbum = artistTracks.groupBy { it.album ?: "Синглы" }

            groupedByAlbum.forEach { (albumTitle, albumTracks) ->
                val releaseYear = albumTracks.firstNotNullOfOrNull { it.year }
                val regular = albumTracks.filter { !it.isDemo && !it.isUnreleased }
                val demos = albumTracks.filter { it.isDemo }
                val unreleased = albumTracks.filter { it.isUnreleased }
                val albumPhotoKey = "album_photo_${primaryArtistName}_${albumTitle}"

                val album = Album(
                    title = albumTitle,
                    artist = primaryArtistName,
                    year = releaseYear,
                    coverUri = prefs.getString(albumPhotoKey, null),
                    regularTracks = regular,
                    demoTracks = demos,
                    unreleasedTracks = unreleased,
                    hasDemosEnabled = demos.isNotEmpty(),
                    hasUnreleasedEnabled = unreleased.isNotEmpty()
                )

                // Добавляем альбом в ОСНОВНЫЕ релизы главного артиста
                val pArtistData = artistDataMap.getOrPut(primaryArtistName) { ArtistData() }
                pArtistData.albums.add(album)
                pArtistData.allTracks.addAll(albumTracks)

                // 3. ИЩЕМ ФИТЫ: пробегаемся по трекам и смотрим, есть ли гости
                albumTracks.forEach { track ->
                    val trackArtists = track.artist?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    if (trackArtists.size > 1) {
                        // Раскидываем этот альбом всем остальным гостям в "Участия"
                        trackArtists.drop(1).forEach { guestName ->
                            val gArtistData = artistDataMap.getOrPut(guestName) { ArtistData() }
                            gArtistData.appearances.add(album)
                            gArtistData.allTracks.add(track) // Даем гостю возможность играть этот трек из своего профиля
                        }
                    }
                }
            }
        }

        // 4. Упаковываем всё обратно в красивый список
        val newArtistsList = artistDataMap.map { (artistName, data) ->
            Artist(
                name = artistName,
                photoUri = prefs.getString(artistName, null),
                albums = data.albums.distinctBy { it.title }.sortedBy { it.year ?: 0 },
                appearances = data.appearances.toList().distinctBy { it.title }.sortedBy { it.year ?: 0 },
                topTracks = data.allTracks.toList().take(5),
                allTracks = data.allTracks.toList()
            )
        }.sortedByDescending { it.allTracks.size } // <--- СОРТИРУЕМ ПО КОЛИЧЕСТВУ ТРЕКОВ

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

    fun updateAlbumDetails(album: Album, newYear: Int?, orderedUris: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            // Просто обновляем порядок треков и год (статусы Demo/Unreleased остаются как были)
            orderedUris.forEachIndexed { index, uri ->
                val track = dao.getTrackByUri(uri).firstOrNull()
                if (track != null) {
                    dao.updateTrack(track.copy(year = newYear, albumOrder = index))
                }
            }
        }
    }

    // Получить путь к обложке альбома по имени артиста (учитывая фиты!)
    fun getAlbumCoverPath(artistName: String?, albumTitle: String?): String? {
        if (artistName.isNullOrBlank() || albumTitle.isNullOrBlank()) return null
        // Берем только ГЛАВНОГО артиста (первого до запятой), так как обложка привязана к нему
        val primaryArtist = artistName.split(",").map { it.trim() }.firstOrNull { it.isNotBlank() } ?: "Неизвестный исполнитель"
        val albumPhotoKey = "album_photo_${primaryArtist}_${albumTitle}"
        return prefs.getString(albumPhotoKey, null)
    }

    // Вспомогательная функция для получения фото ЛЮБОГО артиста по имени
    fun getArtistPhotoPath(artistName: String): String? {
        return prefs.getString(artistName.trim(), null)
    }


    fun extractMetadata(uriString: String): SmartMetadata {
        val uri = Uri.parse(uriString)
        val context = getApplication<Application>().applicationContext

        // 1. Достаем реальное имя файла
        var realFileName = "Неизвестный файл"
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        realFileName = it.getString(index)
                    }
                }
            }
        } else {
            realFileName = uri.path?.substringAfterLast('/') ?: "Неизвестный файл"
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var year: Int? = null
        var coverArt: ByteArray? = null
        var trackType: String? = null

        // --- НОВАЯ ПЕРЕМЕННАЯ ДЛЯ ТЕКСТА ---
        var lyricsText: String? = null

        // 2. Пуленепробиваемое чтение через временный файл
        val tempFile = java.io.File(context.cacheDir, "temp_tag_read.mp3")
        try {
            // Копируем файл из системы в наш кэш
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            // Спокойно читаем теги нормальным путем
            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
            val tag = audioFile.tag

            if (tag != null) {
                fun clean(str: String?) = str?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }

                title = clean(tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE))
                artist = clean(tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST))
                album = clean(tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM))
                year = clean(tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR))?.take(4)?.toIntOrNull()
                coverArt = tag.firstArtwork?.binaryData

                // Читаем наш секретный комментарий (DEMO / UNRELEASED)
                val comment = clean(tag.getFirst(org.jaudiotagger.tag.FieldKey.COMMENT))
                if (comment != null && comment.startsWith("DUBMUSIC=")) {
                    trackType = comment.substringAfter("DUBMUSIC=")
                }

                // --- НОВОЕ: ЧИТАЕМ ТЕКСТ ИЗ ФАЙЛА ---
                lyricsText = clean(tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Обязательно удаляем временный файл, чтобы не засорять память
            tempFile.delete()
        }

        // 3. Фолбэк: если тегов внутри физически нет, пытаемся угадать по имени файла
        var finalTitle = title
        var finalArtist = artist
        if (title == null && artist == null && realFileName.contains("-")) {
            val parts = realFileName.substringBeforeLast(".").split("-", limit = 2)
            if (parts.size == 2) {
                finalArtist = parts[0].trim()
                finalTitle = parts[1].trim()
            }
        }

        return SmartMetadata(
            realFileName = realFileName.substringBeforeLast("."),
            title = finalTitle,
            artist = finalArtist,
            album = album,
            year = year,
            coverArt = coverArt,
            trackType = trackType,
            lyrics = lyricsText // --- ПЕРЕДАЕМ ТЕКСТ ДАЛЬШЕ ---
        )
    }

    fun saveLyrics(track: TrackEntity, newLyrics: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            val uri = Uri.parse(track.uri)
            val tempFile = java.io.File(context.cacheDir, "temp_lyrics_edit.mp3")

            try {
                // Копируем во временный файл
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                // Вскрываем и пишем текст (LYRICS)
                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                val tag = audioFile.tagOrCreateAndSetDefault

                if (newLyrics.isBlank()) {
                    tag.deleteField(org.jaudiotagger.tag.FieldKey.LYRICS)
                } else {
                    tag.setField(org.jaudiotagger.tag.FieldKey.LYRICS, newLyrics.trim())
                }

                audioFile.commit()

                // Перезаписываем физический файл
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                }

                // Обновляем базу данных
                val updatedTrack = track.copy(lyrics = newLyrics.trim().ifBlank { null })
                dao.updateTrack(updatedTrack)

                // ==========================================
                // ИСПРАВЛЕНИЕ: МГНОВЕННОЕ ОБНОВЛЕНИЕ ЭКРАНА
                // ==========================================
                if (_currentTrack.value?.uri == updatedTrack.uri) {
                    _currentTrack.value = updatedTrack
                    musicService?.currentTrackItem = updatedTrack
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                tempFile.delete()
            }
        }
    }
}