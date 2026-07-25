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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VisibilityOff

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
@Composable
fun PlaylistsTabScreen(viewModel: MusicViewModel) {
    val processedTracks by viewModel.processedTracks.collectAsState(initial = emptyList())
    // Слушаем список скрытых треков
    val hiddenTracks by viewModel.hiddenTracks.collectAsState(initial = emptyList())

    // Состояния навигации внутри вкладки
    var showAllTracks by remember { mutableStateOf(false) }
    var showHiddenTracks by remember { mutableStateOf(false) }

    if (showAllTracks) {
        // ------------------------------------
        // ЭКРАН: ВСЕ СКАЧАННЫЕ ТРЕКИ
        // ------------------------------------
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                IconButton(onClick = { showAllTracks = false }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
                Text("Все скачанные треки", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(processedTracks, key = { it.uri }) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.playTrack(track) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(track.title ?: track.fileName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(track.artist ?: "Неизвестный исполнитель", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    } else if (showHiddenTracks) {
        // ------------------------------------
        // ЭКРАН: СКРЫТЫЕ ТРЕКИ
        // ------------------------------------
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                IconButton(onClick = { showHiddenTracks = false }) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
                Text("Скрытые файлы", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            if (hiddenTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Здесь ничего нет", color = Color.Gray)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(hiddenTracks, key = { it.uri }) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.Gray)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(track.fileName, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))

                            // Кнопка восстановления
                            IconButton(onClick = { viewModel.unhideTrack(track) }) {
                                Icon(Icons.Default.Restore, contentDescription = "Восстановить", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    } else {
        // ------------------------------------
        // ГЛАВНЫЙ ЭКРАН ВКЛАДКИ
        // ------------------------------------
        Column(modifier = Modifier.fillMaxSize()) {
            Text(text = "Плейлисты", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))

            // Блок "Все скачанные треки"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .clickable { showAllTracks = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color.White) }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text("Все скачанные треки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${processedTracks.size} треков", fontSize = 14.sp, color = Color.Gray)
                }
            }

            // Пружина, выталкивающая полоску "Скрытое" в самый низ экрана
            Spacer(modifier = Modifier.weight(1f))

            // Полоска "Скрытое"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHiddenTracks = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VisibilityOff, contentDescription = "Скрытое", tint = Color.Gray)
                Spacer(modifier = Modifier.width(16.dp))
                Text("Скрытое", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.Gray)

                Spacer(modifier = Modifier.weight(1f))

                // Счетчик скрытых файлов
                Text(text = "${hiddenTracks.size}", fontSize = 14.sp, color = Color.Gray)
            }
        }
    }
}