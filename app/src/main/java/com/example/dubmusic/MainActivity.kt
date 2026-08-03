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
import android.net.Uri
import android.os.IBinder
import androidx.activity.viewModels
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.enableEdgeToEdge


data class Album(
    val title: String,
    val artist: String,
    val year: Int?,
    val coverUri: String?, // Путь к сохраненной обложке альбома
    val regularTracks: List<TrackEntity>,
    val demoTracks: List<TrackEntity>,
    val unreleasedTracks: List<TrackEntity>, // <--- НОВОЕ
    var hasDemosEnabled: Boolean = false,
    var hasUnreleasedEnabled: Boolean = false // <--- НОВОЕ
)

data class Artist(
    val name: String,
    val photoUri: String?,
    val albums: List<Album>,
    val topTracks: List<TrackEntity>,
    val allTracks: List<TrackEntity>
)

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
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
                    // Мини-плеер (нижняя плашка)
                    currentTrack?.let { track ->
                        BottomPlayerBar(
                            track = track,
                            viewModel = viewModel, // <--- ПЕРЕДАЕМ VIEWMODEL СЮДА
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
                    // ИСПРАВЛЕНИЕ: Оставляем отступ только снизу для мини-плеера и меню. Верх пускаем под экран!
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (selectedItem) {
                    0 -> StubScreen("Волна", "Здесь будет случайный поток треков")
                    1 -> StatsTabScreen(viewModel = viewModel)
                    2 -> SavedTabScreen(viewModel)
                    3 -> PlaylistsTabScreen(viewModel)
                }
            }
        }

        val customPlaylists by viewModel.allPlaylists.collectAsState(initial = emptyList<PlaylistEntity>())

        BackHandler(enabled = showFullScreenPlayer) {
            showFullScreenPlayer = false
        }
        AnimatedVisibility(
            visible = showFullScreenPlayer && currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            currentTrack?.let { track ->
                val playlistsContainingTrack by viewModel.getPlaylistsForTrack(track.uri).collectAsState(initial = emptyList())
                val playbackMode by viewModel.playbackMode.collectAsState()
                FullScreenPlayer(
                    viewModel = viewModel, // <--- И ПЕРЕДАЕМ VIEWMODEL СЮДА
                    playbackMode = playbackMode,
                    playlistsContainingTrack = playlistsContainingTrack,
                    track = track,
                    isPlaying = isPlaying,
                    progress = progress,
                    currentTime = currentTime,
                    totalTime = totalTime,
                    playlists = customPlaylists,
                    onTogglePlayback = { viewModel.togglePlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onClose = { showFullScreenPlayer = false },
                    onEdit = { trackToEdit = track },
                    onNext = { viewModel.playNext() },
                    onPrev = { viewModel.playPrev() },
                    onToggleMode = { viewModel.togglePlaybackMode(it) },
                    onAddToPlaylist = { playlist -> viewModel.addTrackToPlaylist(track, playlist) }
                )
            }
        }
    }

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
    viewModel: MusicViewModel, // Добавили параметр
    isPlaying: Boolean,
    progress: Float,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onOpenFullScreen: () -> Unit
) {
    // Пытаемся найти и загрузить обложку альбома
    val coverPath = viewModel.getAlbumCoverPath(track.artist, track.album)
    val bitmap = remember(coverPath) {
        coverPath?.let { path ->
            try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp)
            .background(Color.White)
            .clickable { onOpenFullScreen() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- ОБЛОЖКА ИЛИ ЗАГЛУШКА ---
            Box(
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = track.title ?: track.fileName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(text = track.artist ?: "Неизвестный", fontSize = 14.sp, color = Color.Gray, maxLines = 1)
            }

            IconButton(onClick = onTogglePlayback) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Следующий",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
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
    viewModel: MusicViewModel, // Добавили параметр
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
    var showPlaylistSelector by remember { mutableStateOf(false) }

    // Пытаемся найти и загрузить обложку альбома
    val coverPath = viewModel.getAlbumCoverPath(track.artist, track.album)
    val bitmap = remember(coverPath) {
        coverPath?.let { path ->
            try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
        }
    }

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

        // --- БОЛЬШАЯ ОБЛОЖКА ИЛИ ЗАГЛУШКА ---
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).shadow(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

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

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isShuffle = playbackMode == PlaybackMode.SHUFFLE
            IconButton(
                onClick = { onToggleMode(PlaybackMode.SHUFFLE) },
                modifier = Modifier.background(if (isShuffle) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = "Случайный порядок", tint = if (isShuffle) MaterialTheme.colorScheme.primary else Color.Gray)
            }

            val isRepeatAll = playbackMode == PlaybackMode.REPEAT_ALL
            IconButton(
                onClick = { onToggleMode(PlaybackMode.REPEAT_ALL) },
                modifier = Modifier.background(if (isRepeatAll) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
            ) {
                Icon(Icons.Default.Repeat, contentDescription = "По кругу", tint = if (isRepeatAll) MaterialTheme.colorScheme.primary else Color.Gray)
            }

            val isRepeatOne = playbackMode == PlaybackMode.REPEAT_ONE
            IconButton(
                onClick = { onToggleMode(PlaybackMode.REPEAT_ONE) },
                modifier = Modifier.background(if (isRepeatOne) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
            ) {
                Icon(Icons.Default.RepeatOne, contentDescription = "Повторять один", tint = if (isRepeatOne) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }
    }

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
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = playlist.name, fontSize = 18.sp)

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


@Composable
fun StatsTabScreen(viewModel: MusicViewModel) {
    // Храним только имена открытых карточек
    var openedArtistName by remember { mutableStateOf<String?>(null) }
    var openedAlbumTitle by remember { mutableStateOf<String?>(null) }

    val artists by viewModel.artistsList.collectAsState()

    // Динамически ищем актуальные данные прямо из списка ViewModel
    val currentArtist = remember(artists, openedArtistName) {
        artists.find { it.name == openedArtistName }
    }

    val currentAlbum = remember(currentArtist, openedAlbumTitle) {
        currentArtist?.albums?.find { it.title == openedAlbumTitle }
    }

    // 1. Если открыт альбом
    if (currentAlbum != null) {
        BackHandler { openedAlbumTitle = null }

        AlbumScreen(
            album = currentAlbum,
            viewModel = viewModel,
            onClose = { openedAlbumTitle = null }
        )
    }
    // 2. Если открыт артист
    else if (currentArtist != null) {
        BackHandler { openedArtistName = null }

        ArtistScreen(
            artist = currentArtist,
            viewModel = viewModel,
            onAlbumClick = { album -> openedAlbumTitle = album.title },
            onClose = { openedArtistName = null }
        )
    }
    // 3. Главный список всех артистов
    else {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Моя медиатека",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            if (artists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Здесь пока пусто. Добавьте музыку!", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(artists) { artist ->

                        val bitmap = remember(artist.photoUri) {
                            artist.photoUri?.let { path ->
                                try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openedArtistName = artist.name } // Сохраняем ИМЯ артиста
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Фото исполнителя",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color.LightGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column {
                                Text(artist.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "${artist.albums.size} альбомов • ${artist.allTracks.size} треков",
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = Color.LightGray.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(
    artist: Artist,
    viewModel: MusicViewModel,
    onAlbumClick: (Album) -> Unit,
    onClose: () -> Unit
) {
    var showAllTracks by remember { mutableStateOf(false) }
    val displayedTracks = if (showAllTracks) artist.allTracks else artist.topTracks

    // Считываем текущую играющую песню из ViewModel
    val currentTrack by viewModel.currentTrack.collectAsState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setArtistPhoto(artist.name, it) }
    }

    val bitmap = remember(artist.photoUri) {
        artist.photoUri?.let { path ->
            try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {

        // ШАПКА АРТИСТА
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(Color.DarkGray)
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Фото исполнителя",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(100.dp).align(Alignment.Center)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.5f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f)
                                )
                            )
                        )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, start = 8.dp, end = 8.dp)
                        .align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }
                    IconButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.Edit, contentDescription = "Изменить фото", tint = Color.White)
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = artist.name,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(
                        onClick = {
                            val tracksToPlay = if (artist.topTracks.isNotEmpty()) artist.topTracks else artist.allTracks
                            if (tracksToPlay.isNotEmpty()) {
                                viewModel.playTrack(tracksToPlay.first(), tracksToPlay)
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Играть",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        // ПОПУЛЯРНЫЕ ТРЕКИ
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showAllTracks) "Все треки" else "Популярные треки",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (artist.allTracks.size > 5) {
                    TextButton(onClick = { showAllTracks = !showAllTracks }) {
                        Text(if (showAllTracks) "Свернуть" else "Все (${artist.allTracks.size})", fontSize = 14.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(displayedTracks) { track ->
            // Автоматически находим обложку альбома для этого трека
            val trackCoverUri = artist.albums.find { it.title == track.album }?.coverUri
            val isPlaying = (currentTrack?.uri == track.uri)

            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrackRowItem(
                    track = track,
                    coverUri = trackCoverUri,
                    isPlaying = isPlaying
                ) {
                    viewModel.playTrack(track, artist.allTracks)
                }
            }
        }

        // АЛЬБОМЫ
        if (!showAllTracks) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Альбомы",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(artist.albums) { album ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AlbumRowItem(album = album, onClick = { onAlbumClick(album) })
                }
            }
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

@Composable
fun AlbumScreen(
    album: Album,
    viewModel: MusicViewModel,
    onClose: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }

    // Считываем текущую играющую песню
    val currentTrack by viewModel.currentTrack.collectAsState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setAlbumPhoto(album.artist, album.title, it) }
    }

    val bitmap = remember(album.coverUri) {
        album.coverUri?.let { path ->
            try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
        }
    }

    if (showEditDialog) {
        EditAlbumDialog(album = album, viewModel = viewModel, onDismiss = { showEditDialog = false })
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {

        // ==========================================
        // 1. ШАПКА АЛЬБОМА С СЕРЫМ ФОНОМ
        // ==========================================
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(Color(0xFF242424)) // Тёмно-серый цвет фона для шапки
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                        }
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Редактировать альбом", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Обложка альбома
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.DarkGray)
                            .clickable { photoPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Обложка альбома",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Album, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                                Text("Нажмите, чтобы добавить фото", color = Color.LightGray, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = album.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        text = "${album.artist} • ${album.year ?: "Неизвестный год"}",
                        fontSize = 16.sp,
                        color = Color.LightGray
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ==========================================
        // 2. ОСНОВНЫЕ ТРЕКИ АЛЬБОМА
        // ==========================================
        items(album.regularTracks) { track ->
            val isPlaying = (currentTrack?.uri == track.uri)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrackRowItem(
                    track = track,
                    coverUri = album.coverUri, // Передаем обложку альбома
                    isPlaying = isPlaying
                ) {
                    viewModel.playTrack(track, album.regularTracks + album.demoTracks + album.unreleasedTracks)
                }
            }
        }

        // ==========================================
        // 3. СЕКЦИЯ DEMO
        // ==========================================
        if (album.hasDemosEnabled && album.demoTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                    Text(text = "Demo", modifier = Modifier.padding(horizontal = 12.dp), color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(album.demoTracks) { track ->
                val isPlaying = (currentTrack?.uri == track.uri)
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TrackRowItem(
                        track = track,
                        coverUri = album.coverUri,
                        isPlaying = isPlaying
                    ) {
                        viewModel.playTrack(track, album.regularTracks + album.demoTracks + album.unreleasedTracks)
                    }
                }
            }
        }

        // ==========================================
        // 4. СЕКЦИЯ UNRELEASED
        // ==========================================
        if (album.hasUnreleasedEnabled && album.unreleasedTracks.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                    Text(text = "Unreleased", modifier = Modifier.padding(horizontal = 12.dp), color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(album.unreleasedTracks) { track ->
                val isPlaying = (currentTrack?.uri == track.uri)
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TrackRowItem(
                        track = track,
                        coverUri = album.coverUri,
                        isPlaying = isPlaying
                    ) {
                        viewModel.playTrack(track, album.regularTracks + album.demoTracks + album.unreleasedTracks)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

@Composable
fun TrackRowItem(
    track: TrackEntity,
    coverUri: String? = null,
    isPlaying: Boolean = false,
    onClick: () -> Unit
) {
    val bitmap = remember(coverUri) {
        coverUri?.let { path ->
            try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
        }
    }

    val safeTitle = track.title?.takeIf { it.isNotBlank() } ?: track.fileName
    val safeArtist = track.artist?.takeIf { it.isNotBlank() } ?: "Неизвестный исполнитель"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // --- ОБЛОЖКА ТРЕКА ИЛИ ЗАГЛУШКА ---
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isPlaying) MaterialTheme.colorScheme.primary
                        else Color.LightGray
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // --- НАЗВАНИЕ + ТЕГ + ИСПОЛНИТЕЛЬ ---
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Название трека
                Text(
                    text = safeTitle,
                    fontSize = 16.sp,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false) // Позволяет названию сужаться, оставляя место для тега
                )

                // ТЕГИ DEMO ИЛИ UNRELEASED
                if (track.isDemo || track.isUnreleased) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (track.isDemo) "Demo" else "Unreleased",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Gray.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = safeArtist,
                fontSize = 14.sp,
                color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else Color.Gray,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AlbumRowItem(album: Album, onClick: () -> Unit) {
    val bitmap = remember(album.coverUri) {
        album.coverUri?.let { path ->
            try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Album, contentDescription = null, tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(album.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            val totalTracks = album.regularTracks.size + album.demoTracks.size + album.unreleasedTracks.size
            Text("${album.year ?: "Год не указан"} • $totalTracks треков", fontSize = 14.sp, color = Color.Gray)
        }
    }
}

@Composable
fun EditAlbumDialog(
    album: Album,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    var yearText by remember { mutableStateOf(album.year?.toString() ?: "") }

    val allTracks = remember(album) { album.regularTracks + album.demoTracks + album.unreleasedTracks }

    // Карта состояний для каждой песни: URI (String) -> "REGULAR", "DEMO", "UNRELEASED"
    val trackTypes = remember {
        mutableStateMapOf<String, String>().apply {
            allTracks.forEach { track ->
                val type = when {
                    track.isDemo -> "DEMO"
                    track.isUnreleased -> "UNRELEASED"
                    else -> "REGULAR"
                }
                put(track.uri, type)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать альбом") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedTextField(
                        value = yearText,
                        onValueChange = { yearText = it },
                        label = { Text("Год выпуска") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    )
                    Text("Типы треков:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(allTracks) { track ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(track.title ?: track.fileName, fontWeight = FontWeight.Medium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            FilterChip(
                                selected = trackTypes[track.uri] == "REGULAR",
                                onClick = { trackTypes[track.uri] = "REGULAR" },
                                label = { Text("Обычный", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = trackTypes[track.uri] == "DEMO",
                                onClick = { trackTypes[track.uri] = "DEMO" },
                                label = { Text("Demo", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = trackTypes[track.uri] == "UNRELEASED",
                                onClick = { trackTypes[track.uri] = "UNRELEASED" },
                                label = { Text("Unreleased", fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.updateAlbumDetails(
                    album = album,
                    newYear = yearText.toIntOrNull(),
                    trackTypesMap = trackTypes
                )
                onDismiss()
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

