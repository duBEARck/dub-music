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
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow

data class Album(
    val title: String,
    val artist: String,
    val year: Int?,
    val coverUri: String?, // Путь к сохраненной обложке альбома
    val regularTracks: List<TrackEntity>,
    val demoTracks: List<TrackEntity>,
    val unreleasedTracks: List<TrackEntity>,
    var hasDemosEnabled: Boolean = false,
    var hasUnreleasedEnabled: Boolean = false
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

        // Подключение к фоновому сервису
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as MusicService.MusicBinder
                viewModel.musicService = binder.getService()

                viewModel.musicService?.onNextClick = { viewModel.playNext() }
                viewModel.musicService?.onPrevClick = { viewModel.playPrev() }
                viewModel.musicService?.onPlayPauseClick = { viewModel.togglePlayback() }

                viewModel.syncWithService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                viewModel.musicService = null
            }
        }

        val serviceIntent = Intent(this, MusicService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6200EA),
                    background = Color(0xFFF5F5F5),
                    surface = Color.White
                )
            ) {
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

    val currentQueue by viewModel.currentQueueFlow.collectAsState()
    val queueTitle by viewModel.currentQueueTitle.collectAsState()

    val items = listOf("Статистика", "Медиатека", "Сохранённое", "Плейлисты")

    val icons = listOf(
        Icons.Default.Assessment,   // Статистика (График)
        Icons.Default.Audiotrack,   // Медиатека (Нота/Трек)
        Icons.Default.LibraryMusic, // Сохранённое (Папка с музыкой)
        Icons.Default.QueueMusic    // Плейлисты (Очередь)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    // Мини-плеер
                    currentTrack?.let { track ->
                        BottomPlayerBar(
                            track = track,
                            viewModel = viewModel,
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
                                onClick = {
                                    if (selectedItem == index) {
                                        // СБРОС (Tap-to-root): Если мы уже на этой вкладке, закрываем всё внутреннее
                                        when (index) {
                                            1 -> { // Медиатека
                                                viewModel.openAlbum(null)
                                                viewModel.openArtist(null)
                                            }
                                            3 -> { // Плейлисты
                                                viewModel.openPlaylist(null)
                                                viewModel.setShowAllTracksPlaylists(false)
                                                viewModel.setShowHiddenTracks(false)
                                            }
                                        }
                                    } else {
                                        // Обычный переход на другую вкладку
                                        selectedItem = index
                                    }
                                },
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
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // ОБНОВЛЯЕМ МАРШРУТИЗАЦИЮ
                when (selectedItem) {
                    0 -> RealStatsScreen(
                        viewModel = viewModel,
                        onNavigateToLibrary = { selectedItem = 1 } // Умный переход в Медиатеку
                    )
                    1 -> LibraryTabScreen(viewModel = viewModel)
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
                val playlistsContainingTrack by viewModel.getPlaylistsForTrack(track.uri)
                    .collectAsState(initial = emptyList())
                val playbackMode by viewModel.playbackMode.collectAsState()
                FullScreenPlayer(
                    viewModel = viewModel,
                    playbackMode = playbackMode,
                    playlistsContainingTrack = playlistsContainingTrack,
                    track = track,
                    currentQueue = currentQueue,
                    queueTitle = queueTitle,
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
                    onAddToPlaylist = { playlist -> viewModel.addTrackToPlaylist(track, playlist) },

                    // --- НОВЫЕ КОЛЛБЭКИ ДЛЯ ПЕРЕХОДОВ ---
                    onNavigateToAlbum = { albumTitle ->
                        selectedItem = 1 // Переключаем нижнее меню на "Статистику"
                        viewModel.openArtist(track.artist) // Подгружаем артиста как фон
                        viewModel.openAlbum(albumTitle) // Поверх открываем альбом
                        showFullScreenPlayer = false // Сворачиваем плеер
                    },
                    onNavigateToArtist = { artistName ->
                        selectedItem = 1 // Переключаем нижнее меню на "Статистику"
                        viewModel.openArtist(artistName) // Открываем артиста
                        showFullScreenPlayer = false // Сворачиваем плеер
                    }
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
    viewModel: MusicViewModel,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onOpenFullScreen: () -> Unit
) {
    val coverPath = viewModel.getAlbumCoverPath(track.artist, track.album)
    val bitmap = remember(coverPath) {
        coverPath?.let { path ->
            try {
                android.graphics.BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                null
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title ?: track.fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
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
                Text(
                    text = track.artist ?: "Неизвестный",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
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
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullScreenPlayer(
    viewModel: MusicViewModel,
    playbackMode: PlaybackMode,
    playlistsContainingTrack: List<Int>,
    track: TrackEntity,
    currentQueue: List<TrackEntity>,
    queueTitle: String,
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
    onAddToPlaylist: (PlaylistEntity) -> Unit,
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {}
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    var showPlaylistSelector by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // 1. ПЕРЕХВАТ СИСТЕМНОГО ЖЕСТА "НАЗАД"
    androidx.activity.compose.BackHandler(enabled = showQueue) {
        showQueue = false
    }

    val artists by viewModel.artistsList.collectAsState()
    val artistPhotoUri = remember(artists, track.artist) {
        artists.find { it.name == track.artist }?.photoUri
    }
    val artistBitmap = remember(artistPhotoUri) {
        artistPhotoUri?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
    }

    // --- BOX-ОБЕРТКА ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (offsetY > 300f) onClose() else offsetY = 0f }
                ) { _, dragAmount -> if (dragAmount > 0 || offsetY > 0) offsetY += dragAmount }
            }
            // Чтобы системный жест Назад всё ещё работал (раньше был конфликт с открытием очереди)
            .pointerInput(Unit) {
                // Получаем ширину экрана. Мертвая зона = 120 пикселей (хватает для жеста Назад)
                val screenWidth = size.width
                val edgeZone = 120f
                var startX = 0f // Здесь будем хранить точку старта касания

                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x // Запоминаем, где палец коснулся экрана
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        // Если свайп начат НЕ у левого и НЕ у правого края
                        if (startX > edgeZone && startX < screenWidth - edgeZone) {
                            if (dragAmount < -15f) { // Потянули влево
                                showQueue = true
                            }
                        }
                    }
                )
            }
    ) {
        // --- ОСНОВНОЙ ПЛЕЕР ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ШАПКА
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .offset(y = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Свернуть", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "Очередь", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            // КАРУСЕЛЬ
            val validQueueSize = if (currentQueue.isNotEmpty()) currentQueue.size else 1
            val initialIndex = remember(track, currentQueue) {
                val idx = currentQueue.indexOf(track)
                if (idx != -1) idx else 0
            }

            val pagerState = rememberPagerState(
                initialPage = initialIndex,
                pageCount = { validQueueSize }
            )

            LaunchedEffect(track) {
                val newIndex = currentQueue.indexOf(track)
                if (newIndex != -1 && pagerState.currentPage != newIndex) {
                    pagerState.animateScrollToPage(newIndex)
                }
            }

            LaunchedEffect(pagerState.settledPage) {
                val indexInQueue = currentQueue.indexOf(track)
                if (pagerState.settledPage != indexInQueue && indexInQueue != -1 && currentQueue.isNotEmpty()) {
                    viewModel.playTrack(currentQueue[pagerState.settledPage], currentQueue)
                }
            }

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 40.dp),
                pageSpacing = 0.dp,
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            ) { page ->
                if (currentQueue.isEmpty()) return@HorizontalPager

                val pageTrack = currentQueue[page]
                val pageCoverPath = viewModel.getAlbumCoverPath(pageTrack.artist, pageTrack.album)
                val pageBitmap = remember(pageCoverPath) {
                    pageCoverPath?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
                }

                val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val scale = 1f - (0.15f * kotlin.math.abs(pageOffset)).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = if (scale < 1f) 0.5f + (0.5f * scale) else 1f
                        }
                        .shadow(12.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    if (pageBitmap != null) {
                        Image(
                            bitmap = pageBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(100.dp), tint = Color.White)
                    }
                }
            }

            // ИНФО О ТРЕКЕ
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (artistBitmap != null) {
                    Image(
                        bitmap = artistBitmap.asImageBitmap(),
                        contentDescription = "Артист",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape).clickable { track.artist?.let { onNavigateToArtist(it) } }
                    )
                } else {
                    Box(
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.LightGray).clickable { track.artist?.let { onNavigateToArtist(it) } },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Person, contentDescription = null, tint = Color.White) }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Оборачиваем название и тег в Row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.title ?: track.fileName,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable { track.album?.let { onNavigateToAlbum(it) } }
                        )
                        if (track.isDemo || track.isUnreleased) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (track.isDemo) "Demo" else "Unreleased",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Gray.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(text = track.artist ?: "Неизвестный исполнитель", fontSize = 16.sp, color = Color.Gray, maxLines = 1)
                }

                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Опции") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Изменить информацию") }, onClick = { showMenu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Добавить в плейлист") }, onClick = { showMenu = false; showPlaylistSelector = true })
                    }
                }
            }

            // ВРЕМЯ И ПОЛЗУНОК
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = currentTime, fontSize = 12.sp, color = Color.Gray)
                    Text(text = totalTime, fontSize = 12.sp, color = Color.Gray)
                }
                Slider(
                    value = progress,
                    onValueChange = onSeek,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                )
            }

            // КНОПКИ УПРАВЛЕНИЯ
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Назад", modifier = Modifier.size(48.dp)) }
                IconButton(onClick = onTogglePlayback, modifier = Modifier.size(80.dp)) { Icon(imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, contentDescription = "Play/Pause", modifier = Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Вперед", modifier = Modifier.size(48.dp)) }
            }

            // НИЖНИЕ КНОПКИ РЕЖИМОВ
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isShuffle = playbackMode == PlaybackMode.SHUFFLE
                IconButton(onClick = { onToggleMode(PlaybackMode.SHUFFLE) }, modifier = Modifier.background(if (isShuffle) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)) { Icon(Icons.Default.Shuffle, contentDescription = "Случайный порядок", tint = if (isShuffle) MaterialTheme.colorScheme.primary else Color.Gray) }
                val isRepeatAll = playbackMode == PlaybackMode.REPEAT_ALL
                IconButton(onClick = { onToggleMode(PlaybackMode.REPEAT_ALL) }, modifier = Modifier.background(if (isRepeatAll) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)) { Icon(Icons.Default.Repeat, contentDescription = "По кругу", tint = if (isRepeatAll) MaterialTheme.colorScheme.primary else Color.Gray) }
                val isRepeatOne = playbackMode == PlaybackMode.REPEAT_ONE
                IconButton(onClick = { onToggleMode(PlaybackMode.REPEAT_ONE) }, modifier = Modifier.background(if (isRepeatOne) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, CircleShape)) { Icon(Icons.Default.RepeatOne, contentDescription = "Повторять один", tint = if (isRepeatOne) MaterialTheme.colorScheme.primary else Color.Gray) }
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
                        LazyColumn {
                            items(playlists.size) { index ->
                                val playlist = playlists[index]
                                val isAlreadyAdded = playlistsContainingTrack.contains(playlist.playlistId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { if (!isAlreadyAdded) { onAddToPlaylist(playlist) }; showPlaylistSelector = false }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = playlist.name, fontSize = 18.sp)
                                    if (isAlreadyAdded) Icon(imageVector = Icons.Default.Check, contentDescription = "Добавлено", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showPlaylistSelector = false }) { Text("Отмена") } }
            )
        }

        // --- БОКОВАЯ ШТОРКА ОЧЕРЕДИ ---
        AnimatedVisibility(
            visible = showQueue,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showQueue = false }
            )
        }

        AnimatedVisibility(
            visible = showQueue,
            enter = slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }),
            exit = slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .shadow(16.dp)
                    // 2. СЕРО-ФИОЛЕТОВЫЙ ФОН: Используем системный вариант Material 3
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount > 15f) { showQueue = false } // Свайп вправо
                        }
                    }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp), // Убрали гигантский отступ сверху и снизу
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showQueue = false }) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Скрыть очередь")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Очередь", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                // 3. НОВЫЙ БЛОК: ХЕДЕР ОЧЕРЕДИ С КАРТИНКОЙ И ВЫДЕЛЕНИЕМ
                val isAllDownloaded = (queueTitle == "Все скачанные треки")
                val isArtistTracks = queueTitle.startsWith("Треки: ")
                val matchedPlaylist = if (!isAllDownloaded && !isArtistTracks) playlists.find { it.name == queueTitle } else null

                // 1. Ищем фото артиста, если сейчас играют его треки
                val headerArtistPhotoUri = remember(artists, queueTitle, isArtistTracks) {
                    if (isArtistTracks) {
                        val artistName = queueTitle.removePrefix("Треки: ").trim()
                        artists.find { it.name == artistName }?.photoUri
                    } else null
                }

                // 2. Ищем обложку плейлиста или альбома
                // Берём путь к файлу ТОЛЬКО если это не "Все скачанные треки"
                val headerCoverPath = matchedPlaylist?.imageUri ?: if (!isAllDownloaded && !isArtistTracks) viewModel.getAlbumCoverPath(track.artist, track.album) else null

                // 3. Декодируем итоговую картинку (либо артист, либо обложка)
                val headerBitmap = remember(headerCoverPath, headerArtistPhotoUri) {
                    val finalPath = headerArtistPhotoUri ?: headerCoverPath
                    finalPath?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.05f))
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {

                        // 1. Если это "Все скачанные треки"
                        if (isAllDownloaded) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
                            }
                        }
                        // 2. Если есть картинка (плейлиста, альбома или артиста)
                        else if (headerBitmap != null) {
                            Image(
                                bitmap = headerBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                // Фото артиста тоже будет стильно скругленным!
                                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).shadow(4.dp)
                            )
                        }
                        // 3. Дефолтная заглушка (если это артист без фото - иконка человека, иначе - иконка альбома)
                        else {
                            Box(
                                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(Color.LightGray),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isArtistTracks) Icons.Default.Person else Icons.Default.Album,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text("Источник", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = queueTitle, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "${currentQueue.size} треков", fontSize = 16.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // СПИСОК ТРЕКОВ
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(currentQueue) { _, queueTrack ->
                        val isPlayingThis = (track.uri == queueTrack.uri)
                        val queueCoverPath = viewModel.getAlbumCoverPath(queueTrack.artist, queueTrack.album)
                        val queueBitmap = remember(queueCoverPath) {
                            queueCoverPath?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
                        }

                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isPlayingThis) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable {
                                        viewModel.playTrack(queueTrack, currentQueue)
                                        showQueue = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (queueBitmap != null) {
                                    Image(
                                        bitmap = queueBitmap.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Default.MusicNote, contentDescription = null, tint = if (isPlayingThis) Color.White else MaterialTheme.colorScheme.primary) }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = queueTrack.title ?: queueTrack.fileName,
                                            fontSize = 16.sp,
                                            fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (queueTrack.isDemo || queueTrack.isUnreleased) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (queueTrack.isDemo) "Demo" else "Unreleased",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Gray,
                                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Gray.copy(alpha = 0.2f)).padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = queueTrack.artist ?: "Неизвестный исполнитель",
                                        fontSize = 14.sp,
                                        color = if (isPlayingThis) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.Gray,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = viewModel.formatTrackDuration(queueTrack.durationMs),
                                    fontSize = 14.sp,
                                    color = if (isPlayingThis) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
fun StubScreen(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text(
            text = subtitle,
            fontSize = 16.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// ==========================================
// ВКЛАДКА 1: МЕДИАТЕКА (С ПОИСКОМ)
// ==========================================
@Composable
fun LibraryTabScreen(viewModel: MusicViewModel) {
    val openedArtistName by viewModel.openedArtistName.collectAsState()
    val openedAlbumTitle by viewModel.openedAlbumTitle.collectAsState()
    val artists by viewModel.artistsList.collectAsState()

    val currentArtist = remember(artists, openedArtistName) { artists.find { it.name == openedArtistName } }
    val currentAlbum = remember(currentArtist, openedAlbumTitle) { currentArtist?.albums?.find { it.title == openedAlbumTitle } }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Системный "Назад" для поиска
    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    if (currentAlbum != null) {
        BackHandler { viewModel.openAlbum(null) }
        AlbumScreen(album = currentAlbum, viewModel = viewModel, onClose = { viewModel.openAlbum(null) })
    } else if (currentArtist != null) {
        BackHandler { viewModel.openArtist(null) }
        ArtistScreen(artist = currentArtist, viewModel = viewModel, onAlbumClick = { album -> viewModel.openAlbum(album.title) }, onClose = { viewModel.openArtist(null) })
    } else {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

            // ИДЕАЛЬНОЕ ВЫРАВНИВАНИЕ ШАПКИ
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Поиск исполнителя...") },
                        singleLine = true,
                        trailingIcon = { IconButton(onClick = { searchQuery = ""; isSearchActive = false }) { Icon(Icons.Default.Close, contentDescription = "Закрыть поиск") } }
                    )
                } else {
                    Text(text = "Моя медиатека", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { isSearchActive = true }) { Icon(Icons.Default.Search, contentDescription = "Поиск") }
                }
            }

            val filteredArtists = remember(artists, searchQuery) {
                if (searchQuery.isBlank()) artists else artists.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }

            if (artists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Здесь пока пусто. Добавьте музыку!", color = Color.Gray) }
            } else if (filteredArtists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Исполнитель не найден", color = Color.Gray) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredArtists) { artist ->
                        val bitmap = remember(artist.photoUri) { artist.photoUri?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } } }
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.openArtist(artist.name) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(CircleShape))
                            } else {
                                Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.LightGray), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = Color.White) }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(artist.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text("${artist.albums.size} релизов • ${artist.allTracks.size} треков", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Color.LightGray.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }
}

// ==========================================
// ВКЛАДКА 0: СТАТИСТИКА
// ==========================================
@Composable
fun RealStatsScreen(viewModel: MusicViewModel, onNavigateToLibrary: () -> Unit) {
    val currentPeriod by viewModel.currentStatsPeriod.collectAsState()
    val totalTimeMs by viewModel.totalListenTime.collectAsState()
    val addedCount by viewModel.tracksAddedCount.collectAsState()

    val topTracksTime by viewModel.topTracksStats.collectAsState()
    val topArtistsTime by viewModel.topArtistsStats.collectAsState()
    val topTracksCount by viewModel.topTracksByCountStats.collectAsState()
    val topArtistsCount by viewModel.topArtistsByCountStats.collectAsState()

    // Новые глобальные потоки
    val loyaltyArtists by viewModel.loyaltyArtists.collectAsState()
    val bingeRecord by viewModel.bingeRecord.collectAsState()

    val artists by viewModel.artistsList.collectAsState()

    var tracksSortByTime by remember { mutableStateOf(true) }
    var artistsSortByTime by remember { mutableStateOf(true) }
    var showHeatmapDialog by remember { mutableStateOf(false) }

    val periodNames = listOf("День", "Неделя", "Месяц", "Год", "Всё время")
    val periods = StatsPeriod.values()

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

        // ШАПКА С КНОПКОЙ КАЛЕНДАРЯ
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Статистика", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(
                onClick = { showHeatmapDialog = true },
                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = "Тепловая карта", tint = MaterialTheme.colorScheme.primary)
            }
        }

        ScrollableTabRow(
            selectedTabIndex = currentPeriod.ordinal,
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            indicator = {}
        ) {
            periods.forEachIndexed { index, period ->
                val isSelected = currentPeriod == period
                Tab(
                    selected = isSelected,
                    onClick = { viewModel.setStatsPeriod(period) },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                ) {
                    Text(text = periodNames[index], color = if (isSelected) Color.White else Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            // ... [1 & 2. КАРТОЧКИ ВРЕМЕНИ И ТРЕКОВ] ...
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Прослушано", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(viewModel.formatMsToHoursMinutes(totalTimeMs), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Добавлено", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("$addedCount треков", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(thickness = 4.dp, color = Color.LightGray.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ... [3. ТОП ТРЕКОВ (Зависит от времени)] ...
            val currentTopTracks = if (tracksSortByTime) topTracksTime else topTracksCount
            if (currentTopTracks.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Топ 5 треков", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = 0.3f)), verticalAlignment = Alignment.CenterVertically) {
                            Text("Время", fontSize = 11.sp, color = if (tracksSortByTime) Color.White else Color.Black, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (tracksSortByTime) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { tracksSortByTime = true }.padding(horizontal = 8.dp, vertical = 6.dp))
                            Text("Разы", fontSize = 11.sp, color = if (!tracksSortByTime) Color.White else Color.Black, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (!tracksSortByTime) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { tracksSortByTime = false }.padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                itemsIndexed(currentTopTracks) { index, trackStat ->
                    val statText = if (tracksSortByTime) viewModel.formatMsToHoursMinutes(trackStat.statValue) else "${trackStat.statValue} раз"
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${index + 1}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Gray, modifier = Modifier.width(30.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                TrackRowItem(track = trackStat.track, coverUri = viewModel.getAlbumCoverPath(trackStat.track.artist, trackStat.track.album), isPlaying = false, trailingText = statText) {
                                    viewModel.playTrack(trackStat.track, currentTopTracks.map { it.track }, forcedTitle = "Топ треков: ${periodNames[currentPeriod.ordinal]}")
                                }
                            }
                        }
                        if (index < currentTopTracks.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 46.dp), color = Color.LightGray.copy(alpha = 0.3f))
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(thickness = 4.dp, color = Color.LightGray.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // ... [4. ТОП ИСПОЛНИТЕЛЕЙ (Зависит от времени)] ...
            val currentTopArtists = if (artistsSortByTime) topArtistsTime else topArtistsCount
            if (currentTopArtists.isNotEmpty()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Топ исполнителей", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = 0.3f)), verticalAlignment = Alignment.CenterVertically) {
                            Text("Время", fontSize = 11.sp, color = if (artistsSortByTime) Color.White else Color.Black, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (artistsSortByTime) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { artistsSortByTime = true }.padding(horizontal = 8.dp, vertical = 6.dp))
                            Text("Разы", fontSize = 11.sp, color = if (!artistsSortByTime) Color.White else Color.Black, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (!artistsSortByTime) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { artistsSortByTime = false }.padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                itemsIndexed(currentTopArtists) { index, artistStat ->
                    val statText = if (artistsSortByTime) viewModel.formatMsToHoursMinutes(artistStat.statValue) else "${artistStat.statValue} раз"
                    val artistObj = remember(artists, artistStat.artist) { artists.find { it.name == artistStat.artist } }
                    val bitmap = remember(artistObj?.photoUri) { artistObj?.photoUri?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } } }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.openArtist(artistStat.artist); onNavigateToLibrary() }.padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("#${index + 1}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Gray, modifier = Modifier.width(30.dp))
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(CircleShape))
                            } else {
                                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.LightGray), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = Color.White) }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(artistStat.artist, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(statText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        if (index < currentTopArtists.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 90.dp), color = Color.LightGray.copy(alpha = 0.3f))
                    }
                }
            }

            // ==========================================
            // ГЛОБАЛЬНАЯ СТАТИСТИКА (ВНЕ ВРЕМЕНИ)
            // ==========================================
            if (loyaltyArtists.isNotEmpty() || bingeRecord != null) {
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    // Огромный жирный разделитель, чтобы показать, что тут начинаются глобальные ачивки
                    HorizontalDivider(thickness = 8.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }

            // 5. ПРЕДАННОСТЬ (За всё время)
            if (loyaltyArtists.isNotEmpty()) {
                item {
                    Text("Преданность (За всё время)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Исполнители, которых вы слушаете чаще всего изо дня в день", fontSize = 12.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                itemsIndexed(loyaltyArtists) { index, stat ->
                    val artistObj = remember(artists, stat.artist) { artists.find { it.name == stat.artist } }
                    val bitmap = remember(artistObj?.photoUri) { artistObj?.photoUri?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } } }
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.openArtist(stat.artist); onNavigateToLibrary() }.padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("#${index + 1}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Gray, modifier = Modifier.width(30.dp))
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(CircleShape))
                            } else {
                                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.LightGray), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = Color.White) }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stat.artist, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text("Слушали ${stat.daysCount} дней", fontSize = 14.sp, color = Color.Gray)
                            }
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        }
                        if (index < loyaltyArtists.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 90.dp), color = Color.LightGray.copy(alpha = 0.3f))
                    }
                }
            }

            // 6. ЗАЛИПАНИЕ (Рекорд повторов)
            if (bingeRecord != null) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(thickness = 2.dp, color = Color.LightGray.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(24.dp))

                    Text("Залипание (Рекорд на репите)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Максимальное количество воспроизведений трека подряд", fontSize = 12.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))

                    TrackRowItem(
                        track = bingeRecord!!.track,
                        coverUri = viewModel.getAlbumCoverPath(bingeRecord!!.track.artist, bingeRecord!!.track.album),
                        isPlaying = false,
                        trailingText = "${bingeRecord!!.statValue} раз"
                    ) {
                        viewModel.playTrack(bingeRecord!!.track, listOf(bingeRecord!!.track), forcedTitle = "Рекорд Залипания")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showHeatmapDialog) {
        HeatmapDialog(viewModel = viewModel, onDismiss = { showHeatmapDialog = false })
    }
}

// ==========================================
// ДИАЛОГ: ТЕПЛОВАЯ КАРТА
// ==========================================
@Composable
fun HeatmapDialog(viewModel: MusicViewModel, onDismiss: () -> Unit) {
    var selectedMode by remember { mutableIntStateOf(0) } // 0-Дни, 1-Недели, 2-Месяцы

    val days by viewModel.heatmapDays.collectAsState()
    val weeks by viewModel.heatmapWeeks.collectAsState()
    val months by viewModel.heatmapMonths.collectAsState()

    val currentData = when (selectedMode) {
        0 -> days
        1 -> weeks
        else -> months
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("История прослушиваний", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Переключатель масштаба
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = 0.2f)),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val modes = listOf("По дням", "По неделям", "По месяцам")
                    modes.forEachIndexed { index, title ->
                        val isSelected = selectedMode == index
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            color = if (isSelected) Color.White else Color.Black,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable { selectedMode = index }
                                .padding(vertical = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (currentData.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("Нет данных", color = Color.Gray)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(currentData) { stat ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stat.dateLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    text = viewModel.formatMsToHoursMinutes(stat.totalMs),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
fun ArtistScreen(
    artist: Artist,
    viewModel: MusicViewModel,
    onAlbumClick: (Album) -> Unit,
    onClose: () -> Unit
) {
    var showAllTracks by remember { mutableStateOf(false) }

    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    // --- НОВОЕ: Подтягиваем динамический топ из базы данных ---
    val dynamicTracks by viewModel.getDynamicArtistTopTracks(artist.name).collectAsState(initial = emptyList())
    // Если БД еще грузится или треков в истории нет, показываем дефолтные все треки
    val finalTracks = if (dynamicTracks.isNotEmpty()) dynamicTracks else artist.allTracks
    // Обрезаем до 5, если не нажата кнопка "Все"
    val displayedTracks = if (showAllTracks) finalTracks else finalTracks.take(5)

    val photoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { viewModel.setArtistPhoto(artist.name, it) }
        }

    val bitmap = remember(artist.photoUri) {
        artist.photoUri?.let { path ->
            try {
                android.graphics.BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                null
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color.DarkGray)) {
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
                        modifier = Modifier
                            .size(100.dp)
                            .align(Alignment.Center)
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
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .statusBarsPadding() // Отступ для кнопок, чтобы картинка осталась сзади
                        .padding(top = 12.dp, start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Назад",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { photoPickerLauncher.launch("image/*") }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Изменить фото",
                            tint = Color.White
                        )
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
                            if (finalTracks.isNotEmpty()) {
                                viewModel.playTrack(finalTracks.first(), finalTracks)
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Играть",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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
                        Text(
                            if (showAllTracks) "Свернуть" else "Все (${artist.allTracks.size})",
                            fontSize = 14.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        items(displayedTracks) { track ->
            val trackCoverUri = artist.albums.find { it.title == track.album }?.coverUri
            val isPlaying = (currentTrack?.uri == track.uri)

            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrackRowItem(track = track, coverUri = trackCoverUri, isPlaying = isPlaying) {
                    viewModel.playTrack(track, artist.allTracks)
                }
            }
        }

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
                // Проверяем, играет ли сейчас этот альбом
                val isThisAlbumContext = currentTrack?.album == album.title

                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AlbumRowItem(
                        album = album,
                        isPlayingContext = isThisAlbumContext && isPlaying, // Передаем статус
                        onClick = { onAlbumClick(album) },
                        onPlayClick = {
                            if (isThisAlbumContext) {
                                viewModel.togglePlayback() // Если это наш альбом - просто ставим паузу/плей
                            } else {
                                val allTracks = (album.regularTracks + album.demoTracks + album.unreleasedTracks).sortedBy { it.albumOrder }
                                if (allTracks.isNotEmpty()) {
                                    viewModel.playTrack(allTracks.first(), allTracks)
                                }
                            }
                        }
                    )
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
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isThisAlbumContext = currentTrack?.album == album.title

    val photoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { viewModel.setAlbumPhoto(album.artist, album.title, it) }
        }

    val bitmap = remember(album.coverUri) {
        album.coverUri?.let { path ->
            try {
                android.graphics.BitmapFactory.decodeFile(path)
            } catch (e: Exception) {
                null
            }
        }
    }

    if (showEditDialog) {
        EditAlbumDialog(
            album = album,
            viewModel = viewModel,
            onDismiss = { showEditDialog = false })
    }

    // --- НОВОЕ: ЖЕСТКАЯ СОРТИРОВКА ТРЕКОВ ПО СОХРАНЕННОМУ ПОРЯДКУ ---
    val sortedRegular =
        remember(album.regularTracks) { album.regularTracks.sortedBy { it.albumOrder } }
    val sortedDemo = remember(album.demoTracks) { album.demoTracks.sortedBy { it.albumOrder } }
    val sortedUnreleased =
        remember(album.unreleasedTracks) { album.unreleasedTracks.sortedBy { it.albumOrder } }

    // Собираем их в один правильный плейлист для плеера
    val allAlbumTracksSorted = remember(sortedRegular, sortedDemo, sortedUnreleased) {
        sortedRegular + sortedDemo + sortedUnreleased
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(Color(0xFF242424))
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Назад",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Редактировать альбом",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

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
                                Icon(
                                    Icons.Default.Album,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    "Нажмите, чтобы добавить фото",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = album.title,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            val totalTracks = album.regularTracks.size + album.demoTracks.size + album.unreleasedTracks.size
                            val albumType = if (totalTracks == 1) "Сингл" else "Альбом"

                            Text(
                                text = "${album.artist} • $albumType • ${album.year ?: "Неизвестный год"}",
                                fontSize = 16.sp,
                                color = Color.LightGray
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // === ВОТ ТА САМАЯ УМНАЯ КНОПКА PLAY/PAUSE ===
                        IconButton(
                            onClick = {
                                if (isThisAlbumContext) {
                                    viewModel.togglePlayback()
                                } else if (allAlbumTracksSorted.isNotEmpty()) {
                                    viewModel.playTrack(allAlbumTracksSorted.first(), allAlbumTracksSorted)
                                }
                            },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = if (isThisAlbumContext && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Играть/Пауза",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ==========================================
        // ОСНОВНЫЕ ТРЕКИ АЛЬБОМА (Теперь отсортированы!)
        // ==========================================
        items(sortedRegular) { track ->
            val isPlayingTrack = (currentTrack?.uri == track.uri)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrackRowItem(track = track, coverUri = album.coverUri, isPlaying = isPlayingTrack) {
                    viewModel.playTrack(
                        track,
                        allAlbumTracksSorted
                    ) // Передаем отсортированную очередь!
                }
            }
        }

        // ==========================================
        // СЕКЦИЯ DEMO (Теперь отсортирована!)
        // ==========================================
        if (album.hasDemosEnabled && sortedDemo.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                    Text(
                        text = "Demo",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(sortedDemo) { track ->
                val isPlayingTrack = (currentTrack?.uri == track.uri)
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TrackRowItem(track = track, coverUri = album.coverUri, isPlaying = isPlayingTrack) {
                        viewModel.playTrack(track, allAlbumTracksSorted)
                    }
                }
            }
        }

        // ==========================================
        // СЕКЦИЯ UNRELEASED (Теперь отсортирована!)
        // ==========================================
        if (album.hasUnreleasedEnabled && sortedUnreleased.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                    Text(
                        text = "Unreleased",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.LightGray)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            items(sortedUnreleased) { track ->
                val isPlayingTrack = (currentTrack?.uri == track.uri)
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TrackRowItem(track = track, coverUri = album.coverUri, isPlaying = isPlayingTrack) {
                        viewModel.playTrack(track, allAlbumTracksSorted)
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
    trailingText: String? = null,
    onClick: () -> Unit
) {
    val bitmap = remember(coverUri) { coverUri?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } } }
    val safeTitle = track.title?.takeIf { it.isNotBlank() } ?: track.fileName
    val safeArtist = track.artist?.takeIf { it.isNotBlank() } ?: "Неизвестный исполнитель"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPlaying) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(if (isPlaying) MaterialTheme.colorScheme.primary else Color.LightGray), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = Color.White) }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = safeTitle, fontSize = 16.sp, fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium, color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (track.isDemo || track.isUnreleased) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (track.isDemo) "Demo" else "Unreleased", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.Gray.copy(alpha = 0.2f)).padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = safeArtist, fontSize = 14.sp, color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // --- ВЫВОДИМ ЦИФРЫ ДЛЯ СТАТИСТИКИ ---
        if (trailingText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = trailingText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AlbumRowItem(album: Album, isPlayingContext: Boolean, onClick: () -> Unit, onPlayClick: () -> Unit) {
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
            ) { Icon(Icons.Default.Album, contentDescription = null, tint = Color.White) }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(album.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            val totalTracks = album.regularTracks.size + album.demoTracks.size + album.unreleasedTracks.size
            val typeText = if (totalTracks == 1) "Сингл" else "$totalTracks треков"
            Text("${album.year ?: "Год не указан"} • $typeText", fontSize = 14.sp, color = Color.Gray)
        }

        IconButton(
            onClick = onPlayClick,
            modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(
                imageVector = if (isPlayingContext) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Играть/Пауза",
                tint = MaterialTheme.colorScheme.primary
            )
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

    // Собираем треки и сразу сортируем их в правильном порядке
    val allTracks = remember(album) {
        (album.regularTracks + album.demoTracks + album.unreleasedTracks).sortedBy { it.albumOrder }
    }

    // Используем изменяемый список, чтобы треки двигались на экране при клике
    val editableTracks = remember { mutableStateListOf(*allTracks.toTypedArray()) }

    val trackTypes = remember {
        mutableStateMapOf<String, String>().apply {
            editableTracks.forEach { track ->
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )
                    Text("Порядок и типы треков:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                itemsIndexed(editableTracks, key = { _, track -> track.uri }) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.title ?: track.fileName, fontWeight = FontWeight.Medium, maxLines = 1)

                            // Скроллируемый ряд чипов, чтобы не ломался интерфейс
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(selected = trackTypes[track.uri] == "REGULAR", onClick = { trackTypes[track.uri] = "REGULAR" }, label = { Text("Обычный", fontSize = 11.sp) })
                                FilterChip(selected = trackTypes[track.uri] == "DEMO", onClick = { trackTypes[track.uri] = "DEMO" }, label = { Text("Demo", fontSize = 11.sp) })
                                FilterChip(selected = trackTypes[track.uri] == "UNRELEASED", onClick = { trackTypes[track.uri] = "UNRELEASED" }, label = { Text("Unreleased", fontSize = 11.sp) })
                            }
                        }

                        // Кнопки перемещения
                        Column {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Вверх",
                                tint = if (index > 0) Color.Gray else Color.LightGray,
                                modifier = Modifier.clickable(enabled = index > 0) {
                                    val movedTrack = editableTracks.removeAt(index)
                                    editableTracks.add(index - 1, movedTrack)
                                }
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Вниз",
                                tint = if (index < editableTracks.size - 1) Color.Gray else Color.LightGray,
                                modifier = Modifier.clickable(enabled = index < editableTracks.size - 1) {
                                    val movedTrack = editableTracks.removeAt(index)
                                    editableTracks.add(index + 1, movedTrack)
                                }
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
                    trackTypesMap = trackTypes,
                    orderedUris = editableTracks.map { it.uri } // Передаем итоговый порядок списка!
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
