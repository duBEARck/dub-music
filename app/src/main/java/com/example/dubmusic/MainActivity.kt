package com.example.dubmusic

import android.os.Build
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.viewModels
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.activity.compose.BackHandler

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Спрашиваем разрешение на уведомления для новых версий Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // --- НОВЫЙ БЛОК: Подключение к фоновому сервису ---
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MusicService.MusicBinder
                viewModel.musicService = binder.getService()

                viewModel.musicService?.onNextClick = { viewModel.playNext() }
                viewModel.musicService?.onPrevClick = { viewModel.playPrev() }
                viewModel.musicService?.onPlayPauseClick = { viewModel.togglePlayback() }

                // --- НОВОЕ: Синхронизируем интерфейс с Сервисом! ---
                viewModel.syncWithService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                viewModel.musicService = null
            }
        }

        val serviceIntent = Intent(this, MusicService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF6200EA),
                background = Color(0xFFF5F5F5),
                surface = Color.White
            )) {
                MusicAppMainScreen(viewModel)
            }
        }
    }
}

@Composable
fun MusicAppMainScreen(viewModel: MusicViewModel) {
    var selectedItem by remember { mutableIntStateOf(0) }

    // Состояния для полноэкранного плеера и редактирования
    var showFullScreenPlayer by remember { mutableStateOf(false) }
    var trackToEdit by remember { mutableStateOf<TrackEntity?>(null) }

    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progress by viewModel.progress.collectAsState()

    val currentTime by viewModel.currentTime.collectAsState()
    val totalTime by viewModel.totalTime.collectAsState()

    val items = listOf("Волна", "Статистика", "Сохранённое", "Плейлисты")
    val icons = listOf(Icons.Default.Waves, Icons.Default.Assessment, Icons.Default.LibraryMusic, Icons.Default.QueueMusic)

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    // Мини-плеер
                    currentTrack?.let { track ->
                        BottomPlayerBar(
                            track = track,
                            isPlaying = isPlaying,
                            progress = progress,
                            onTogglePlayback = { viewModel.togglePlayback() },
                            onNext = { viewModel.playNext() },
                            onOpenFullScreen = { showFullScreenPlayer = true }
                        )
                    }
                    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                        items.forEachIndexed { index, item ->
                            NavigationBarItem(
                                icon = { Icon(icons[index], contentDescription = item) },
                                label = { Text(item) },
                                selected = selectedItem == index,
                                onClick = { selectedItem = index },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (selectedItem) {
                    0 -> StubScreen("Волна", "Здесь будет случайный поток треков")
                    1 -> StubScreen("Статистика", "Топ-5 артистов и треков за месяц/год")
                    2 -> SavedTabScreen(viewModel)
                    3 -> PlaylistsTabScreen(viewModel)
                }
            }
        }


        val customPlaylists by viewModel.allPlaylists.collectAsState(initial = emptyList<PlaylistEntity>())
        // Анимация выезда полноэкранного плеера снизу вверх
        // --- НОВОЕ: Закрываем большой плеер свайпом "Назад" ---
        BackHandler(enabled = showFullScreenPlayer) {
            showFullScreenPlayer = false
        }
        AnimatedVisibility(
            visible = showFullScreenPlayer && currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it }), // Выезжает снизу
            exit = slideOutVertically(targetOffsetY = { it })   // Уезжает вниз
        ) {
            currentTrack?.let { track ->
                val playlistsContainingTrack by viewModel.getPlaylistsForTrack(track.uri).collectAsState(initial = emptyList())
                val playbackMode by viewModel.playbackMode.collectAsState()
                FullScreenPlayer(
                    playbackMode = playbackMode,
                    playlistsContainingTrack = playlistsContainingTrack,
                    track = track,
                    isPlaying = isPlaying,
                    progress = progress,
                    currentTime = currentTime,
                    totalTime = totalTime,
                    playlists = customPlaylists, // ПЕРЕДАЕМ СПИСОК ПЛЕЙЛИСТОВ
                    onTogglePlayback = { viewModel.togglePlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onClose = { showFullScreenPlayer = false },
                    onEdit = { trackToEdit = track },
                    onNext = { viewModel.playNext() },
                    onPrev = { viewModel.playPrev() },
                    onToggleMode = { viewModel.togglePlaybackMode(it) }, // режим плеера
                    // ПЕРЕДАЕМ КОМАНДУ ДОБАВЛЕНИЯ
                    onAddToPlaylist = { playlist -> viewModel.addTrackToPlaylist(track, playlist) }
                )
            }
        }
    }

    // Окно редактирования (вызывается поверх всего, если мы нажали "Изменить информацию")
    trackToEdit?.let { track ->
        EditTrackDialog(
            track = track,
            onDismiss = { trackToEdit = null },
            onSave = { title, artist, album ->
                viewModel.processTrack(track, title, artist, album)
                trackToEdit = null
            }
        )
    }
}

@Composable
fun BottomPlayerBar(
    track: TrackEntity,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onOpenFullScreen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp)
            .background(Color.White)
            .clickable { onOpenFullScreen() } // Клик по прямоугольнику
    ) {
        Row(
            // ИЗМЕНЕНИЕ 1: Заменили horizontal = 16.dp на start = 16.dp, end = 4.dp (чтобы прижать кнопки вправо)
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = track.title ?: track.fileName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(text = track.artist ?: "Неизвестный", fontSize = 14.sp, color = Color.Gray, maxLines = 1)
            }

            // Кнопка Play/Pause. Важно: мы перехватываем клик, чтобы он не прошел на весь прямоугольник
            IconButton(onClick = onTogglePlayback) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    // ИЗМЕНЕНИЕ 2: Увеличили размер до 36.dp, чтобы кнопки смотрелись увереннее
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Кнопка Следующий трек
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Следующий",
                    // ИЗМЕНЕНИЕ 3: Добавили точно такой же размер 36.dp и фирменный цвет
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        // Та самая растущая полоска
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent
        )
    }
}

// ---------------------------------------------------------
// ПОЛНОЭКРАННЫЙ ПЛЕЕР
// ---------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenPlayer(
    playbackMode: PlaybackMode,
    playlistsContainingTrack: List<Int>,
    track: TrackEntity,
    isPlaying: Boolean,
    progress: Float,
    currentTime: String,
    totalTime: String,
    playlists: List<PlaylistEntity>,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onToggleMode: (PlaybackMode) -> Unit,
    onAddToPlaylist: (PlaylistEntity) -> Unit
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Состояние для отображения окна выбора плейлиста
    var showPlaylistSelector by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (offsetY > 300f) onClose() else offsetY = 0f
                    }
                ) { _, dragAmount ->
                    if (dragAmount > 0 || offsetY > 0) offsetY += dragAmount
                }
            }
            .padding(top = 48.dp, bottom = 32.dp, start = 24.dp, end = 24.dp)
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Свернуть", modifier = Modifier.size(36.dp)) }
        Spacer(modifier = Modifier.height(24.dp))
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).shadow(4.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(32.dp))

        // Название, Артист и 3 точки
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = track.title ?: track.fileName, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(text = track.artist ?: "Неизвестный исполнитель", fontSize = 16.sp, color = Color.Gray, maxLines = 1)
            }

            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Опции")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Изменить информацию") }, onClick = { showMenu = false; onEdit() })
                    // Вызываем диалог выбора плейлиста
                    DropdownMenuItem(text = { Text("Добавить в плейлист") }, onClick = { showMenu = false; showPlaylistSelector = true })
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = currentTime, fontSize = 12.sp, color = Color.Gray)
            Text(text = totalTime, fontSize = 12.sp, color = Color.Gray)
        }
        Slider(value = progress, onValueChange = onSeek, modifier = Modifier.fillMaxWidth(), colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary))
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Назад", modifier = Modifier.size(48.dp)) }
            IconButton(onClick = onTogglePlayback, modifier = Modifier.size(80.dp)) { Icon(imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, contentDescription = "Play/Pause", modifier = Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Вперед", modifier = Modifier.size(48.dp)) }
        }
        Spacer(modifier = Modifier.weight(1f))

        // --- НОВЫЙ БЛОК: Кнопки режимов воспроизведения ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Случайный порядок
            val isShuffle = playbackMode == PlaybackMode.SHUFFLE
            IconButton(
                onClick = { onToggleMode(PlaybackMode.SHUFFLE) },
                modifier = Modifier.background(if (isShuffle) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = "Случайный порядок", tint = if (isShuffle) MaterialTheme.colorScheme.primary else Color.Gray)
            }

            // Повтор всего
            val isRepeatAll = playbackMode == PlaybackMode.REPEAT_ALL
            IconButton(
                onClick = { onToggleMode(PlaybackMode.REPEAT_ALL) },
                modifier = Modifier.background(if (isRepeatAll) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
            ) {
                Icon(Icons.Default.Repeat, contentDescription = "По кругу", tint = if (isRepeatAll) MaterialTheme.colorScheme.primary else Color.Gray)
            }

            // Повтор одной
            val isRepeatOne = playbackMode == PlaybackMode.REPEAT_ONE
            IconButton(
                onClick = { onToggleMode(PlaybackMode.REPEAT_ONE) },
                modifier = Modifier.background(if (isRepeatOne) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
            ) {
                Icon(Icons.Default.RepeatOne, contentDescription = "Повторять один", tint = if (isRepeatOne) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }
    }

    // ДИАЛОГ ВЫБОРА ПЛЕЙЛИСТА
    if (showPlaylistSelector) {
        AlertDialog(
            onDismissRequest = { showPlaylistSelector = false },
            title = { Text("Выберите плейлист", fontWeight = FontWeight.Bold) },
            text = {
                if (playlists.isEmpty()) {
                    Text("У вас пока нет плейлистов", color = Color.Gray)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(playlists.size) { index ->
                            val playlist = playlists[index]
                            // Проверяем, есть ли ID этого плейлиста в списке добавленных
                            val isAlreadyAdded = playlistsContainingTrack.contains(playlist.playlistId)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!isAlreadyAdded) {
                                            onAddToPlaylist(playlist)
                                        }
                                        showPlaylistSelector = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween // Раздвигает текст и галочку по краям
                            ) {
                                Text(text = playlist.name, fontSize = 18.sp)

                                // Если трек уже там — рисуем галочку
                                if (isAlreadyAdded) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Добавлено",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistSelector = false }) { Text("Отмена") }
            }
        )
    }
}

// Заглушка (оставь ее внизу)
@Composable
fun StubScreen(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(text = title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text(text = subtitle, fontSize = 16.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
    }
}

