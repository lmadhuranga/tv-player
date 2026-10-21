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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

private val Ink = Color(0xFF07111F)
private val Teal = Color(0xFF5BD5C6)
private val Muted = Color(0xFFB9C8D9)

class MainActivity : ComponentActivity() {
    private lateinit var playback: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playback = PlaybackController(applicationContext)
        setContent {
            MaterialTheme {
                TVPlayerApp(playback)
            }
        }
    }

    override fun onDestroy() {
        playback.release()
        super.onDestroy()
    }
}

private enum class AppRoute { HOME, ADD_URL, PLAYER }

@Composable
private fun TVPlayerApp(playback: PlaybackController) {
    var route by remember { mutableStateOf(AppRoute.HOME) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsWasOpen by remember { mutableStateOf(false) }
    val settingsOpenerRequester = remember { FocusRequester() }
    val settingsCloseRequester = remember { FocusRequester() }

    BackHandler(enabled = settingsOpen || route != AppRoute.HOME) {
        if (settingsOpen) settingsOpen = false else route = AppRoute.HOME
    }

    when (route) {
        AppRoute.HOME -> HomeScreen(
            settingsRequester = settingsOpenerRequester,
            onOpenPlayer = { route = AppRoute.PLAYER },
            onAddVideo = { route = AppRoute.ADD_URL },
            onOpenSettings = { settingsOpen = true },
        )

        AppRoute.ADD_URL -> VideoUrlEntryScreen(
            onStart = { url, label ->
                playback.playUrl(url, "Video from $label")
                route = AppRoute.PLAYER
            },
            onBack = { route = AppRoute.HOME },
        )

        AppRoute.PLAYER -> PlayerScreen(playback = playback, onHome = { route = AppRoute.HOME })
    }

    if (settingsOpen) {
        LaunchedEffect(Unit) { settingsCloseRequester.requestFocus() }
        SettingsPlaceholder(focusRequester = settingsCloseRequester, onDismiss = { settingsOpen = false })
    } else if (settingsWasOpen) {
        LaunchedEffect(settingsOpen) { settingsOpenerRequester.requestFocus() }
    }
    LaunchedEffect(settingsOpen) { settingsWasOpen = settingsOpen }
}

@Composable
private fun HomeScreen(
    settingsRequester: FocusRequester,
    onOpenPlayer: () -> Unit,
    onAddVideo: () -> Unit,
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
                    Text("Player setup is coming next", color = Color.White, fontSize = 23.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Enter a video link to get started.", color = Muted, fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsPlaceholder(focusRequester: FocusRequester, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xD907111F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(72.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("SETTINGS", color = Teal, fontSize = 15.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(16.dp))
            Text("Settings will appear here.", color = Color.White, fontSize = 32.sp)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(focusRequester)) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun PlayerScreen(playback: PlaybackController, onHome: () -> Unit) {
    val state by playback.state.collectAsState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Ink),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
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
                    update = { view -> if (view.player !== playback.player) view.player = playback.player },
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
                Button(onClick = onHome) { Text("Home") }
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
