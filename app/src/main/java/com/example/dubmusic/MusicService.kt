package com.example.dubmusic // Убедись, что тут твой правильный пакет!

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.media.MediaPlayer
import android.net.Uri
import androidx.media.app.NotificationCompat.MediaStyle
import android.app.PendingIntent
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat

class MusicService : Service() {

    // Переменные для связи кнопок в шторке с функциями в ViewModel
    var onNextClick: (() -> Unit)? = null
    var onPrevClick: (() -> Unit)? = null
    var onPlayPauseClick: (() -> Unit)? = null

    // Это "мост", через который наш интерфейс (MusicViewModel) будет общаться с этим Сервисом
    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }
    private val binder = MusicBinder()

    // Плеер теперь живет здесь!
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var mediaSession: MediaSessionCompat

    // Переменная для связи: Сервис будет кричать "Песня закончилась!", а ViewModel будет это слышать
    var onTrackCompletion: (() -> Unit)? = null

    fun playTrack(uriString: String) {
        mediaPlayer?.release()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, Uri.parse(uriString))
                prepare()
                start()
                // Когда песня кончилась, дергаем коллбэк
                setOnCompletionListener { onTrackCompletion?.invoke() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pause() { mediaPlayer?.pause() }
    fun resume() { mediaPlayer?.start() }
    fun seekTo(position: Int) { mediaPlayer?.seekTo(position) }
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    // Добавим переменную, чтобы Сервис запоминал, какая песня сейчас играет
    var currentTrackItem: TrackEntity? = null

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaSession.release() // Выключаем сессию
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            "MUSIC_CHANNEL",
            "Музыкальный плеер",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        // --- НОВОЕ: Создаем официальный паспорт плеера и подключаем к нему наушники ---
        mediaSession = MediaSessionCompat(this, "DubMusicService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { onPlayPauseClick?.invoke() }
                override fun onPause() { onPlayPauseClick?.invoke() }
                override fun onSkipToNext() { onNextClick?.invoke() }
                override fun onSkipToPrevious() { onPrevClick?.invoke() }
                // --- НОВОЕ: Слушаем перемотку из шторки ---
                override fun onSeekTo(pos: Long) {
                    mediaPlayer?.seekTo(pos.toInt())
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ловим клики из шторки
        when (intent?.action) {
            "ACTION_PLAY_PAUSE" -> onPlayPauseClick?.invoke()
            "ACTION_NEXT" -> onNextClick?.invoke()
            "ACTION_PREV" -> onPrevClick?.invoke()
        }
        return START_NOT_STICKY
    }

    // Эта функция будет менять песню и кнопки в шторке
    fun updateNotification(title: String, artist: String, isPlaying: Boolean) {

        // Получаем текущие данные прямо из плеера
        val duration = mediaPlayer?.duration?.toLong() ?: 0L
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L

        // 1. МЕТАДАННЫЕ (Добавили передачу длины трека - METADATA_KEY_DURATION)
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration) // <--- Для ползунка
            .build()
        mediaSession.setMetadata(metadata)

        // 2. СТАТУС ПЛЕЕРА (Говорим Android, что конкретно сейчас происходит)
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO // <--- Разрешаем системе дергать ползунок
            )
            // Передаем статус, текущую секунду и скорость (1.0f - нормальная скорость)
            .setState(state, position, if (isPlaying) 1.0f else 0f)
            .build()
        mediaSession.setPlaybackState(playbackState)

        // 3. СБОРКА УВЕДОМЛЕНИЯ (твой старый код)
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play

        val prevIntent = PendingIntent.getService(this, 1, Intent(this, MusicService::class.java).apply { action = "ACTION_PREV" }, PendingIntent.FLAG_IMMUTABLE)
        val playPauseIntent = PendingIntent.getService(this, 2, Intent(this, MusicService::class.java).apply { action = "ACTION_PLAY_PAUSE" }, PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getService(this, 3, Intent(this, MusicService::class.java).apply { action = "ACTION_NEXT" }, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, "MUSIC_CHANNEL")
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_play)
            .addAction(R.drawable.ic_prev, "Prev", prevIntent)
            .addAction(playPauseIcon, "Play/Pause", playPauseIntent)
            .addAction(R.drawable.ic_next, "Next", nextIntent)
            .setStyle(
                MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.sessionToken)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder // Отдаем "мост" приложению, когда оно к нам подключается
    }
}