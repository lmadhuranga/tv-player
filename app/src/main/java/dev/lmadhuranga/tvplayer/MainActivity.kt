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
        val playerSettings = PlayerSettingsRepository(applicationContext)
        playback = PlaybackController(applicationContext, history)
        setContent {
            MaterialTheme {
                TVPlayerApp(playback, history, playerSettings, window)
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
private fun TVPlayerApp(
    playback: PlaybackController,
    historyRepository: HistoryRepository,
    playerSettingsRepository: PlayerSettingsRepository,
    window: Window,
) {
    var route by remember { mutableStateOf(AppRoute.HANDOFF) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsWasOpen by remember { mutableStateOf(false) }
    val settingsOpenerRequester = remember { FocusRequester() }
    val settingsCloseRequester = remember { FocusRequester() }
    val rememberPositions by historyRepository.enabled.collectAsState(initial = true)
    val recentEntries by historyRepository.entries.collectAsState(initial = emptyList())
    val playbackState by playback.state.collectAsState()
    val maximumBufferMinutes by playerSettingsRepository.maximumBufferMinutes.collectAsState(
        initial = PlayerSettingsRepository.DEFAULT_BUFFER_MINUTES,
    )
    val appScope = rememberCoroutineScope()
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playback.loadSubtitleFile(uri)
    }
    var pendingStart by remember { mutableStateOf<PendingPlayback?>(null) }

    LaunchedEffect(maximumBufferMinutes) {
        playback.setMaximumBufferMinutes(maximumBufferMinutes)
    }

    fun startVideo(url: String, subtitleUrl: String?) {
        val identity = HistoryRepository.mediaIdentity(url)
        val sameVideo = identity?.let { mediaIdentity ->
            recentEntries.firstOrNull { it.mediaUrl?.let(HistoryRepository::mediaIdentity) == mediaIdentity }
        }
        val playbackSubtitleUrl = subtitleUrl ?: sameVideo?.subtitleUrl
        val candidates = if (identity == null) emptyList() else recentEntries.filter {
            it.positionMs > 5_000L && it.mediaUrl?.let(HistoryRepository::mediaIdentity) == identity
        }
        if (rememberPositions && identity != null && candidates.isNotEmpty()) {
            pendingStart = PendingPlayback(url, playbackSubtitleUrl, candidates)
        } else {
            playback.playUrl(url, playbackSubtitleUrl)
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
            entries = recentEntries,
            settingsRequester = settingsOpenerRequester,
            onOpenSettings = { settingsOpen = true },
            onReplay = { entry ->
                val url = entry.mediaUrl ?: return@HomeScreen
                playback.playUrl(url, entry.subtitleUrl, entry.positionMs.takeIf { it > 5_000L } ?: 0L)
                route = AppRoute.PLAYER
            },
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
            recentEntries = recentEntries,
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
            onApprovalPending = { pending -> if (pending) settingsOpen = false },
            onReplay = { entry ->
                val url = entry.mediaUrl ?: return@HandoffScreen
                playback.playUrl(url, entry.subtitleUrl, entry.positionMs.takeIf { it > 5_000L } ?: 0L)
                route = AppRoute.PLAYER
            },
        )

        AppRoute.PLAYER -> FullscreenPlayerScreen(
            playback = playback,
            window = window,
            maximumBufferMinutes = maximumBufferMinutes,
            onMaximumBufferMinutesChange = { minutes ->
                playback.setMaximumBufferMinutes(minutes)
                appScope.launch { playerSettingsRepository.setMaximumBufferMinutes(minutes) }
            },
            onChooseSubtitleFile = {
                subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
            },
            onAddUrl = {
                playback.pauseForBackground()
                route = AppRoute.ADD_URL
            },
            onHome = {
                playback.pauseForBackground()
                route = AppRoute.HANDOFF
            },
        )
    }

    if (settingsOpen) {
        LaunchedEffect(Unit) { settingsCloseRequester.requestFocus() }
        SettingsScreen(
            focusRequester = settingsCloseRequester,
            rememberPositions = rememberPositions,
            maximumBufferMinutes = maximumBufferMinutes,
            entries = recentEntries,
            onToggleRemember = { appScope.launch { historyRepository.setEnabled(!rememberPositions) } },
            onMaximumBufferMinutesChange = { minutes ->
                playback.setMaximumBufferMinutes(minutes)
                appScope.launch { playerSettingsRepository.setMaximumBufferMinutes(minutes) }
            },
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
    entries: List<HistoryEntry>,
    settingsRequester: FocusRequester,
    onOpenSettings: () -> Unit,
    onReplay: (HistoryEntry) -> Unit,
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
                    modifier = Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("RECENTLY PLAYED", color = Teal, fontSize = 14.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(10.dp))
                    val replayable = entries.filter { it.mediaUrl != null }.take(3)
                    if (replayable.isEmpty()) {
                        Text("Play a direct video link to see it here.", color = Muted, fontSize = 17.sp)
                    } else {
                        replayable.forEach { entry ->
                            AppButton(onClick = { onReplay(entry) }) {
                                Column {
                                    Text(entry.title.ifBlank { entry.sourceHost.ifBlank { "Video" } }, fontSize = 17.sp)
                                    Text("${entry.sourceHost} · ${formatTime(entry.positionMs)}", color = Muted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    focusRequester: FocusRequester,
    rememberPositions: Boolean,
    maximumBufferMinutes: Int,
    entries: List<HistoryEntry>,
    onToggleRemember: () -> Unit,
    onMaximumBufferMinutesChange: (Int) -> Unit,
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
                Text("History can save direct video and subtitle URLs on this device so you can replay them. Links may expire. App data backups may include these saved URLs.", color = Muted, fontSize = 18.sp)
                AppButton(onClick = onToggleRemember, modifier = Modifier.focusRequester(focusRequester)) {
                    Text("Remember positions: ${if (rememberPositions) "On" else "Off"}")
                }
                Text("MAXIMUM BUFFER", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                Text(
                    "Buffers up to the selected time. Playback starts as soon as enough video is ready. Memory and stream limits may reduce the buffer. Applies to the next video.",
                    color = Muted,
                    fontSize = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 5).forEach { minutes ->
                        AppButton(onClick = { onMaximumBufferMinutesChange(minutes) }) {
                            Text("$minutes minute${if (minutes == 1) "" else "s"}${if (maximumBufferMinutes == minutes) " · Selected" else ""}")
                        }
                    }
                }
                if (entries.isNotEmpty()) {
                    Text("RECENT POSITIONS", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                    entries.forEach { entry ->
                        Text("${entry.title} · ${entry.sourceHost} · ${formatTime(entry.positionMs)}", color = Color.White, fontSize = 17.sp)
                    }
                    AppButton(onClick = onClearHistory) { Text("Clear history") }
                } else {
                    Text("No playback history.", color = Muted, fontSize = 17.sp)
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
