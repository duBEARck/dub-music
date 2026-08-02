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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.SwapVert

@Composable
fun SavedTabScreen(viewModel: MusicViewModel) {
    // Читаем данные из базы. collectAsState превращает базу в поток, который сам обновляет экран!
    val tracks by viewModel.unprocessedTracks.collectAsState(initial = emptyList())
    var trackToEdit by remember { mutableStateOf<TrackEntity?>(null) }

    // Получаем доступ к системным службам
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            // ВОТ ОНО! Берем права на чтение файла "навечно"
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val fileName = uri.lastPathSegment ?: "Unknown_Audio"
            viewModel.addUnprocessedFile(uri.toString(), fileName)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Необработанные", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            FilledTonalButton(onClick = { filePickerLauncher.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Загрузить")
            }
        }

        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Все файлы обработаны.\nЗагрузите новые.", color = Color.Gray)
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            items(tracks, key = { it.uri }) { track ->
                UnprocessedTrackItem(
                    track = track,
                    onEdit = { trackToEdit = track },
                    onHide = { viewModel.hideTrack(track) },
                    onPlay = { viewModel.playTrack(track) } // Передаем команду воспроизведения
                )
            }
        }
    }

    trackToEdit?.let { track ->
        EditTrackDialog(
            track = track,
            onDismiss = { trackToEdit = null },
            onSave = { title, artist, album ->
                // Сохраняем в базу (обрабатываем)!
                viewModel.processTrack(track, title, artist, album)
                trackToEdit = null
            }
        )
    }
}

// Диалог редактирования почти не изменился, только TrackEntity вместо UnprocessedTrack
@Composable
fun EditTrackDialog(
    track: TrackEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String, album: String) -> Unit
) {
    var titleText by remember { mutableStateOf(track.fileName) }
    var artistText by remember { mutableStateOf("") }
    var albumText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обработка трека") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Файл: ${track.fileName}", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(value = titleText, onValueChange = { titleText = it }, label = { Text("Название трека") }, singleLine = true)
                OutlinedTextField(value = artistText, onValueChange = { artistText = it }, label = { Text("Исполнитель") }, singleLine = true)
                // Подсказка, что если оставить пустым, будет сингл
                OutlinedTextField(value = albumText, onValueChange = { albumText = it }, label = { Text("Альбом (пусто = Сингл)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(titleText, artistText, albumText) }, enabled = titleText.isNotBlank()) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun UnprocessedTrackItem(
    track: TrackEntity,
    onEdit: () -> Unit,
    onHide: () -> Unit,
    onPlay: () -> Unit // Добавили новый параметр
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    var showHideDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() } // ВОТ ТУТ делаем всю строчку кликабельной
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = track.fileName, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { isMenuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Опции") }
            DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
                DropdownMenuItem(text = { Text("Изменить информацию") }, onClick = { isMenuExpanded = false; onEdit() })
                DropdownMenuItem(text = { Text("Скрыть", color = Color.Red) }, onClick = { isMenuExpanded = false; showHideDialog = true })
            }
        }
    }

    if (showHideDialog) {
        AlertDialog(
            onDismissRequest = { showHideDialog = false },
            title = { Text("Скрыть трек?") },
            text = { Text("Точно скрыть?") },
            confirmButton = { TextButton(onClick = { showHideDialog = false; onHide() }) { Text("Да", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showHideDialog = false }) { Text("Отмена") } }
        )
    }
}

// ---------------------------------------------------------
// НОВЫЙ ЭКРАН ПЛЕЙЛИСТОВ
// ---------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsTabScreen(viewModel: MusicViewModel) {
    val processedTracks by viewModel.processedTracks.collectAsState(initial = emptyList<TrackEntity>())
    val hiddenTracks by viewModel.hiddenTracks.collectAsState(initial = emptyList<TrackEntity>())
    val customPlaylists by viewModel.allPlaylists.collectAsState(initial = emptyList<PlaylistEntity>())

    var showAllTracks by remember { mutableStateOf(false) }
    var showHiddenTracks by remember { mutableStateOf(false) }
    var openedPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // ------------------------------------
    // 1. ЭКРАН: КАСТОМНЫЙ ПЛЕЙЛИСТ
    // ------------------------------------
    if (openedPlaylist != null) {
        // --- ПЕРЕХВАТЫВАЕМ СИСТЕМНЫЙ СВАЙП "НАЗАД" ---
        BackHandler(enabled = true) {
            openedPlaylist = null // Закрываем плейлист при свайпе
        }

        val playlistTracks by viewModel.getTracksForPlaylist(openedPlaylist!!.playlistId).collectAsState(initial = emptyList<TrackEntity>())
        val totalDuration = viewModel.formatTotalDuration(playlistTracks)
        var showEditDialog by remember { mutableStateOf(false) }
        val currentTrack by viewModel.currentTrack.collectAsState()
        var isReorderMode by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {

            LazyColumn(modifier = Modifier.fillMaxSize()) {

                // ЭЛЕМЕНТ 1: Шапка (кнопки, обложка, название) — теперь она уедет вверх при скролле!
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Верхняя панель с кнопками
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = { openedPlaylist = null },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                            ) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }

                            // Сгруппировали правые кнопки вместе
                            Row {
                                // Кнопка переключения режима сортировки
                                IconButton(
                                    onClick = { isReorderMode = !isReorderMode },
                                    modifier = Modifier.background(if (isReorderMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                                ) { Icon(Icons.Default.SwapVert, contentDescription = "Изменить порядок", tint = if (isReorderMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = { showEditDialog = true },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                                ) { Icon(Icons.Default.Edit, contentDescription = "Редактировать") }
                            }
                        }

                        // Квадратная обложка плейлиста
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (openedPlaylist!!.imageUri != null) {
                                Image(
                                    painter = rememberAsyncImagePainter(openedPlaylist!!.imageUri),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        // Инфо-панель (Название, время и большая кнопка Play)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(openedPlaylist!!.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                Text("$totalDuration • ${playlistTracks.size} треков", fontSize = 14.sp, color = Color.Gray)
                            }

                            IconButton(
                                onClick = { if (playlistTracks.isNotEmpty()) viewModel.playTrack(playlistTracks.first(), playlistTracks) },
                                modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Слушать", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }

                // ЭЛЕМЕНТЫ 2: Сам список песен
                itemsIndexed(playlistTracks, key = { _, track -> track.uri }) { index, track ->
                    // Проверяем, играет ли этот трек прямо сейчас
                    val isPlayingThis = currentTrack?.uri == track.uri

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // ПОДСВЕТКА СЕРО-СИНИМ, ЕСЛИ ТРЕК ИГРАЕТ
                                .background(if (isPlayingThis) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { viewModel.playTrack(track, playlistTracks) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Заглушка обложки трека
                            Box(
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }

                            Spacer(modifier = Modifier.width(16.dp))

                            // Название и исполнитель
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title ?: track.fileName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(track.artist ?: "Неизвестный исполнитель", fontSize = 14.sp, color = Color.Gray)
                            }

                            // ВРЕМЯ ТРЕКА
                            Text(
                                text = viewModel.formatTrackDuration(track.durationMs),
                                fontSize = 14.sp,
                                color = Color.Gray
                            )

                            // СТРЕЛОЧКИ СОРТИРОВКИ (Показываем только если включен режим isReorderMode)
                            if (isReorderMode) {
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Вверх",
                                        tint = if (index > 0) Color.Gray else Color.LightGray,
                                        modifier = Modifier.clickable(enabled = index > 0) { viewModel.moveTrackInPlaylist(openedPlaylist!!.playlistId, index, index - 1) }
                                    )
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Вниз",
                                        tint = if (index < playlistTracks.size - 1) Color.Gray else Color.LightGray,
                                        modifier = Modifier.clickable(enabled = index < playlistTracks.size - 1) { viewModel.moveTrackInPlaylist(openedPlaylist!!.playlistId, index, index + 1) }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
        }

        if (showEditDialog) {
            CreatePlaylistDialog(
                initialName = openedPlaylist!!.name,
                initialImageUri = openedPlaylist!!.imageUri,
                isEditMode = true,
                onDismiss = { showEditDialog = false },
                onSave = { newName, newImg ->
                    viewModel.updatePlaylist(openedPlaylist!!, newName, newImg)
                    openedPlaylist = openedPlaylist!!.copy(name = newName, imageUri = newImg)
                    showEditDialog = false
                },
                onDelete = {
                    viewModel.deletePlaylist(openedPlaylist!!)
                    showEditDialog = false
                    openedPlaylist = null
                }
            )
        }

    }
    // ------------------------------------
    // 2. ЭКРАН: ВСЕ СКАЧАННЫЕ ТРЕКИ
    // ------------------------------------
    else if (showAllTracks) {

        BackHandler(enabled = true) {
            showAllTracks = false // Закрываем плейлист при свайпе
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                IconButton(onClick = { showAllTracks = false }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
                Text("Все скачанные треки", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(processedTracks, key = { it.uri }) { track ->
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.playTrack(track, processedTracks) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(track.title ?: track.fileName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(track.artist ?: "Неизвестный исполнитель", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                        // Разделитель (начинается с отступом, чтобы не перечеркивать иконку ноты)
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp, end = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
    // ------------------------------------
    // 3. ЭКРАН: СКРЫТЫЕ ТРЕКИ
    // ------------------------------------
    else if (showHiddenTracks) {

        BackHandler(enabled = true) {
            showHiddenTracks = false // Закрываем плейлист при свайпе
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                IconButton(onClick = { showHiddenTracks = false }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
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
                                IconButton(onClick = { viewModel.unhideTrack(track) }) {
                                    Icon(Icons.Default.Restore, contentDescription = "Восстановить", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
    // ------------------------------------
    // 4. ГЛАВНЫЙ ЭКРАН ВКЛАДКИ
    // ------------------------------------
    else {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Плейлисты", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showCreateDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Создать плейлист") }
            }

            PlaylistRowItem(icon = Icons.Default.LibraryMusic, title = "Все скачанные треки", subtitle = "${processedTracks.size} треков", onClick = { showAllTracks = true })
            Spacer(modifier = Modifier.height(16.dp))
            Text("Мои плейлисты", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(customPlaylists) { playlist ->
                    //Оборачиваем строку в Column, чтобы добавить разделитель снизу
                    Column {
                        Row(modifier = Modifier.fillMaxWidth().clickable { openedPlaylist = playlist }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (playlist.imageUri != null) {
                                Image(painter = rememberAsyncImagePainter(playlist.imageUri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
                            } else {
                                Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            //обавляем weight(1f), чтобы текст занял всё свободное место и оттолкнул кнопку вправо
                            Text(playlist.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))

                            //Кнопка Play прямо в списке
                            IconButton(
                                onClick = { viewModel.playPlaylistDirectly(playlist.playlistId) },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Играть плейлист", tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        //Разделитель между плейлистами
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.5f))
                    }
                }
            }
            PlaylistRowItem(icon = Icons.Default.VisibilityOff, title = "Скрытое", subtitle = "${hiddenTracks.size}", onClick = { showHiddenTracks = true }, isTransparent = true)
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(initialName = "", onDismiss = { showCreateDialog = false }, onSave = { name, img ->
            viewModel.createPlaylist(name, img)
            showCreateDialog = false
        })
    }
}

@Composable
fun PlaylistRowItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, isTransparent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(if (isTransparent) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(if (isTransparent) Color.Transparent else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (isTransparent) Color.Gray else Color.White)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (isTransparent) Color.Gray else Color.Black)
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
    var showDeleteConfirm by remember { mutableStateOf(false) } // Состояние для окна "Вы точно хотите?"

    val context = LocalContext.current
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            imageUri = it.toString()
        }
    }

    // ОКНО ПОДТВЕРЖДЕНИЯ УДАЛЕНИЯ
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить плейлист?", fontWeight = FontWeight.Bold) },
            text = { Text("Вы точно хотите удалить плейлист \"$name\"? Это действие нельзя отменить.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") } }
        )
    } else {
        // ОСНОВНОЕ ОКНО РЕДАКТИРОВАНИЯ/СОЗДАНИЯ
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isEditMode) "Редактировать" else "Новый плейлист") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(120.dp).background(Color.LightGray, RoundedCornerShape(8.dp)).clickable { galleryLauncher.launch(arrayOf("image/*")) }, contentAlignment = Alignment.Center) {
                        if (imageUri != null) {
                            Image(painter = rememberAsyncImagePainter(imageUri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color.Gray)
                                Text("Обложка", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название плейлиста") }, singleLine = true)

                    // ЕСЛИ МЫ В РЕЖИМЕ РЕДАКТИРОВАНИЯ — ПОКАЗЫВАЕМ КНОПКУ УДАЛИТЬ
                    if (isEditMode) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Удалить плейлист")
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { onSave(name, imageUri) }, enabled = name.isNotBlank()) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
        )
    }
}