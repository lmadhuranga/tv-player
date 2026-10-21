package dev.lmadhuranga.tvplayer

import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale

private const val CONTROLS_AUTO_HIDE_MS = 4_000L

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun FullscreenPlayerScreen(
    playback: PlaybackController,
    window: Window,
    onChooseSubtitleFile: () -> Unit,
    onAddUrl: () -> Unit,
    onHome: () -> Unit,
) {
    val state by playback.state.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var optionsOpen by remember { mutableStateOf(false) }
    var optionsOpenedFromAudio by remember { mutableStateOf(false) }
    var returningFromOptions by remember { mutableStateOf(false) }
    var consumedDownKeyUp by remember { mutableStateOf(false) }
    var consumedMediaKeyUp by remember { mutableStateOf(false) }
    var consumedHiddenLeftKeyUp by remember { mutableStateOf(false) }
    var consumedHiddenRightKeyUp by remember { mutableStateOf(false) }
    var interactionSerial by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val optionsFirstFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val newLinkFocus = remember { FocusRequester() }

    DisposableEffect(window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    LaunchedEffect(controlsVisible, optionsOpen, state.isPlaying, state.error, state.subtitleError, interactionSerial) {
        if (optionsOpen || !controlsVisible || !state.isPlaying || state.isBuffering || state.error != null || state.subtitleError != null) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    LaunchedEffect(state.error, state.canRetry, state.subtitleError, optionsOpen) {
        if (optionsOpen) return@LaunchedEffect
        when {
            state.error != null -> {
                controlsVisible = true
                interactionSerial++
                withFrameNanos { }
                if (state.canRetry) retryFocus.requestFocus() else newLinkFocus.requestFocus()
            }
            state.subtitleError != null -> {
                controlsVisible = true
                interactionSerial++
                withFrameNanos { }
                optionsFocus.requestFocus()
            }
        }
    }

    LaunchedEffect(optionsOpen) {
        if (optionsOpen) {
            optionsFirstFocus.requestFocus()
        } else if (returningFromOptions) {
            if (optionsOpenedFromAudio) audioFocus.requestFocus() else optionsFocus.requestFocus()
            returningFromOptions = false
        }
    }

    LaunchedEffect(controlsVisible) {
        if (!optionsOpen && !returningFromOptions) {
            if (controlsVisible) {
                when {
                    state.error != null -> if (state.canRetry) retryFocus.requestFocus() else newLinkFocus.requestFocus()
                    state.subtitleError != null -> optionsFocus.requestFocus()
                    state.title != null -> playFocus.requestFocus()
                    else -> backFocus.requestFocus()
                }
            } else {
                rootFocus.requestFocus()
            }
        }
    }

    BackHandler {
        when {
            optionsOpen -> {
                returningFromOptions = true
                optionsOpen = false
            }
            controlsVisible -> {
                if (state.error != null) {
                    onHome()
                } else {
                    controlsVisible = false
                    interactionSerial++
                }
            }
            else -> onHome()
        }
    }

    if (optionsOpen) {
        PlayerOptionsScreen(
            playback = playback,
            state = state,
            firstFocus = optionsFirstFocus,
            onChooseSubtitleFile = onChooseSubtitleFile,
            onAddUrl = onAddUrl,
            onClose = {
                returningFromOptions = true
                optionsOpen = false
            },
        )
        return
    }

    fun interacted(action: () -> Unit) {
        controlsVisible = true
        interactionSerial++
        action()
    }

    fun openOptions(fromAudioButton: Boolean = false) {
        optionsOpenedFromAudio = fromAudioButton
        optionsOpen = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusProperties { canFocus = !controlsVisible }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.MediaPlayPause) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedMediaKeyUp = true
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            interactionSerial++
                            playback.togglePlayPause()
                        }
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedMediaKeyUp) {
                        consumedMediaKeyUp = false
                        true
                    } else {
                        false
                    }
                } else if (event.key == Key.DirectionDown) {
                    if (event.type == KeyEventType.KeyDown && !controlsVisible) {
                        consumedDownKeyUp = true
                        controlsVisible = true
                        interactionSerial++
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedDownKeyUp) {
                        consumedDownKeyUp = false
                        true
                    } else {
                        false
                    }
                } else if (!controlsVisible && state.title != null && event.key == Key.DirectionLeft) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedHiddenLeftKeyUp = true
                        if (event.nativeKeyEvent.repeatCount == 0) playback.seekBy(-30_000L)
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedHiddenLeftKeyUp) {
                        consumedHiddenLeftKeyUp = false
                        true
                    } else false
                } else if (!controlsVisible && state.title != null && event.key == Key.DirectionRight) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedHiddenRightKeyUp = true
                        if (event.nativeKeyEvent.repeatCount == 0) playback.seekBy(30_000L)
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedHiddenRightKeyUp) {
                        consumedHiddenRightKeyUp = false
                        true
                    } else false
                } else {
                    if (event.type == KeyEventType.KeyDown) interactionSerial++
                    false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
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
            Text(
                "Nothing is playing yet",
                color = Color.White,
                fontSize = 28.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (state.isBuffering && !controlsVisible) {
            StatusPill(
                text = "Buffering · buffered to ${formatPlayerTime(state.bufferedPositionMs)}",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!controlsVisible && state.title != null) {
            HiddenPlaybackProgress(
                positionMs = state.positionMs,
                bufferedPositionMs = state.bufferedPositionMs,
                durationMs = state.durationMs,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 58.dp, vertical = 18.dp),
            )
        }

        if (state.error != null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 80.dp),
                shape = RoundedCornerShape(18.dp),
                colors = SurfaceDefaults.colors(containerColor = Color(0xE6101E30)),
            ) {
                Text(state.error!!, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(28.dp))
            }
        }

        if (controlsVisible) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(190.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.86f), Color.Transparent))),
            )
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(270.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f)))),
            )
            Column(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 58.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppButton(onClick = { interacted(onHome) }, modifier = Modifier.focusRequester(backFocus)) { Text("‹  Back", fontSize = 17.sp) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.title ?: "TV PLAYER", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                state.isBuffering -> "Buffering · ${formatPlayerTime(state.bufferedPositionMs)} buffered"
                                state.videoWidth > 0 && state.videoHeight > 0 -> "${state.videoWidth} × ${state.videoHeight}"
                                state.subtitleError != null -> "Subtitle: ${state.subtitleError}"
                                else -> "TV PLAYER"
                            },
                            color = Muted,
                            fontSize = 15.sp,
                        )
                    }
                    AppButton(
                        onClick = { interacted { openOptions() } },
                        modifier = Modifier.focusRequester(optionsFocus),
                    ) { Text("⚙  Options", fontSize = 17.sp) }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 58.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatPlayerTime(state.positionMs), color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text(formatPlayerTime(state.durationMs), color = Muted, fontSize = 16.sp)
                }
                PlayerProgress(
                    positionMs = state.positionMs,
                    bufferedPositionMs = state.bufferedPositionMs,
                    durationMs = state.durationMs,
                    onSeekBy = { delta -> interacted { playback.seekBy(delta) } },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton(
                        onClick = { interacted(playback::toggleSubtitles) },
                        enabled = state.subtitlesAvailable,
                    ) { Text(if (state.subtitlesEnabled) "CC  On" else "CC  Off", fontSize = 16.sp) }
                    if (state.audioChoices.size > 1) {
                        AppButton(
                            onClick = { interacted { openOptions(fromAudioButton = true) } },
                            modifier = Modifier.focusRequester(audioFocus),
                        ) { Text("Audio", fontSize = 16.sp) }
                    }
                    Spacer(Modifier.weight(1f))
                    AppButton(onClick = { interacted { playback.seekBy(-30_000L) } }, enabled = state.title != null) {
                        Text("−30 sec", fontSize = 17.sp)
                    }
                    AppButton(
                        onClick = { interacted(playback::togglePlayPause) },
                        enabled = state.title != null,
                        modifier = Modifier.focusRequester(playFocus),
                    ) { Text(if (state.isPlaying) "Ⅱ  Pause" else "▶  Play", fontSize = 20.sp) }
                    AppButton(onClick = { interacted { playback.seekBy(30_000L) } }, enabled = state.title != null) {
                        Text("+30 sec", fontSize = 17.sp)
                    }
                    AppButton(onClick = { interacted(playback::cycleSpeed) }, enabled = state.title != null) {
                        Text("${state.speed}×", fontSize = 16.sp)
                    }
                }
                state.error?.let { message ->
                    Text(message, color = Color(0xFFFFC2B7), fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppButton(onClick = { interacted(playback::retryPlayback) }, enabled = state.canRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
                        AppButton(onClick = { interacted(onAddUrl) }, modifier = Modifier.focusRequester(newLinkFocus)) { Text("Enter another link") }
                    }
                }
                state.subtitleError?.let { Text("Subtitle unavailable: $it", color = Color(0xFFFFB4A9), fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun PlayerProgress(positionMs: Long, bufferedPositionMs: Long, durationMs: Long, onSeekBy: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(progress, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (focused) 14.dp else 7.dp)
            .background(Color.White.copy(alpha = 0.24f), RoundedCornerShape(8.dp))
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                    onSeekBy(-10_000L)
                    true
                } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                    onSeekBy(10_000L)
                    true
                } else {
                    false
                }
            }
            .focusable(),
    ) {
        Box(Modifier.fillMaxWidth(buffered).height(if (focused) 14.dp else 7.dp).background(Color(0xFFBDBDBD), RoundedCornerShape(8.dp)))
        Box(Modifier.fillMaxWidth(progress).height(if (focused) 14.dp else 7.dp).background(Color(0xFF454545), RoundedCornerShape(8.dp)))
    }
}

@Composable
private fun HiddenPlaybackProgress(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(progress, 1f) else 0f
    Box(modifier.height(5.dp).background(Color(0x66444444), RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(buffered).fillMaxSize().background(Color(0xFFBDBDBD), RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(progress).fillMaxSize().background(Color(0xFF454545), RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = SurfaceDefaults.colors(containerColor = Color(0xCC000000)),
    ) {
        Text(text, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 15.dp))
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun PlayerOptionsScreen(
    playback: PlaybackController,
    state: PlaybackUiState,
    firstFocus: FocusRequester,
    onChooseSubtitleFile: () -> Unit,
    onAddUrl: () -> Unit,
    onClose: () -> Unit,
) {
    TVBackground {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 64.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("PLAYER SETTINGS", color = Teal, fontSize = 15.sp, letterSpacing = 3.sp)
            Text("Picture, audio and subtitles", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Text("Settings stay on this page; the video controls do not open floating panels.", color = Muted, fontSize = 17.sp)
            AppButton(onClick = onChooseSubtitleFile, enabled = state.title != null && !state.subtitleLoading) {
                Text(if (state.subtitleLoading) "Loading subtitle file…" else "Choose subtitle file (.srt or .vtt)")
            }
            state.subtitleError?.let { Text("Subtitle unavailable: $it", color = Color(0xFFFFB4A9), fontSize = 15.sp) }
            state.error?.let { error ->
                Text(error, color = Color(0xFFFFC2B7), fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton(onClick = playback::retryPlayback, enabled = state.canRetry) { Text("Retry") }
                    AppButton(onClick = onAddUrl) { Text("Enter another link") }
                }
            }
            Spacer(Modifier.height(8.dp))
            AppButton(onClick = playback::cycleDisplayMode, modifier = Modifier.focusRequester(firstFocus)) {
                Text("Picture size: ${state.displayMode.label}")
            }
            AppButton(onClick = playback::cycleSpeed, enabled = state.title != null) { Text("Playback speed: ${state.speed}×") }
            AppButton(onClick = playback::toggleSubtitles, enabled = state.subtitlesAvailable) {
                Text(if (state.subtitlesEnabled) "Subtitles: On" else "Subtitles: Off")
            }
            AppButton(onClick = { playback.adjustSubtitleOffset(-500L) }, enabled = state.hasExternalSubtitles) {
                Text("Subtitle timing: ${formatPlayerOffset(state.subtitleOffsetMs)} −")
            }
            AppButton(onClick = { playback.adjustSubtitleOffset(500L) }, enabled = state.hasExternalSubtitles) { Text("Subtitle timing +") }
            AppButton(onClick = playback::cycleSubtitleSize) {
                Text("Subtitle size: ${listOf("Small", "Medium", "Large", "Extra large")[state.subtitleSizeIndex]}")
            }
            AppButton(onClick = playback::cycleSubtitleColor) {
                Text("Subtitle color: ${listOf("White", "Yellow", "Cyan", "Green")[state.subtitleColorIndex]}")
            }
            AppButton(onClick = playback::toggleSubtitleBackground) {
                Text("Subtitle background: ${if (state.subtitleBackgroundEnabled) "On" else "Off"}")
            }
            if (state.audioChoices.size > 1) {
                Text("AUDIO TRACK", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                state.audioChoices.forEach { choice ->
                    AppButton(onClick = { playback.selectTrack(choice) }) {
                        Text("${if (choice.selected) "✓ " else ""}${choice.label}")
                    }
                }
            }
            if (state.videoVariantChoices.size > 1) {
                Text("STREAM VARIANT", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                AppButton(onClick = playback::selectAutomaticVideoVariant) {
                    Text("${if (state.selectedVideoVariantKey == null) "✓ " else ""}Auto select")
                }
                state.videoVariantChoices.forEach { choice ->
                    AppButton(onClick = { playback.selectTrack(choice) }) {
                        Text("${if (choice.selected) "✓ " else ""}${choice.label}")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            AppButton(onClick = onClose) { Text("Back to video") }
        }
    }
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private fun formatPlayerOffset(milliseconds: Long): String =
    String.format(Locale.US, "%+.1f sec", milliseconds / 1_000.0)
