package com.example.dubmusic

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF6200EA),
                background = Color(0xFFF5F5F5),
                surface = Color.White
            )) {
                val viewModel: MusicViewModel = viewModel()
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
                            onOpenFullScreen = { showFullScreenPlayer = true } // Открываем на весь экран!
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

        // Анимация выезда полноэкранного плеера снизу вверх
        AnimatedVisibility(
            visible = showFullScreenPlayer && currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it }), // Выезжает снизу
            exit = slideOutVertically(targetOffsetY = { it })   // Уезжает вниз
        ) {
            currentTrack?.let { track ->
                FullScreenPlayer(
                    track = track,
                    isPlaying = isPlaying,
                    progress = progress,
                    currentTime = currentTime, // Передаем
                    totalTime = totalTime,     // Передаем
                    onTogglePlayback = { viewModel.togglePlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onClose = { showFullScreenPlayer = false },
                    onEdit = { trackToEdit = track }, // Прокидываем трек в редактор
                    onNext = { /* ЗАГЛУШКА */ },
                    onPrev = { /* ЗАГЛУШКА */ }
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
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
    track: TrackEntity,
    isPlaying: Boolean,
    progress: Float,
    currentTime: String,
    totalTime: String,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    // Переменная для хранения смещения окна при свайпе
    var offsetY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Двигаем экран вслед за пальцем
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .pointerInput(Unit) {
                // Отслеживаем только вертикальные жесты, чтобы не мешать ползунку перемотки
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (offsetY > 300f) {
                            onClose() // Если свайпнули достаточно сильно - закрываем
                        } else {
                            offsetY = 0f // Иначе окно отпружинивает обратно
                        }
                    }
                ) { _, dragAmount ->
                    // Разрешаем тянуть только вниз
                    if (dragAmount > 0 || offsetY > 0) {
                        offsetY += dragAmount
                    }
                }
            }
            .padding(top = 48.dp, bottom = 32.dp, start = 24.dp, end = 24.dp)
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Свернуть", modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                .shadow(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Название, Артист и 3 точки теперь в одном ряду!
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // Шрифты уменьшены
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
                    DropdownMenuItem(text = { Text("Добавить в плейлист") }, onClick = { showMenu = false; /* ЗАГЛУШКА */ })
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp)) // Одинаковое расстояние от картинки до текста и от текста до кнопок

        // Блок со временем
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = currentTime, fontSize = 12.sp, color = Color.Gray)
            Text(text = totalTime, fontSize = 12.sp, color = Color.Gray)
        }

        Slider(
            value = progress,
            onValueChange = onSeek,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Назад", modifier = Modifier.size(48.dp)) }

            IconButton(onClick = onTogglePlayback, modifier = Modifier.size(80.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = "Play/Pause",
                    modifier = Modifier.fillMaxSize(),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Вперед", modifier = Modifier.size(48.dp)) }
        }

        // ВОТ ГЛАВНЫЙ СЕКРЕТ КОМПОНОВКИ:
        // Пустое место перенесено в самый низ. Оно работает как пружина,
        // выталкивая текст, ползунок и кнопки наверх, поближе к обложке.
        Spacer(modifier = Modifier.weight(1f))
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

