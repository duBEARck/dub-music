package com.example.dubmusic

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable
fun SavedTabScreen(viewModel: MusicViewModel) {
    var trackToEdit by remember { mutableStateOf<TrackEntity?>(null) }

    // ПАМЯТЬ ДЛЯ ОТКРЫТЫХ ПАПОК (Изначально пусто -> все папки свернуты)
    var expandedFolders by remember { mutableStateOf(setOf<String>()) }

    val context = LocalContext.current
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { folderUri ->
        folderUri?.let { uri ->
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                viewModel.scanAndHealFolder(context, uri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val processedTracks by viewModel.processedTracks.collectAsState(initial = emptyList())
    val unprocessedTracks by viewModel.unprocessedTracks.collectAsState(initial = emptyList())
    val currentTrack by viewModel.currentTrack.collectAsState()

    val tracksByFolder = remember(processedTracks, unprocessedTracks) {
        // УМНАЯ СОРТИРОВКА (Natural Sort: 1, 2, 10 вместо 1, 10, 2)
        val naturalSortComparator = Comparator<String> { s1, s2 ->
            val num1 = s1.takeWhile { it.isDigit() }.toIntOrNull()
            val num2 = s2.takeWhile { it.isDigit() }.toIntOrNull()
            when {
                num1 != null && num2 != null -> {
                    val cmp = num1.compareTo(num2)
                    if (cmp != 0) cmp else s1.compareTo(s2, ignoreCase = true)
                }
                num1 != null -> -1 // Если только у s1 есть число, он идет выше
                num2 != null -> 1  // Если только у s2 есть число, он идет выше
                else -> s1.compareTo(s2, ignoreCase = true) // Обычный алфавит
            }
        }

        val combinedList = processedTracks.map { it to true } + unprocessedTracks.map { it to false }
        combinedList
            .groupBy { viewModel.getFolderNameFromUri(it.first.uri) }
            .toSortedMap(naturalSortComparator) // Применяем нашу умную сортировку
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // ШАПКА С КНОПКОЙ БЫСТРОГО ОБНОВЛЕНИЯ И ДОБАВЛЕНИЯ ПАПКИ
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Медиатека", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))

            // Кнопка быстрого сканирования уже разрешённой папки в 1 клик
            FilledTonalIconButton(
                onClick = { viewModel.refreshGrantedFolders(context) }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить папку")
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledTonalButton(
                onClick = { folderPickerLauncher.launch(null) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Добавить папку")
            }
        }

        if (tracksByFolder.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Здесь пока пусто.\nЗагрузите папку с музыкой.", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                tracksByFolder.forEach { (folderName, tracksInFolder) ->
                    // Проверяем, есть ли папка в списке ОТКРЫТЫХ
                    val isExpanded = expandedFolders.contains(folderName)

                    // ЗАГОЛОВОК ПАПКИ
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    // Логика сворачивания/разворачивания
                                    expandedFolders = if (isExpanded) expandedFolders - folderName else expandedFolders + folderName
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = folderName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Text("${tracksInFolder.size}", fontSize = 14.sp, color = Color.Gray)
                        }
                    }

                    // ТРЕКИ ВНУТРИ ПАПКИ (Рисуем ТОЛЬКО если папка открыта)
                    // ТРЕКИ ВНУТРИ ПАПКИ (Рисуем ТОЛЬКО если папка открыта)
                    if (isExpanded) {
                        items(tracksInFolder, key = { it.first.uri }) { (track, isProcessed) ->
                            if (isProcessed) {
                                val coverPath = viewModel.getAlbumCoverPath(track)
                                val isPlayingThis = currentTrack?.uri == track.uri

                                Column {
                                    TrackRowItem(
                                        track = track,
                                        coverUri = coverPath,
                                        isPlaying = isPlayingThis,
                                        viewModel = viewModel
                                    ) {
                                        viewModel.playTrack(
                                            track = track,
                                            playlist = tracksInFolder.map { it.first },
                                            forcedTitle = "Папка: $folderName"
                                        )
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 80.dp, end = 16.dp),
                                        thickness = 1.dp,
                                        color = Color.LightGray.copy(alpha = 0.3f)
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.alpha(0.6f)) {
                                    UnprocessedTrackItem(
                                        track = track,
                                        onEdit = { trackToEdit = track },
                                        onHide = { viewModel.hideTrack(track) },
                                        onPlay = { viewModel.playTrack(track, tracksInFolder.map { it.first }, forcedTitle = "Папка: $folderName") }
                                    )
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }

    trackToEdit?.let { track ->
        EditTrackDialog(
            track = track,
            onDismiss = { trackToEdit = null },
            viewModel = viewModel,
            onSave = { title, artist, album, isDemo, isUnreleased, isLive, liveCity, liveYear ->
                viewModel.processTrack(
                    track = track,
                    title = title,
                    artist = artist,
                    album = album,
                    isDemo = isDemo,
                    isUnreleased = isUnreleased,
                    isLive = isLive,
                    liveCity = liveCity,
                    liveYear = liveYear
                )
                trackToEdit = null
            }
        )
    }
}

@Composable
fun EditTrackDialog(
    track: TrackEntity,
    viewModel: MusicViewModel,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        artist: String,
        album: String,
        isDemo: Boolean,
        isUnreleased: Boolean,
        isLive: Boolean,
        liveCity: String?,
        liveYear: Int?
    ) -> Unit
) {
    val smartData = remember(track) { viewModel.extractMetadata(track.uri) }
    var titleText by remember { mutableStateOf(track.title ?: smartData.title ?: smartData.realFileName) }
    var artistText by remember { mutableStateOf(track.artist ?: smartData.artist ?: "") }
    var albumText by remember { mutableStateOf(track.album ?: smartData.album ?: "") }

    // Поля специально для LIVE-выступлений
    var liveCityText by remember { mutableStateOf(track.liveCity ?: smartData.liveCity ?: "") }
    var liveYearText by remember {
        mutableStateOf(
            track.liveYear?.toString()
                ?: smartData.liveYear?.toString()
                ?: track.year?.toString()
                ?: smartData.year?.toString()
                ?: ""
        )
    }

    // ЕДИНАЯ ПЕРЕМЕННАЯ ТИПА
    var trackType by remember {
        mutableStateOf(
            when {
                track.isLive || smartData.trackType == "LIVE" || smartData.realFileName.contains("live", ignoreCase = true) -> "LIVE"
                track.isDemo || smartData.realFileName.contains("demo", ignoreCase = true) -> "DEMO"
                track.isUnreleased || smartData.realFileName.contains("unreleased", ignoreCase = true) -> "UNRELEASED"
                else -> "REGULAR"
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обработка трека") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Файл: ${track.fileName}", fontSize = 12.sp, color = Color.Gray)

                // --- ПАНЕЛЬ ВЫБОРА ТИПА (4 КНОПКИ) ---
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("REGULAR", "LIVE", "DEMO", "UNRELEASED").forEach { type ->
                        val isSelected = trackType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.2f))
                                .clickable { trackType = type }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = type,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else Color.DarkGray,
                                maxLines = 1
                            )
                        }
                    }
                }

                // --- ДОПОЛНИТЕЛЬНЫЕ ПОЛЯ ДЛЯ LIVE (ГОРОД И ГОД) ---
                androidx.compose.animation.AnimatedVisibility(visible = trackType == "LIVE") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = liveCityText,
                            onValueChange = { liveCityText = it },
                            label = { Text("Город") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = liveYearText,
                            onValueChange = { liveYearText = it.filter { ch -> ch.isDigit() }.take(4) },
                            label = { Text("Год") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                OutlinedTextField(value = titleText, onValueChange = { titleText = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(value = artistText, onValueChange = { artistText = it }, label = { Text("Исполнители (через запятую)") }, singleLine = true)
                OutlinedTextField(value = albumText, onValueChange = { albumText = it }, label = { Text("Альбом (пусто = Сингл)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val isDemoFinal = trackType == "DEMO"
                    val isUnreleasedFinal = trackType == "UNRELEASED"
                    val isLiveFinal = trackType == "LIVE"
                    onSave(
                        titleText.trim(),
                        artistText.trim(),
                        albumText.trim(),
                        isDemoFinal,
                        isUnreleasedFinal,
                        isLiveFinal,
                        liveCityText.trim().ifBlank { null },
                        liveYearText.toIntOrNull()
                    )
                },
                enabled = titleText.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun UnprocessedTrackItem(
    track: TrackEntity,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    onPlay: () -> Unit
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    var showHideDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Audiotrack,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Color.Gray
        )
        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title ?: track.fileName,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = track.artist ?: "Неизвестный исполнитель",
                color = Color.Gray,
                fontSize = 14.sp,
                maxLines = 1
            )
        }

        Box {
            IconButton(onClick = { isMenuExpanded = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Опции"
                )
            }
            DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Изменить информацию") },
                    onClick = { isMenuExpanded = false; onEdit() })
                DropdownMenuItem(
                    text = { Text("Скрыть", color = Color.Red) },
                    onClick = { isMenuExpanded = false; showHideDialog = true })
            }
        }
    }

    if (showHideDialog) {
        AlertDialog(
            onDismissRequest = { showHideDialog = false },
            title = { Text("Скрыть трек?") },
            text = { Text("Точно скрыть?") },
            confirmButton = {
                TextButton(onClick = {
                    showHideDialog = false; onHide()
                }) { Text("Да", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showHideDialog = false }) { Text("Отмена") } }
        )
    }
}

// ---------------------------------------------------------
// ЭКРАН ПЛЕЙЛИСТОВ
// ---------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsTabScreen(viewModel: MusicViewModel, isActiveTab: Boolean = true) {
    val processedTracks by viewModel.processedTracks.collectAsState(initial = emptyList<TrackEntity>())
    val hiddenTracks by viewModel.hiddenTracks.collectAsState(initial = emptyList<TrackEntity>())
    val customPlaylists by viewModel.allPlaylists.collectAsState(initial = emptyList<PlaylistEntity>())

    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val queueTitle by viewModel.currentQueueTitle.collectAsState()

    val showAllTracks by viewModel.showAllTracksPlaylists.collectAsState()
    val showHiddenTracks by viewModel.showHiddenTracks.collectAsState()
    val openedPlaylist by viewModel.openedPlaylist.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    // --- ЗАПОМИНАЛКИ ДЛЯ АНИМАЦИИ ЗАКРЫТИЯ (Чтобы экраны не исчезали мгновенно) ---
    val displayPlaylist = remember { mutableStateOf(openedPlaylist) }
    if (openedPlaylist != null) displayPlaylist.value = openedPlaylist

    val isAllTracksOpen = remember { mutableStateOf(showAllTracks) }
    isAllTracksOpen.value = showAllTracks

    val isHiddenTracksOpen = remember { mutableStateOf(showHiddenTracks) }
    isHiddenTracksOpen.value = showHiddenTracks

    // --- СИСТЕМНЫЕ КНОПКИ "НАЗАД" ---
    BackHandler(enabled = isActiveTab && openedPlaylist != null) { viewModel.openPlaylist(null) }
    BackHandler(enabled = isActiveTab && showAllTracks && openedPlaylist == null) { viewModel.setShowAllTracksPlaylists(false) }
    BackHandler(enabled = isActiveTab && showHiddenTracks && openedPlaylist == null) { viewModel.setShowHiddenTracks(false) }

    // --- ЕДИНЫЙ BOX ДЛЯ НАСЛОЕНИЯ ЭКРАНОВ ---
    Box(modifier = Modifier.fillMaxSize()) {

        // =========================================================
        // СЛОЙ 0: ГЛАВНЫЙ ЭКРАН ВКЛАДКИ (Всегда лежит в самом низу)
        // =========================================================
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Плейлисты", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Создать плейлист") }
            }

            PlaylistRowItem(icon = Icons.Default.LibraryMusic, title = "Все скачанные треки", subtitle = "${processedTracks.size} треков", onClick = { viewModel.setShowAllTracksPlaylists(true) })
            Spacer(modifier = Modifier.height(16.dp))
            Text("Мои плейлисты", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(customPlaylists) { playlist ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.openPlaylist(playlist) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ИСПОЛЬЗУЕМ АСИНХРОННЫЙ COIL ДЛЯ ИДЕАЛЬНОЙ ПРОКРУТКИ
                            if (playlist.imageUri != null) {
                                Image(
                                    painter = coil.compose.rememberAsyncImagePainter(playlist.imageUri),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White) }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(playlist.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))

                            val isThisPlaylistContext = queueTitle == playlist.name
                            IconButton(
                                onClick = { if (isThisPlaylistContext) viewModel.togglePlayback() else viewModel.playPlaylistDirectly(playlist.playlistId) },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                            ) { Icon(imageVector = if (isThisPlaylistContext && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Играть/Пауза", tint = MaterialTheme.colorScheme.primary) }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
            PlaylistRowItem(icon = Icons.Default.VisibilityOff, title = "Скрытое", subtitle = "${hiddenTracks.size}", onClick = { viewModel.setShowHiddenTracks(true) }, isTransparent = true)
        }

        // =========================================================
        // СЛОЙ 1: ЭКРАН "ВСЕ СКАЧАННЫЕ ТРЕКИ" (Выезжает сбоку)
        // =========================================================
        androidx.compose.animation.AnimatedVisibility(
            visible = isAllTracksOpen.value,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it })
        ) {
            // --- 1. ДОБАВЛЯЕМ ЛОГИКУ ГРУППИРОВКИ ---
            // Собираем необработанные треки (processedTracks у тебя уже есть)
            val unprocessed by viewModel.unprocessedTracks.collectAsState(initial = emptyList())

            val tracksByFolder = remember(processedTracks, unprocessed) {
                val combinedList = processedTracks.map { it to true } + unprocessed.map { it to false }
                combinedList
                    .groupBy { viewModel.getFolderNameFromUri(it.first.uri) }
                    .toSortedMap()
            }

            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        IconButton(onClick = { viewModel.setShowAllTracksPlaylists(false) }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
                        Text("Все скачанные треки", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }

                    // --- 2. ЗАМЕНЯЕМ СТАРЫЙ LazyColumn НА СГРУППИРОВАННЫЙ ---
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        tracksByFolder.forEach { (folderName, tracksInFolder) ->

                            // ЗАГОЛОВОК ПАПКИ
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = folderName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // ТРЕКИ ВНУТРИ ЭТОЙ ПАПКИ
                            items(tracksInFolder) { (track, isProcessed) ->
                                if (isProcessed) {
                                    // ========================================================
                                    // ТВОЙ ОРИГИНАЛЬНЫЙ ДИЗАЙН ДЛЯ ОБРАБОТАННЫХ ТРЕКОВ
                                    // ========================================================
                                    val isPlayingThis = currentTrack?.uri == track.uri
                                    val coverPath = viewModel.getAlbumCoverPath(track)

                                    Column {
                                        // ВМЕСТО ОГРОМНОГО ROW ТЕПЕРЬ ОДНА СТРОЧКА:
                                        TrackRowItem(
                                            track = track,
                                            coverUri = coverPath,
                                            isPlaying = isPlayingThis,
                                            viewModel = viewModel
                                        ) {
                                            viewModel.playTrack(track, processedTracks, forcedTitle = "Все скачанные треки")
                                        }

                                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                                    }
                                } else {
                                    // ========================================================
                                    // НОВЫЙ ДИЗАЙН ДЛЯ НЕОБРАБОТАННЫХ ТРЕКОВ
                                    // ========================================================
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .alpha(0.5f)
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.HourglassEmpty, contentDescription = "Не обработан", tint = Color.Gray)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = track.fileName, maxLines = 1, fontSize = 16.sp)
                                            Text(text = "Ожидает обработки...", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }

                            item { Spacer(modifier = Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }

        // =========================================================
        // СЛОЙ 2: ЭКРАН "СКРЫТЫЕ ТРЕКИ" (Выезжает сбоку)
        // =========================================================
        androidx.compose.animation.AnimatedVisibility(
            visible = isHiddenTracksOpen.value,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it })
        ) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        IconButton(onClick = { viewModel.setShowHiddenTracks(false) }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
                        Text("Скрытые файлы", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    if (hiddenTracks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Здесь ничего нет", color = Color.Gray) }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(hiddenTracks, key = { it.uri }) { track ->
                                Column {
                                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.Gray)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(track.fileName, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { viewModel.unhideTrack(track) }) { Icon(Icons.Default.Restore, contentDescription = "Восстановить", tint = MaterialTheme.colorScheme.primary) }
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================
        // СЛОЙ 3: КАСТОМНЫЙ ПЛЕЙЛИСТ (Выезжает сбоку поверх всего)
        // =========================================================
        androidx.compose.animation.AnimatedVisibility(
            visible = openedPlaylist != null,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it })
        ) {
            displayPlaylist.value?.let { playlist ->
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

                    val initialTracks = remember(playlist.playlistId) { viewModel.getCachedPlaylistTracks(playlist.playlistId) }
                    val playlistTracks by viewModel.getTracksForPlaylist(playlist.playlistId).collectAsState(initial = initialTracks)
                    val totalDuration = viewModel.formatTotalDuration(playlistTracks)
                    var showEditDialog by remember { mutableStateOf(false) }
                    var isReorderMode by remember { mutableStateOf(false) }
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

                    var isAnyTrackDragging by remember { mutableStateOf(false) }
                    val localTracks = remember(playlist.playlistId) { mutableStateListOf(*initialTracks.toTypedArray()) }

                    LaunchedEffect(playlistTracks) {
                        if (!isAnyTrackDragging) {
                            localTracks.clear()
                            localTracks.addAll(playlistTracks)
                        }
                    }

                    var draggedIndex by remember { mutableIntStateOf(-1) }
                    var dragOffsetY by remember { mutableFloatStateOf(0f) }
                    var itemHeightPx by remember { mutableFloatStateOf(200f) }

                    fun checkAndSwap() {
                        if (draggedIndex == -1 || itemHeightPx <= 0f || localTracks.isEmpty()) return

                        val offsetInItems = (dragOffsetY / itemHeightPx).roundToInt()
                        if (offsetInItems == 0) return

                        // Находим индексы треков, которые СЕЙЧАС реально видны на экране (индекс 0 в LazyColumn — это шапка плейлиста)
                        val visibleTrackIndices = listState.layoutInfo.visibleItemsInfo
                            .mapNotNull { info -> (info.index - 1).takeIf { it in 0..localTracks.lastIndex } }

                        val minVisibleIdx = visibleTrackIndices.firstOrNull() ?: 0
                        val maxVisibleIdx = visibleTrackIndices.lastOrNull() ?: localTracks.lastIndex

                        // Не даём треку улететь за пределы видимых элементов экрана (чтобы LazyColumn его не уничтожил)
                        val targetIdx = (draggedIndex + offsetInItems).coerceIn(minVisibleIdx, maxVisibleIdx)

                        if (targetIdx != draggedIndex) {
                            val savedFirstIndex = listState.firstVisibleItemIndex
                            val savedFirstOffset = listState.firstVisibleItemScrollOffset

                            val movedTrack = localTracks.removeAt(draggedIndex)
                            localTracks.add(targetIdx, movedTrack)

                            // Фиксируем якорь скролла, если задели самый верхний видимый трек, чтобы список не прыгал
                            if (savedFirstIndex >= 1 && (draggedIndex == savedFirstIndex - 1 || targetIdx <= savedFirstIndex - 1)) {
                                listState.requestScrollToItem(savedFirstIndex, savedFirstOffset)
                            }

                            dragOffsetY -= (targetIdx - draggedIndex) * itemHeightPx
                            draggedIndex = targetIdx
                        }

                        // Если трек упёрся в верхнюю или нижнюю границу видимой области, не копим лишний отступ
                        if ((draggedIndex <= minVisibleIdx && dragOffsetY < 0f) ||
                            (draggedIndex >= maxVisibleIdx && dragOffsetY > 0f)
                        ) {
                            dragOffsetY = dragOffsetY.coerceIn(-itemHeightPx * 0.6f, itemHeightPx * 0.6f)
                        }
                    }

                    // Моторчик привязан к isAnyTrackDragging, поэтому он НЕ прерывается при обмене соседних треков!
                    LaunchedEffect(isAnyTrackDragging) {
                        while (isAnyTrackDragging && draggedIndex != -1) {
                            val track = localTracks.getOrNull(draggedIndex) ?: break
                            val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.key == track.uri }
                            if (itemInfo != null) {
                                val currentY = itemInfo.offset.toFloat() + dragOffsetY
                                val viewportHeight = listState.layoutInfo.viewportSize.height.toFloat()

                                val topZone = 220f
                                val bottomZone = 280f

                                // Динамическая скорость: чем ближе палец к краю, тем быстрее летит список
                                val scrollSpeed = when {
                                    viewportHeight < 400f -> 0f
                                    currentY < topZone -> {
                                        val factor = ((topZone - currentY) / topZone).coerceIn(0.25f, 1.6f)
                                        -42f * factor
                                    }
                                    currentY > viewportHeight - bottomZone -> {
                                        val factor = ((currentY - (viewportHeight - bottomZone)) / bottomZone).coerceIn(0.25f, 1.6f)
                                        42f * factor
                                    }
                                    else -> 0f
                                }

                                if (scrollSpeed != 0f) {
                                    val consumed = listState.scrollBy(scrollSpeed)
                                    if (consumed != 0f) {
                                        dragOffsetY += consumed
                                        checkAndSwap()
                                    }
                                }
                            }
                            kotlinx.coroutines.delay(16)
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            item {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        IconButton(onClick = { viewModel.openPlaylist(null) }, modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
                                        Row {
                                            IconButton(onClick = { isReorderMode = !isReorderMode }, modifier = Modifier.background(if (isReorderMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)) { Icon(Icons.Default.SwapVert, contentDescription = "Изменить порядок", tint = if (isReorderMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            IconButton(onClick = { showEditDialog = true }, modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)) { Icon(Icons.Default.Edit, contentDescription = "Редактировать") }
                                        }
                                    }

                                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                        if (playlist.imageUri != null) {
                                            Image(painter = coil.compose.rememberAsyncImagePainter(playlist.imageUri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        } else {
                                            Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }

                                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(playlist.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                            Text("$totalDuration • ${playlistTracks.size} треков", fontSize = 14.sp, color = Color.Gray)
                                        }
                                        val isThisPlaylistContext = queueTitle == playlist.name
                                        IconButton(
                                            onClick = {
                                                if (isThisPlaylistContext) viewModel.togglePlayback()
                                                else if (localTracks.isNotEmpty()) viewModel.playTrack(localTracks.first(), localTracks.toList(), forcedTitle = playlist.name)
                                            },
                                            modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                                        ) {
                                            Icon(imageVector = if (isThisPlaylistContext && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Слушать/Пауза", tint = Color.White, modifier = Modifier.size(32.dp))
                                        }
                                    }
                                }
                            }

                            itemsIndexed(localTracks, key = { _, track -> track.uri }) { index, track ->
                                val isPlayingThis = currentTrack?.uri == track.uri
                                val coverPath = viewModel.getAlbumCoverPath(track)

                                val isDragging = draggedIndex == index
                                val elevation by androidx.compose.animation.core.animateDpAsState(if (isDragging) 12.dp else 0.dp)
                                val scale by androidx.compose.animation.core.animateFloatAsState(if (isDragging) 1.05f else 1f)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onGloballyPositioned {
                                            if (it.size.height > 0) itemHeightPx = it.size.height.toFloat()
                                        }
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .graphicsLayer {
                                            translationY = if (isDragging) dragOffsetY else 0f
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .shadow(elevation)
                                        .background(if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            TrackRowItem(
                                                track = track,
                                                coverUri = coverPath,
                                                isPlaying = isPlayingThis && !isDragging,
                                                trailingText = viewModel.formatTrackDuration(track.durationMs),
                                                viewModel = viewModel,
                                                onRemoveFromPlaylist = {
                                                    localTracks.remove(track)
                                                    viewModel.removeTrackFromPlaylist(playlist.playlistId, track.uri)
                                                }
                                            ) {
                                                viewModel.playTrack(track, localTracks.toList(), forcedTitle = playlist.name)
                                            }
                                        }

                                        if (isReorderMode) {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "Перетащить",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .padding(end = 16.dp)
                                                    .size(32.dp)
                                                    .pointerInput(track.uri) {
                                                        detectVerticalDragGestures(
                                                            onDragStart = {
                                                                isAnyTrackDragging = true
                                                                draggedIndex = localTracks.indexOf(track)
                                                                dragOffsetY = 0f
                                                            },
                                                            onDragEnd = {
                                                                isAnyTrackDragging = false
                                                                draggedIndex = -1
                                                                dragOffsetY = 0f
                                                                viewModel.savePlaylistOrder(playlist.playlistId, playlist.name, localTracks.toList())
                                                            },
                                                            onDragCancel = {
                                                                isAnyTrackDragging = false
                                                                draggedIndex = -1
                                                                dragOffsetY = 0f
                                                                viewModel.savePlaylistOrder(playlist.playlistId, playlist.name, localTracks.toList())
                                                            },
                                                            onVerticalDrag = { change, dragAmount ->
                                                                change.consume()
                                                                dragOffsetY += dragAmount
                                                                checkAndSwap()
                                                            }
                                                        )
                                                    }
                                            )
                                        }
                                    }
                                    if (!isDragging) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                    if (showEditDialog) {
                        CreatePlaylistDialog(
                            initialName = playlist.name,
                            initialImageUri = playlist.imageUri,
                            isEditMode = true,
                            onDismiss = { showEditDialog = false },
                            onSave = { newName, newImg ->
                                viewModel.updatePlaylist(playlist, newName, newImg)
                                viewModel.openPlaylist(playlist.copy(name = newName, imageUri = newImg))
                                showEditDialog = false
                            },
                            onDelete = {
                                viewModel.deletePlaylist(playlist)
                                showEditDialog = false
                                viewModel.openPlaylist(null)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            initialName = "",
            onDismiss = { showCreateDialog = false },
            onSave = { name, img -> viewModel.createPlaylist(name, img); showCreateDialog = false }
        )
    }
}

@Composable
fun PlaylistRowItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isTransparent: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isTransparent) Color.Transparent else MaterialTheme.colorScheme.primary.copy(
                    alpha = 0.1f
                )
            )
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isTransparent) Color.Transparent else MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isTransparent) Color.Gray else Color.White
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isTransparent) Color.Gray else Color.Black
            )
            Text(subtitle, fontSize = 14.sp, color = Color.Gray)
        }
    }
}


@Composable
fun CreatePlaylistDialog(
    initialName: String,
    initialImageUri: String? = null,
    isEditMode: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialName) }
    var imageUri by remember { mutableStateOf(initialImageUri) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { imageUri = it.toString() } // Просто сохраняем URI, копировать будет ViewModel
        }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить плейлист?", fontWeight = FontWeight.Bold) },
            text = { Text("Вы точно хотите удалить плейлист \"$name\"? Это действие нельзя отменить.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDelete?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                }) { Text("Отмена") }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isEditMode) "Редактировать" else "Новый плейлист") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(Color.LightGray, RoundedCornerShape(8.dp))
                            .clickable { galleryLauncher.launch(arrayOf("image/*")) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (imageUri != null) {
                            Image(
                                painter = rememberAsyncImagePainter(imageUri),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = Color.Gray
                                ); Text("Обложка", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название плейлиста") },
                        singleLine = true
                    )

                    if (isEditMode) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null
                            ); Spacer(Modifier.width(8.dp)); Text("Удалить плейлист")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSave(name, imageUri) },
                    enabled = name.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
        )
    }
}