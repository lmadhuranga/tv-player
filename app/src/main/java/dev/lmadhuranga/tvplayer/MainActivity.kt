package dev.lmadhuranga.tvplayer

import android.os.Bundle
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

internal val Ink = Color(0xFF07111F)
internal val Teal = Color(0xFF5BD5C6)
internal val Muted = Color(0xFFB9C8D9)

class MainActivity : ComponentActivity() {
    private lateinit var playback: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val history = HistoryRepository(applicationContext)
        playback = PlaybackController(applicationContext, history)
        setContent {
            MaterialTheme {
                TVPlayerApp(playback, history)
            }
        }
    }

    override fun onDestroy() {
        playback.release()
        super.onDestroy()
    }

    override fun onStop() {
        if (::playback.isInitialized) playback.pauseForBackground()
        super.onStop()
    }
}

private enum class AppRoute { HOME, ADD_URL, HANDOFF, PLAYER }

@Composable
private fun TVPlayerApp(playback: PlaybackController, historyRepository: HistoryRepository) {
    var route by remember { mutableStateOf(AppRoute.HOME) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsWasOpen by remember { mutableStateOf(false) }
    val settingsOpenerRequester = remember { FocusRequester() }
    val settingsCloseRequester = remember { FocusRequester() }
    val rememberPositions by historyRepository.enabled.collectAsState(initial = false)
    val recentEntries by historyRepository.entries.collectAsState(initial = emptyList())
    val appScope = rememberCoroutineScope()
    var pendingStart by remember { mutableStateOf<PendingPlayback?>(null) }

    fun startVideo(url: String, title: String, subtitleUrl: String?) {
        val candidate = recentEntries.firstOrNull { it.positionMs > 5_000L }
        if (rememberPositions && candidate != null) {
            pendingStart = PendingPlayback(url, title, subtitleUrl, candidate)
        } else {
            playback.playUrl(url, title, subtitleUrl)
            route = AppRoute.PLAYER
        }
    }

    BackHandler(enabled = settingsOpen || route != AppRoute.HOME) {
        if (settingsOpen) settingsOpen = false else {
            if (route == AppRoute.PLAYER) playback.pauseForBackground()
            route = AppRoute.HOME
        }
    }

    when (route) {
        AppRoute.HOME -> HomeScreen(
            settingsRequester = settingsOpenerRequester,
            onOpenPlayer = { route = AppRoute.PLAYER },
            onAddVideo = { route = AppRoute.ADD_URL },
            onOpenHandoff = { route = AppRoute.HANDOFF },
            onOpenSettings = { settingsOpen = true },
        )

        AppRoute.ADD_URL -> VideoUrlEntryScreen(
            onStart = { url, subtitleUrl, label ->
                startVideo(url, "Video from $label", subtitleUrl)
            },
            onBack = { route = AppRoute.HOME },
        )

        AppRoute.HANDOFF -> HandoffScreen(
            onVideoSubmitted = { submission ->
                val host = (validateVideoUrl(submission.videoUrl) as? VideoUrlValidation.Valid)?.sourceLabel ?: "TV Player"
                startVideo(submission.videoUrl, "Video from $host", submission.subtitleUrl)
            },
            onBack = { route = AppRoute.HOME },
        )

        AppRoute.PLAYER -> PlayerScreen(playback = playback, onAddUrl = {
            playback.pauseForBackground()
            route = AppRoute.ADD_URL
        }, onHome = {
            playback.pauseForBackground()
            route = AppRoute.HOME
        })
    }

    if (settingsOpen) {
        LaunchedEffect(Unit) { settingsCloseRequester.requestFocus() }
        SettingsScreen(
            focusRequester = settingsCloseRequester,
            rememberPositions = rememberPositions,
            entries = recentEntries,
            onToggleRemember = { appScope.launch { historyRepository.setEnabled(!rememberPositions) } },
            onClearHistory = { appScope.launch { historyRepository.clear() } },
            onDismiss = { settingsOpen = false },
        )
    } else if (settingsWasOpen) {
        LaunchedEffect(settingsOpen) { settingsOpenerRequester.requestFocus() }
    }
    LaunchedEffect(settingsOpen) { settingsWasOpen = settingsOpen }

    pendingStart?.let { pending ->
        ResumePrompt(
            entry = pending.entry,
            onResume = {
                playback.playUrl(pending.url, pending.title, pending.subtitleUrl, pending.entry.positionMs)
                pendingStart = null
                route = AppRoute.PLAYER
            },
            onStartOver = {
                playback.playUrl(pending.url, pending.title, pending.subtitleUrl)
                pendingStart = null
                route = AppRoute.PLAYER
            },
            onCancel = { pendingStart = null },
        )
    }
}

private data class PendingPlayback(val url: String, val title: String, val subtitleUrl: String?, val entry: HistoryEntry)

@Composable
private fun HomeScreen(
    settingsRequester: FocusRequester,
    onOpenPlayer: () -> Unit,
    onAddVideo: () -> Unit,
    onOpenHandoff: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TVBackground {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Brand()
                Spacer(Modifier.height(30.dp))
                Text(
                    "Your screen.\nYour video.",
                    color = Color.White,
                    fontSize = 56.sp,
                    lineHeight = 64.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "A simple place to play videos on your TV.",
                    color = Muted,
                    fontSize = 21.sp,
                )
                Spacer(Modifier.height(34.dp))
                Button(onClick = onAddVideo) { Text("Add video URL", fontSize = 18.sp) }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenHandoff) { Text("Send link from phone", fontSize = 18.sp) }
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenPlayer) { Text("Open player", fontSize = 18.sp) }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.focusRequester(settingsRequester),
                ) {
                    Text("Settings", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.width(56.dp))
            Surface(
                modifier = Modifier.size(width = 390.dp, height = 250.dp),
                shape = RoundedCornerShape(28.dp),
                colors = SurfaceDefaults.colors(containerColor = Color(0xFF12243A)),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("READY WHEN YOU ARE", color = Teal, fontSize = 14.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("Play a direct video link", color = Color.White, fontSize = 23.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Enter a video link to get started.", color = Muted, fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    focusRequester: FocusRequester,
    rememberPositions: Boolean,
    entries: List<HistoryEntry>,
    onToggleRemember: () -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Surface(
            modifier = Modifier.width(760.dp),
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF102239)),
        ) {
            Column(
                modifier = Modifier.padding(36.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("PRIVACY & HISTORY", color = Teal, fontSize = 16.sp, letterSpacing = 2.sp)
                Text("Position history stays on this device. Full video and subtitle URLs are never saved.", color = Muted, fontSize = 18.sp)
                Button(onClick = onToggleRemember, modifier = Modifier.focusRequester(focusRequester)) {
                    Text("Remember positions: ${if (rememberPositions) "On" else "Off"}")
                }
                if (entries.isNotEmpty()) {
                    Text("RECENT POSITIONS", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                    entries.forEach { entry ->
                        Text("${entry.title} · ${entry.sourceHost} · ${formatTime(entry.positionMs)}", color = Color.White, fontSize = 17.sp)
                    }
                    Button(onClick = onClearHistory) { Text("Clear history") }
                } else {
                    Text("No saved positions.", color = Muted, fontSize = 17.sp)
                }
                Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ResumePrompt(entry: HistoryEntry, onResume: () -> Unit, onStartOver: () -> Unit, onCancel: () -> Unit) {
    val resumeRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel) {
        LaunchedEffect(Unit) { resumeRequester.requestFocus() }
        Surface(
            modifier = Modifier.width(700.dp),
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF102239)),
        ) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RESUME VIDEO?", color = Teal, fontSize = 16.sp, letterSpacing = 2.sp)
                Text("A saved position is available from ${entry.sourceHost.ifBlank { "a previous video" }} at ${formatTime(entry.positionMs)}.", color = Color.White, fontSize = 21.sp)
                Text("The new link may point to different content. Choose where to begin.", color = Muted, fontSize = 17.sp)
                Button(onClick = onResume, modifier = Modifier.focusRequester(resumeRequester)) { Text("Resume at ${formatTime(entry.positionMs)}") }
                Button(onClick = onStartOver) { Text("Start from the beginning") }
                Button(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(playback: PlaybackController, onAddUrl: () -> Unit, onHome: () -> Unit) {
    val state by playback.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var menuWasOpen by remember { mutableStateOf(false) }
    val menuOpener = remember { FocusRequester() }
    val menuFirst = remember { FocusRequester() }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    Surface(modifier = Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Ink)) {
        Box(Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().focusProperties { canFocus = !menuOpen }.padding(horizontal = 56.dp, vertical = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.title ?: "PLAYER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(if (state.isBuffering) "Buffering…" else "", color = Muted, fontSize = 16.sp)
            }
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier.weight(1f).fillMaxSize().background(Color.Black, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            isFocusable = false
                            isFocusableInTouchMode = false
                            player = playback.player
                        }
                    },
                    update = { view ->
                        if (view.player !== playback.player) view.player = playback.player
                        view.resizeMode = state.displayMode.resizeMode
                        view.subtitleView?.apply {
                            setApplyEmbeddedStyles(false)
                            setApplyEmbeddedFontSizes(false)
                            val color = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green)[state.subtitleColorIndex].toArgb()
                            val background = if (state.subtitleBackgroundEnabled) 0xB8000000.toInt() else android.graphics.Color.TRANSPARENT
                            setStyle(CaptionStyleCompat(color, background, android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
                            setFractionalTextSize(listOf(0.045f, 0.06f, 0.075f, 0.09f)[state.subtitleSizeIndex])
                        }
                    },
                    onRelease = { view -> view.player = null },
                )
                if (state.title == null) {
                    Text("Nothing is playing yet", color = Color.White, fontSize = 28.sp)
                }
                state.error?.let { message ->
                    Text(message, color = Color.White, fontSize = 21.sp, modifier = Modifier.padding(40.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "${formatTime(state.positionMs)}  /  ${formatTime(state.durationMs)}",
                color = Muted,
                fontSize = 16.sp,
            )
            Text(
                if (state.isBuffering) "Buffering · buffered to ${formatTime(state.bufferedPositionMs)}"
                else "Buffered to ${formatTime(state.bufferedPositionMs)}",
                color = Muted,
                fontSize = 14.sp,
            )
            if (state.videoWidth > 0 && state.videoHeight > 0) {
                Text("Decoded picture: ${state.videoWidth}×${state.videoHeight}", color = Muted, fontSize = 14.sp)
            }
            if (state.error != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = playback::retryPlayback, enabled = state.canRetry) { Text("Retry") }
                    Button(onClick = onAddUrl) { Text("Enter another link") }
                }
            }
            state.subtitleError?.let { Text("Subtitle: $it", color = Color(0xFFFFB4A9), fontSize = 15.sp) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { playback.seekBy(-30_000L) }, enabled = state.title != null) { Text("−30 sec") }
                Button(onClick = playback::togglePlayPause, enabled = state.title != null) {
                    Text(if (state.isPlaying) "Pause" else "Play")
                }
                Button(onClick = { playback.seekBy(30_000L) }, enabled = state.title != null) { Text("+30 sec") }
                Button(onClick = playback::cycleSpeed, enabled = state.title != null) {
                    Text("${state.speed}×")
                }
                Button(onClick = { menuOpen = true }, modifier = Modifier.focusRequester(menuOpener)) { Text("Options") }
                Button(onClick = onHome) { Text("Home") }
            }
        }
        if (menuOpen) {
            LaunchedEffect(Unit) { menuFirst.requestFocus() }
            Dialog(onDismissRequest = { menuOpen = false }) {
                Surface(
                    modifier = Modifier.width(600.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = SurfaceDefaults.colors(containerColor = Color(0xFF102239)),
                ) {
                Column(
                    modifier = Modifier.padding(30.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("PLAYER OPTIONS", color = Teal, fontSize = 16.sp, letterSpacing = 2.sp)
                    Button(onClick = playback::cycleDisplayMode, modifier = Modifier.focusRequester(menuFirst)) {
                        Text("Picture: ${state.displayMode.label}")
                    }
                    Button(onClick = playback::toggleSubtitles, enabled = state.subtitlesAvailable) {
                        Text(if (state.subtitlesEnabled) "Subtitles: On" else "Subtitles: Off")
                    }
                    Button(onClick = { playback.adjustSubtitleOffset(-500L) }, enabled = state.hasExternalSubtitles) {
                        Text("Subtitle timing: ${formatOffset(state.subtitleOffsetMs)} −")
                    }
                    Button(onClick = { playback.adjustSubtitleOffset(500L) }, enabled = state.hasExternalSubtitles) { Text("Subtitle timing +") }
                    Button(onClick = playback::cycleSubtitleSize) {
                        Text("Subtitle size: ${listOf("Small", "Medium", "Large", "Extra large")[state.subtitleSizeIndex]}")
                    }
                    Button(onClick = playback::cycleSubtitleColor) {
                        Text("Subtitle color: ${listOf("White", "Yellow", "Cyan", "Green")[state.subtitleColorIndex]}")
                    }
                    Button(onClick = playback::toggleSubtitleBackground) {
                        Text("Subtitle background: ${if (state.subtitleBackgroundEnabled) "On" else "Off"}")
                    }
                    if (state.audioChoices.size > 1) {
                        Text("AUDIO TRACK", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                        state.audioChoices.forEach { choice ->
                            Button(onClick = { playback.selectTrack(choice) }) {
                                Text("${if (choice.selected) "✓ " else ""}${choice.label}")
                            }
                        }
                    }
                    if (state.videoVariantChoices.size > 1) {
                        Text("STREAM VARIANT", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                        Button(onClick = playback::selectAutomaticVideoVariant) {
                            Text("${if (state.selectedVideoVariantKey == null) "✓ " else ""}Auto select")
                        }
                        state.videoVariantChoices.forEach { choice ->
                            Button(onClick = { playback.selectTrack(choice) }) {
                                Text("${if (choice.selected) "✓ " else ""}${choice.label}")
                            }
                        }
                    }
                    state.subtitleError?.let { Text("Subtitle: $it", color = Color(0xFFFFB4A9), fontSize = 14.sp) }
                    Button(onClick = { menuOpen = false }) { Text("Close options") }
                }
                }
            }
        } else if (menuWasOpen) {
            LaunchedEffect(menuOpen) { menuOpener.requestFocus() }
        }
        LaunchedEffect(menuOpen) { menuWasOpen = menuOpen }
        }
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatOffset(milliseconds: Long): String =
    String.format(Locale.US, "%+.1f sec", milliseconds / 1_000.0)

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(52.dp).background(Teal, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = Ink, fontSize = 22.sp)
        }
        Spacer(Modifier.width(16.dp))
        Text("TV PLAYER", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}

@Composable
private fun TVBackground(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Ink),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF142A46), Ink, Color(0xFF0A1220))))
                .padding(horizontal = 72.dp, vertical = 48.dp),
        ) {
            content()
        }
    }
}
