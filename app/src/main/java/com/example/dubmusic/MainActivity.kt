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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex

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
    val appearances: List<Album> = emptyList(), // список фитов
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
                        selectedItem = 1 // Переключаем нижнее меню на "Медиатеку"

                        // ИСПРАВЛЕНИЕ: Берем только первого (главного) артиста, отсекая гостей!
                        val primaryArtist = track.artist?.split(",")?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
                        viewModel.openAlbum(albumTitle, primaryArtist) // Передаем сразу и альбом, и артиста

                        showFullScreenPlayer = false // Сворачиваем плеер
                    },
                    onNavigateToArtist = { artistName ->
                        selectedItem = 1 // Железно переключаем нижнее меню на "Медиатеку"
                        viewModel.openArtist(artistName) // Открываем конкретного гостя
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
            viewModel = viewModel,
            onSave = { title, artist, album, isDemo, isUnreleased ->
                viewModel.processTrack(track, title, artist, album, isDemo = isDemo, isUnreleased = isUnreleased)
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
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TrackBadge(isDemo = track.isDemo, isUnreleased = track.isUnreleased)
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

    // --- Состояния текста песни ---
    var showLyrics by remember { mutableStateOf(false) }
    var showLyricsEditDialog by remember { mutableStateOf(false) }

    // 1. ПЕРЕХВАТ СИСТЕМНОГО ЖЕСТА "НАЗАД"
    androidx.activity.compose.BackHandler(enabled = true) {
        if (showQueue) {
            showQueue = false
        } else if (showLyrics) {
            showLyrics = false
        } else {
            onClose()
        }
    }

    val artists by viewModel.artistsList.collectAsState()
    val artistPhotoUri = remember(artists, track.artist) {
        artists.find { it.name == track.artist }?.photoUri
    }
    val artistBitmap = remember(artistPhotoUri) {
        artistPhotoUri?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
    }

    // --- BOX-ОБЕРТКА ВСЕГО ПЛЕЕРА ---
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
            .pointerInput(Unit) {
                // ИСПРАВЛЕННЫЕ СВАЙПЫ
                val screenWidth = size.width.toFloat()
                val edgeZone = 120f
                var startX = 0f

                detectHorizontalDragGestures(
                    onDragStart = { offset -> startX = offset.x },
                    onHorizontalDrag = { _, dragAmount ->
                        if (startX > edgeZone && startX < screenWidth - edgeZone) {
                            if (dragAmount < -15f) { showQueue = true } // Свайп влево
                            if (dragAmount > 15f) { showLyrics = true } // Свайп вправо
                        }
                    }
                )
            }
    ) {
        // ==========================================
        // ОСНОВНОЙ ПЛЕЕР
        // ==========================================
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
                val artistNames = track.artist?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: listOf("Неизвестный исполнитель")
                var showArtistMenu by remember { mutableStateOf(false) }

                Box {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.clickable {
                            if (artistNames.size > 1) showArtistMenu = true else onNavigateToArtist(artistNames.first())
                        }
                    ) {
                        if (artistNames.size > 1) {
                            val pic2 = viewModel.getArtistPhotoPath(artistNames[1])
                            val bmp2 = remember(pic2) { pic2?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } } }

                            Box(
                                modifier = Modifier
                                    .padding(start = 24.dp)
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(Color.LightGray)
                                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bmp2 != null) Image(bitmap = bmp2.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                            }
                        }

                        val pic1 = viewModel.getArtistPhotoPath(artistNames[0])
                        val bmp1 = remember(pic1) { pic1?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } } }

                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Gray)
                                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bmp1 != null) Image(bitmap = bmp1.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                        }
                    }

                    DropdownMenu(expanded = showArtistMenu, onDismissRequest = { showArtistMenu = false }) {
                        artistNames.forEach { aName ->
                            DropdownMenuItem(
                                text = { Text(aName) },
                                onClick = { showArtistMenu = false; onNavigateToArtist(aName) },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp)) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.title ?: track.fileName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { track.album?.let { onNavigateToAlbum(it) } }
                        )
                        TrackBadge(isDemo = track.isDemo, isUnreleased = track.isUnreleased)
                    }
                    Text(
                        text = track.artist ?: "Неизвестный исполнитель",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                IconButton(onClick = { showLyrics = true }, modifier = Modifier.background(Color.Transparent, CircleShape)) {Icon(Icons.Default.Subject, contentDescription = "Текст песни", tint = Color.Gray, modifier = Modifier.size(24.dp)) }
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

        // ==========================================
        // БОКОВАЯ ШТОРКА ОЧЕРЕДИ
        // ==========================================
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showQueue = false }) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Скрыть очередь")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Очередь", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                val isAllDownloaded = (queueTitle == "Все скачанные треки")
                val isArtistTracks = queueTitle.startsWith("Треки: ")
                val matchedPlaylist = if (!isAllDownloaded && !isArtistTracks) playlists.find { it.name == queueTitle } else null
                val headerArtistPhotoUri = remember(artists, queueTitle, isArtistTracks) {
                    if (isArtistTracks) {
                        val artistName = queueTitle.removePrefix("Треки: ").trim()
                        artists.find { it.name == artistName }?.photoUri
                    } else null
                }
                val headerCoverPath = matchedPlaylist?.imageUri ?: if (!isAllDownloaded && !isArtistTracks) viewModel.getAlbumCoverPath(track.artist, track.album) else null
                val headerBitmap = remember(headerCoverPath, headerArtistPhotoUri) {
                    val finalPath = headerArtistPhotoUri ?: headerCoverPath
                    finalPath?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
                }

                Box(
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.05f)).padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isAllDownloaded) {
                            Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
                            }
                        } else if (headerBitmap != null) {
                            Image(bitmap = headerBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).shadow(4.dp))
                        } else {
                            Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(Color.LightGray), contentAlignment = Alignment.Center) {
                                Icon(imageVector = if (isArtistTracks) Icons.Default.Person else Icons.Default.Album, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
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
                                    .clickable { viewModel.playTrack(queueTrack, currentQueue); showQueue = false }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (queueBitmap != null) {
                                    Image(bitmap = queueBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                                } else {
                                    Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = if (isPlayingThis) Color.White else MaterialTheme.colorScheme.primary)
                                    }
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
                                Text(text = viewModel.formatTrackDuration(queueTrack.durationMs), fontSize = 14.sp, color = if (isPlayingThis) MaterialTheme.colorScheme.primary else Color.Gray)
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }

        // ==========================================
        // БОКОВАЯ ШТОРКА ТЕКСТА ПЕСНИ (СИММЕТРИЧНО)
        // ==========================================
        AnimatedVisibility(
            visible = showLyrics,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showLyrics = false }
            )
        }

        AnimatedVisibility(
            visible = showLyrics,
            enter = slideInHorizontally(initialOffsetX = { fullWidth -> -fullWidth }), // Выезжает СЛЕВА
            exit = slideOutHorizontally(targetOffsetX = { fullWidth -> -fullWidth }),
            modifier = Modifier.align(Alignment.CenterStart) // ПРАВИЛЬНОЕ ПРИЖАТИЕ ВЛЕВО
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .shadow(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount < -15f) { showLyrics = false } // Свайп влево, чтобы закрыть
                        }
                    }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                    .statusBarsPadding()
            ) {
                // 1. ШАПКА ШТОРКИ
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Текст песни", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { showLyrics = false }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Скрыть")
                    }
                }

                // 2. ИНФОРМАЦИЯ О ПЕСНЕ
                val lyricsCoverPath = viewModel.getAlbumCoverPath(track.artist, track.album)
                val lyricsBitmap = remember(lyricsCoverPath) {
                    lyricsCoverPath?.let { path -> try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
                }

                Box(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.05f)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (lyricsBitmap != null) {
                            Image(bitmap = lyricsBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).shadow(4.dp))
                        } else {
                            Box(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(Color.LightGray), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Сейчас играет", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = track.title ?: track.fileName, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = track.artist ?: "Неизвестный", fontSize = 16.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        IconButton(onClick = { showLyricsEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Редактировать", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // 3. САМ ТЕКСТ ИЛИ ЗАГЛУШКА
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    if (!track.lyrics.isNullOrBlank()) {
                        Text(
                            text = track.lyrics,
                            fontSize = 14.sp, // <--- Уменьшили размер с 16.sp до 14.sp
                            lineHeight = 22.sp, // <--- Скорректировали межстрочный интервал
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Subject, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.Gray.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Текста пока нет", color = Color.Gray, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            FilledTonalButton(onClick = { showLyricsEditDialog = true }) {
                                Text("Добавить текст")
                            }
                        }
                    }
                }
            }
        }
    } // Конец главного Box() плеера

    // ==========================================
    // ДИАЛОГ РЕДАКТИРОВАНИЯ ТЕКСТА (ВНЕ ГЛАВНОГО ОКНА)
    // ==========================================
    if (showLyricsEditDialog) {
        var lyricsText by remember { mutableStateOf(track.lyrics ?: "") }
        AlertDialog(
            onDismissRequest = { showLyricsEditDialog = false },
            title = { Text("Текст песни", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = lyricsText,
                    onValueChange = { lyricsText = it },
                    modifier = Modifier.fillMaxWidth().height(250.dp),
                    placeholder = { Text("Вставь текст песни сюда...") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveLyrics(track, lyricsText)
                    showLyricsEditDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { showLyricsEditDialog = false }) { Text("Отмена") }
            }
        )
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
    val currentAlbum = remember(currentArtist, openedAlbumTitle) {
        currentArtist?.albums?.find { it.title == openedAlbumTitle }
            ?: currentArtist?.appearances?.find { it.title == openedAlbumTitle }
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // ЗАПОМИНАЕМ ПОСЛЕДНЕЕ СОСТОЯНИЕ ДЛЯ ПЛАВНОЙ АНИМАЦИИ ЗАКРЫТИЯ
    val displayArtist = remember { mutableStateOf(currentArtist) }
    if (currentArtist != null) displayArtist.value = currentArtist

    val displayAlbum = remember { mutableStateOf(currentAlbum) }
    if (currentAlbum != null) displayAlbum.value = currentAlbum

    // Системный "Назад" для поиска
    BackHandler(enabled = isSearchActive && currentArtist == null && currentAlbum == null) {
        isSearchActive = false
        searchQuery = ""
    }

    // Системный "Назад" для артиста
    BackHandler(enabled = currentArtist != null && currentAlbum == null) {
        viewModel.openArtist(null)
    }

    // Системный "Назад" для альбома
    BackHandler(enabled = currentAlbum != null) {
        viewModel.openAlbum(null)
    }

    // ИСПОЛЬЗУЕМ BOX ДЛЯ НАСЛОЕНИЯ ЭКРАНОВ (чтобы нижние не удалялись из памяти)
    Box(modifier = Modifier.fillMaxSize()) {

        // 1. БАЗОВЫЙ ЭКРАН БИБЛИОТЕКИ (Всегда в самом низу)
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.openArtist(artist.name) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (artist.photoUri != null) {
                                androidx.compose.foundation.Image(
                                    painter = coil.compose.rememberAsyncImagePainter(java.io.File(artist.photoUri)),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp).clip(CircleShape)
                                )
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

        // 2. ЭКРАН АРТИСТА (Поверх библиотеки)
        AnimatedVisibility(
            visible = currentArtist != null,
            enter = slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }),
            exit = slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth })
        ) {
            displayArtist.value?.let { artist -> // <--- ИСПОЛЬЗУЕМ ЗАПОМНЕННОЕ
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ArtistScreen(artist = artist, viewModel = viewModel, onAlbumClick = { album -> viewModel.openAlbum(album.title) }, onClose = { viewModel.openArtist(null) })
                }
            }
        }

        // 3. ЭКРАН АЛЬБОМА (Поверх всего)
        AnimatedVisibility(
            visible = currentAlbum != null,
            enter = slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }),
            exit = slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth })
        ) {
            displayAlbum.value?.let { album -> // <--- ИСПОЛЬЗУЕМ ЗАПОМНЕННОЕ
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AlbumScreen(album = album, viewModel = viewModel, onClose = { viewModel.openAlbum(null) })
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
    val bingeRecords by viewModel.bingeRecords.collectAsState()

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
            if (loyaltyArtists.isNotEmpty() || bingeRecords.isNotEmpty()) {
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

            // 6. ЗАЛИПАНИЕ (Рекорды повторов)
            if (bingeRecords.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(thickness = 2.dp, color = Color.LightGray.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(24.dp))

                    Text("Залипание (На репите)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Треки, которые вы слушали на повторе дольше всего", fontSize = 12.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                itemsIndexed(bingeRecords) { index, record ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${index + 1}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Gray, modifier = Modifier.width(30.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                TrackRowItem(
                                    track = record.track,
                                    coverUri = viewModel.getAlbumCoverPath(record.track.artist, record.track.album),
                                    isPlaying = false,
                                    trailingText = "${record.statValue} раз"
                                ) {
                                    // При клике включаем весь топ-5 как плейлист!
                                    viewModel.playTrack(record.track, bingeRecords.map { it.track }, forcedTitle = "Топ Залипаний")
                                }
                            }
                        }
                        if (index < bingeRecords.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 46.dp), color = Color.LightGray.copy(alpha = 0.3f))
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

    // Подтягиваем динамический топ из базы данных
    val dynamicTracks by viewModel.getDynamicArtistTopTracks(artist.name).collectAsState(initial = artist.topTracks)

    val finalTracks = remember(dynamicTracks, artist.allTracks) {
        val historyUris = dynamicTracks.map { it.uri }
        val missingTracks = artist.allTracks.filter { it.uri !in historyUris }
        (dynamicTracks + missingTracks)
    }
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
                                viewModel.playTrack(
                                    track = finalTracks.first(),
                                    playlist = finalTracks,
                                    forcedTitle = "Треки: ${artist.name}"
                                )
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(color = MaterialTheme.colorScheme.primary)
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

        itemsIndexed(displayedTracks) { index, track ->
            // Ищем обложку глобально через ViewModel (теперь фиты работают)
            val trackCoverUri = viewModel.getAlbumCoverPath(track.artist, track.album)
            val isPlayingTrack = (currentTrack?.uri == track.uri)

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                TrackRowItem(track = track, coverUri = trackCoverUri, isPlaying = isPlayingTrack) {
                    // ИСПРАВЛЕНИЕ 2: Передаем forcedTitle
                    viewModel.playTrack(
                        track = track,
                        playlist = artist.allTracks,
                        forcedTitle = "Треки: ${artist.name}"
                    )
                }
                // ВОЗВРАЩАЕМ РАЗДЕЛИТЕЛЬ ДЛЯ ТРЕКОВ
                if (index < displayedTracks.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 66.dp), color = Color.LightGray.copy(alpha = 0.3f))
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
            itemsIndexed(artist.albums) { index, album ->
                val isThisAlbumContext = currentTrack?.album == album.title

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AlbumRowItem(
                        album = album,
                        isPlayingContext = isThisAlbumContext && isPlaying,
                        onClick = { onAlbumClick(album) },
                        onPlayClick = {
                            if (isThisAlbumContext) {
                                viewModel.togglePlayback()
                            } else {
                                val allTracks = (album.regularTracks + album.demoTracks + album.unreleasedTracks).sortedBy { it.albumOrder }
                                if (allTracks.isNotEmpty()) viewModel.playTrack(allTracks.first(), allTracks)
                            }
                        }
                    )
                    // ВОЗВРАЩАЕМ РАЗДЕЛИТЕЛЬ ДЛЯ АЛЬБОМОВ
                    if (index < artist.albums.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = Color.LightGray.copy(alpha = 0.3f))
                    }
                }
            }

            // --- НОВАЯ СЕКЦИЯ: УЧАСТИЕ В РЕЛИЗАХ (ФИТЫ) ---
            if (artist.appearances.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Участие в релизах",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                itemsIndexed(artist.appearances) { index, album ->
                    val isThisAlbumContext = currentTrack?.album == album.title

                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        AlbumRowItem(
                            album = album,
                            isPlayingContext = isThisAlbumContext && isPlaying,
                            onClick = { onAlbumClick(album) },
                            onPlayClick = {
                                if (isThisAlbumContext) {
                                    viewModel.togglePlayback()
                                } else {
                                    val allTracks = (album.regularTracks + album.demoTracks + album.unreleasedTracks).sortedBy { it.albumOrder }
                                    if (allTracks.isNotEmpty()) viewModel.playTrack(allTracks.first(), allTracks)
                                }
                            }
                        )
                        // РАЗДЕЛИТЕЛЬ ДЛЯ ФИТОВ
                        if (index < artist.appearances.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = Color.LightGray.copy(alpha = 0.3f))
                        }
                    }
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
                            val albumType = if (album.title == "Синглы") "Сингл" else "Альбом"

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
        // --- ИСПОЛЬЗУЕМ АСИНХРОННЫЙ COIL ДЛЯ ИДЕАЛЬНОЙ ПРОКРУТКИ ---
        if (coverUri != null) {
            androidx.compose.foundation.Image(
                painter = coil.compose.rememberAsyncImagePainter(java.io.File(coverUri)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(if (isPlaying) MaterialTheme.colorScheme.primary else Color.LightGray), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = Color.White) }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = safeTitle,
                    fontSize = 16.sp,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TrackBadge(isDemo = track.isDemo, isUnreleased = track.isUnreleased)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = safeArtist, fontSize = 14.sp, color = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else Color.Gray, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }

        if (trailingText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = trailingText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AlbumRowItem(album: Album, isPlayingContext: Boolean, onClick: () -> Unit, onPlayClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // --- ИСПОЛЬЗУЕМ АСИНХРОННЫЙ COIL ДЛЯ ИДЕАЛЬНОЙ ПРОКРУТКИ ---
        if (album.coverUri != null) {
            androidx.compose.foundation.Image(
                painter = coil.compose.rememberAsyncImagePainter(java.io.File(album.coverUri)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) { Icon(Icons.Default.Album, contentDescription = null, tint = Color.White) }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(album.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            val totalTracks = album.regularTracks.size + album.demoTracks.size + album.unreleasedTracks.size
            val albumType = if (album.title == "Синглы") "Сингл" else "Альбом"
            Text("${album.year ?: "Год не указан"} • $albumType • $totalTracks треков", fontSize = 14.sp, color = Color.Gray)
        }

        IconButton(onClick = onPlayClick, modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)) {
            Icon(imageVector = if (isPlayingContext) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Играть/Пауза", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun EditAlbumDialog(
    album: Album,
    onDismiss: () -> Unit,
    viewModel: MusicViewModel
) {
    var yearText by remember { mutableStateOf(album.year?.toString() ?: "") }

    val editableRegular = remember { mutableStateListOf(*album.regularTracks.sortedBy { it.albumOrder }.toTypedArray()) }
    val editableDemo = remember { mutableStateListOf(*album.demoTracks.sortedBy { it.albumOrder }.toTypedArray()) }
    val editableUnreleased = remember { mutableStateListOf(*album.unreleasedTracks.sortedBy { it.albumOrder }.toTypedArray()) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки альбома") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = yearText,
                    onValueChange = { yearText = it },
                    label = { Text("Год выпуска") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Порядок треков:", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {

                    if (editableRegular.isNotEmpty()) {
                        item { Text("ОСНОВНЫЕ ТРЕКИ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                        items(editableRegular, key = { it.uri }) { track ->
                            TrackEditRow(track, editableRegular, listState)
                        }
                    }

                    if (editableDemo.isNotEmpty()) {
                        item { Text("DEMO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                        items(editableDemo, key = { it.uri }) { track ->
                            TrackEditRow(track, editableDemo, listState)
                        }
                    }

                    if (editableUnreleased.isNotEmpty()) {
                        item { Text("UNRELEASED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                        items(editableUnreleased, key = { it.uri }) { track ->
                            TrackEditRow(track, editableUnreleased, listState)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val finalUris = (editableRegular + editableDemo + editableUnreleased).map { it.uri }
                viewModel.updateAlbumDetails(
                    album = album,
                    newYear = yearText.toIntOrNull(),
                    orderedUris = finalUris
                )
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun TrackEditRow(
    track: TrackEntity,
    currentList: MutableList<TrackEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var itemHeightPx by remember { mutableFloatStateOf(120f) }

    val elevation by androidx.compose.animation.core.animateDpAsState(if (isDragging) 12.dp else 0.dp)
    val scale by androidx.compose.animation.core.animateFloatAsState(if (isDragging) 1.05f else 1f)

    fun checkAndSwap() {
        val threshold = itemHeightPx / 2
        if (dragOffsetY > threshold && currentIndex < currentList.lastIndex) {
            val movedTrack = currentList.removeAt(currentIndex)
            currentList.add(currentIndex + 1, movedTrack)
            currentIndex++
            dragOffsetY -= itemHeightPx
        } else if (dragOffsetY < -threshold && currentIndex > 0) {
            val movedTrack = currentList.removeAt(currentIndex)
            currentList.add(currentIndex - 1, movedTrack)
            currentIndex--
            dragOffsetY += itemHeightPx
        }
    }

    LaunchedEffect(isDragging) {
        while (isDragging) {
            val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.key == track.uri }
            if (itemInfo != null) {
                val currentY = itemInfo.offset.toFloat() + dragOffsetY
                val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()

                val scrollSpeed = when {
                    viewportHeight < 300f -> 0f // Защита: если список крошечный, вообще не пытаемся скроллить
                    currentY < 100f -> -15f
                    currentY > viewportHeight - 150f -> 15f
                    else -> 0f
                }
                if (scrollSpeed != 0f) {
                    // 1. Пытаемся прокрутить список и узнаем РЕАЛЬНОЕ пройденное расстояние
                    val consumed = listState.scrollBy(scrollSpeed)
                    // 2. Сдвигаем трек только если список реально прокрутился!
                    dragOffsetY += consumed
                    if (consumed != 0f) {
                        checkAndSwap()
                    }
                }
            }
            kotlinx.coroutines.delay(16)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { itemHeightPx = it.size.height.toFloat() }
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY // ВОЗВРАЩАЕМ СДВИГ ЗА ПАЛЬЦЕМ!
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, RoundedCornerShape(8.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(vertical = 8.dp, horizontal = if (isDragging) 8.dp else 0.dp)
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Перетащить",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(28.dp)
                .pointerInput(currentList) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            isDragging = true
                            currentIndex = currentList.indexOf(track)
                            dragOffsetY = 0f
                        },
                        onDragEnd = { isDragging = false; dragOffsetY = 0f },
                        onDragCancel = { isDragging = false; dragOffsetY = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetY += dragAmount
                            checkAndSwap()
                        }
                    )
                }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title ?: track.fileName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (track.isDemo) Text("DEMO", fontSize = 10.sp, color = Color.Gray)
                if (track.isUnreleased) Text("UNRELEASED", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun TrackBadge(isDemo: Boolean, isUnreleased: Boolean) {
    if (!isDemo && !isUnreleased) return

    Spacer(modifier = Modifier.width(6.dp))
    Box(
        modifier = Modifier
            .height(14.dp) // Жестко ограничиваем высоту
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Gray.copy(alpha = 0.6f))
            .padding(horizontal = 4.dp), // Отступы только по бокам
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isDemo) "DEMO" else "UNRELEASED",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            style = androidx.compose.ui.text.TextStyle(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                lineHeight = 9.sp
            )
        )
    }
}
