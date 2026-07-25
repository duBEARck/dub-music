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
    fun addUnprocessedFile(uri: String, fileName: String) {
        // Явно отправляем задачу в фоновый поток (IO)
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertTrack(TrackEntity(uri = uri, fileName = fileName))
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

    // --- ЛОГИКА ПЛЕЕРА ---
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null // Для фонового таймера прогресса

    private val _currentTrack = MutableStateFlow<TrackEntity?>(null)
    val currentTrack: StateFlow<TrackEntity?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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

    fun playTrack(track: TrackEntity) {
        mediaPlayer?.release()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(getApplication(), Uri.parse(track.uri))
                prepare()
                start()
            }
            _currentTrack.value = track
            _isPlaying.value = true

            // Задаем общую длительность при старте
            _totalTime.value = formatTime(mediaPlayer?.duration ?: 0)
            startProgressUpdate()

            mediaPlayer?.setOnCompletionListener {
                _isPlaying.value = false
                _progress.value = 0f
                _currentTime.value = "0:00" // Сбрасываем время при конце трека
                progressJob?.cancel()
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
}