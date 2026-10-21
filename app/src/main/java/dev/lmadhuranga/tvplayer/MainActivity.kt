package dev.lmadhuranga.tvplayer

import android.os.Bundle
import android.view.Window
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi

import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

internal val Ink = Color(0xFF07111F)
internal val Teal = Color(0xFF5BD5C6)
internal val Muted = Color(0xFFB9C8D9)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MainActivity : ComponentActivity() {
    private lateinit var playback: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val history = HistoryRepository(applicationContext)
        playback = PlaybackController(applicationContext, history)
        setContent {
            MaterialTheme {
                TVPlayerApp(playback, history, window)
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
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun TVPlayerApp(playback: PlaybackController, historyRepository: HistoryRepository, window: Window) {
    var route by remember { mutableStateOf(AppRoute.HANDOFF) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsWasOpen by remember { mutableStateOf(false) }
    val settingsOpenerRequester = remember { FocusRequester() }
    val settingsCloseRequester = remember { FocusRequester() }
    val rememberPositions by historyRepository.enabled.collectAsState(initial = false)
    val recentEntries by historyRepository.entries.collectAsState(initial = emptyList())
    val playbackState by playback.state.collectAsState()
    val appScope = rememberCoroutineScope()
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playback.loadSubtitleFile(uri)
    }
    var pendingStart by remember { mutableStateOf<PendingPlayback?>(null) }

    fun startVideo(url: String, subtitleUrl: String?) {
        val candidates = recentEntries.filter { it.positionMs > 5_000L }
        if (rememberPositions && candidates.isNotEmpty()) {
            pendingStart = PendingPlayback(url, subtitleUrl, candidates)
        } else {
            playback.playUrl(url, subtitleUrl)
            route = AppRoute.PLAYER
        }
    }

    BackHandler(enabled = settingsOpen || (route != AppRoute.HOME && route != AppRoute.PLAYER)) {
        if (settingsOpen) settingsOpen = false else {
            if (route == AppRoute.PLAYER) playback.pauseForBackground()
            route = AppRoute.HOME
        }
    }

    when (route) {
        AppRoute.HOME -> HomeScreen(
            settingsRequester = settingsOpenerRequester,
            onOpenSettings = { settingsOpen = true },
        )

        AppRoute.ADD_URL -> VideoUrlEntryScreen(
            onStart = { url, subtitleUrl ->
                startVideo(url, subtitleUrl)
            },
            onBack = { route = AppRoute.HOME },
        )

        AppRoute.HANDOFF -> HandoffScreen(
            cacheDirectory = playback.cacheDirectory,
            hasActiveMedia = playbackState.hasMedia,
            settingsRequester = settingsOpenerRequester,
            onVideoSubmitted = { submission ->
                pendingStart = null
                if (submission.uploadedVideo != null) {
                    playback.playUploadedFile(
                        submission.uploadedVideo,
                        submission.uploadedVideoName ?: submission.uploadedVideo.name,
                        submission.subtitleUrl,
                        submission.uploadedSubtitle,
                    )
                } else {
                    playback.playUrl(submission.videoUrl, submission.subtitleUrl, uploadedSubtitleFile = submission.uploadedSubtitle)
                }
                route = AppRoute.PLAYER
            },
            onBack = { route = AppRoute.HOME },
            onGoToPlayer = { route = AppRoute.PLAYER },
            onOpenSettings = { settingsOpen = true },
        )

        AppRoute.PLAYER -> FullscreenPlayerScreen(playback = playback, window = window, onChooseSubtitleFile = {
            subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
        }, onAddUrl = {
            playback.pauseForBackground()
            route = AppRoute.ADD_URL
        }, onHome = {
            playback.pauseForBackground()
            route = AppRoute.HANDOFF
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
            entries = pending.entries,
            onResume = { entry ->
                playback.playUrl(pending.url, pending.subtitleUrl, entry.positionMs)
                pendingStart = null
                route = AppRoute.PLAYER
            },
            onStartOver = {
                playback.playUrl(pending.url, pending.subtitleUrl)
                pendingStart = null
                route = AppRoute.PLAYER
            },
            onCancel = { pendingStart = null },
        )
    }
}

private data class PendingPlayback(val url: String, val subtitleUrl: String?, val entries: List<HistoryEntry>)

@Composable
private fun HomeScreen(
    settingsRequester: FocusRequester,
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
                AppButton(
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
                AppButton(onClick = onToggleRemember, modifier = Modifier.focusRequester(focusRequester)) {
                    Text("Remember positions: ${if (rememberPositions) "On" else "Off"}")
                }
                if (entries.isNotEmpty()) {
                    Text("RECENT POSITIONS", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                    entries.forEach { entry ->
                        Text("${entry.title} · ${entry.sourceHost} · ${formatTime(entry.positionMs)}", color = Color.White, fontSize = 17.sp)
                    }
                    AppButton(onClick = onClearHistory) { Text("Clear history") }
                } else {
                    Text("No saved positions.", color = Muted, fontSize = 17.sp)
                }
                AppButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ResumePrompt(entries: List<HistoryEntry>, onResume: (HistoryEntry) -> Unit, onStartOver: () -> Unit, onCancel: () -> Unit) {
    val resumeRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onCancel) {
        LaunchedEffect(Unit) { resumeRequester.requestFocus() }
        Surface(
            modifier = Modifier.width(700.dp),
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF102239)),
        ) {
            Column(Modifier.padding(36.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RESUME VIDEO?", color = Teal, fontSize = 16.sp, letterSpacing = 2.sp)
                Text("Choose a saved position. No link matching is inferred, and the new link may point to different content.", color = Muted, fontSize = 17.sp)
                entries.forEachIndexed { index, entry ->
                    val label = entry.title.ifBlank { entry.sourceHost.ifBlank { "Video" } }
                    AppButton(onClick = { onResume(entry) }, modifier = if (index == 0) Modifier.focusRequester(resumeRequester) else Modifier) {
                        Text("Resume $label · ${formatTime(entry.positionMs)}")
                    }
                }
                AppButton(onClick = onStartOver) { Text("Start from the beginning") }
                AppButton(onClick = onCancel) { Text("Cancel") }
            }
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
internal fun TVBackground(content: @Composable () -> Unit) {
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
