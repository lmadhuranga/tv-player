package dev.lmadhuranga.tvplayer

import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale

private const val CONTROLS_AUTO_HIDE_MS = 4_000L

private enum class OptionsFocusOrigin { HEADER, AUDIO, BOTTOM }
private enum class PlayerTransportGlyph { PREVIOUS, PLAY, PAUSE, NEXT }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun FullscreenPlayerScreen(
    playback: PlaybackController,
    window: Window,
    maximumBufferMinutes: Int,
    onMaximumBufferMinutesChange: (Int) -> Unit,
    onChooseSubtitleFile: () -> Unit,
    onAddUrl: () -> Unit,
    onHome: () -> Unit,
) {
    val state by playback.state.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var optionsOpen by remember { mutableStateOf(false) }
    var optionsFocusOrigin by remember { mutableStateOf(OptionsFocusOrigin.HEADER) }
    var returningFromOptions by remember { mutableStateOf(false) }
    var consumedDownKeyUp by remember { mutableStateOf(false) }
    var consumedMediaKeyUp by remember { mutableStateOf(false) }
    var consumedHiddenSelectKeyUp by remember { mutableStateOf(false) }
    var consumedHiddenLeftKeyUp by remember { mutableStateOf(false) }
    var consumedHiddenRightKeyUp by remember { mutableStateOf(false) }
    var hiddenLeftLastRepeatCount by remember { mutableIntStateOf(0) }
    var hiddenRightLastRepeatCount by remember { mutableIntStateOf(0) }
    var consumedSkipBackwardKeyUp by remember { mutableStateOf(false) }
    var consumedSkipForwardKeyUp by remember { mutableStateOf(false) }
    var interactionSerial by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val bottomOptionsFocus = remember { FocusRequester() }
    val optionsFirstFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val newLinkFocus = remember { FocusRequester() }
    val emptyPlayerFocus = remember { FocusRequester() }

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
            when (optionsFocusOrigin) {
                OptionsFocusOrigin.HEADER -> optionsFocus.requestFocus()
                OptionsFocusOrigin.AUDIO -> audioFocus.requestFocus()
                OptionsFocusOrigin.BOTTOM -> bottomOptionsFocus.requestFocus()
            }
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
                    else -> emptyPlayerFocus.requestFocus()
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
            maximumBufferMinutes = maximumBufferMinutes,
            onMaximumBufferMinutesChange = onMaximumBufferMinutesChange,
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

    fun openOptions(origin: OptionsFocusOrigin) {
        optionsFocusOrigin = origin
        optionsOpen = true
    }

    fun seekTenSeconds(deltaMs: Long) {
        interacted { playback.seekBy(deltaMs) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusProperties { canFocus = !controlsVisible }
            .onPreviewKeyEvent { event ->
                val nativeKeyCode = event.nativeKeyEvent.keyCode
                if (nativeKeyCode in setOf(
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                    ) && (consumedHiddenSelectKeyUp || (!controlsVisible && state.title != null))
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedHiddenSelectKeyUp = true
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            interactionSerial++
                            playback.togglePlayPause()
                        }
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedHiddenSelectKeyUp) {
                        consumedHiddenSelectKeyUp = false
                        true
                    } else false
                } else if (nativeKeyCode == android.view.KeyEvent.KEYCODE_MEDIA_REWIND ||
                    nativeKeyCode == android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedSkipBackwardKeyUp = true
                        if (event.nativeKeyEvent.repeatCount == 0 && state.title != null) seekTenSeconds(-10_000L)
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedSkipBackwardKeyUp) {
                        consumedSkipBackwardKeyUp = false
                        true
                    } else false
                } else if (nativeKeyCode == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                    nativeKeyCode == android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedSkipForwardKeyUp = true
                        if (event.nativeKeyEvent.repeatCount == 0 && state.title != null) seekTenSeconds(10_000L)
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedSkipForwardKeyUp) {
                        consumedSkipForwardKeyUp = false
                        true
                    } else false
                } else if (event.key == Key.MediaPlayPause) {
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
                } else if (event.key == Key.DirectionLeft &&
                    (consumedHiddenLeftKeyUp || (!controlsVisible && state.title != null))
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedHiddenLeftKeyUp = true
                        val repeatCount = event.nativeKeyEvent.repeatCount
                        if (repeatCount > hiddenLeftLastRepeatCount) {
                            if (state.title != null) playback.seekBy(-120_000L * (repeatCount - hiddenLeftLastRepeatCount))
                            hiddenLeftLastRepeatCount = repeatCount
                        }
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedHiddenLeftKeyUp) {
                        consumedHiddenLeftKeyUp = false
                        if (hiddenLeftLastRepeatCount == 0 && state.title != null) playback.seekBy(-30_000L)
                        hiddenLeftLastRepeatCount = 0
                        true
                    } else false
                } else if (event.key == Key.DirectionRight &&
                    (consumedHiddenRightKeyUp || (!controlsVisible && state.title != null))
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        consumedHiddenRightKeyUp = true
                        val repeatCount = event.nativeKeyEvent.repeatCount
                        if (repeatCount > hiddenRightLastRepeatCount) {
                            if (state.title != null) playback.seekBy(120_000L * (repeatCount - hiddenRightLastRepeatCount))
                            hiddenRightLastRepeatCount = repeatCount
                        }
                        true
                    } else if (event.type == KeyEventType.KeyUp && consumedHiddenRightKeyUp) {
                        consumedHiddenRightKeyUp = false
                        if (hiddenRightLastRepeatCount == 0 && state.title != null) playback.seekBy(30_000L)
                        hiddenRightLastRepeatCount = 0
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

        if (state.title != null && !state.isPlaying && !state.isBuffering && state.error == null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).size(88.dp)
                    .semantics { contentDescription = "Video paused" },
                shape = CircleShape,
                colors = SurfaceDefaults.colors(
                    containerColor = Color(0x99000000),
                    contentColor = Color.White,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PlayerTransportIcon(PlayerTransportGlyph.PAUSE)
                }
            }
        }

        if (state.title == null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Your screen. Your video.",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Send a video from your phone, or add a direct link from Options.",
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 18.sp,
                )
                AppButton(
                    onClick = { interacted(onAddUrl) },
                    modifier = Modifier.focusRequester(emptyPlayerFocus),
                ) { Text("Add a video link", fontSize = 17.sp) }
            }
        }

        if (state.isBuffering && !controlsVisible) {
            StatusPill(
                text = "Buffering · buffered to ${formatPlayerTime(state.bufferedPositionMs)}",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!controlsVisible && state.title != null) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 58.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatPlayerTime(state.positionMs), color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                HiddenPlaybackProgress(
                    positionMs = state.positionMs,
                    bufferedPositionMs = state.bufferedPositionMs,
                    durationMs = state.durationMs,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(formatPlayerTime(state.durationMs), color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp)
            }
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
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 32.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlayerCircleButton(
                        onClick = { interacted(onHome) },
                        modifier = Modifier.focusRequester(backFocus).semantics { contentDescription = "Back" },
                    ) { Text("‹", fontSize = 34.sp) }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.title ?: "TV PLAYER",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                    PlayerCircleButton(
                        onClick = { interacted { openOptions(OptionsFocusOrigin.HEADER) } },
                        modifier = Modifier.focusRequester(optionsFocus).semantics { contentDescription = "Player options" },
                    ) { Text("⚙", fontSize = 22.sp) }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { message ->
                    Text(message, color = Color(0xFFFFC2B7), fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppButton(onClick = { interacted(playback::retryPlayback) }, enabled = state.canRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
                        AppButton(onClick = { interacted(onAddUrl) }, modifier = Modifier.focusRequester(newLinkFocus)) { Text("Enter another link") }
                    }
                }
                state.subtitleError?.let { Text("Subtitle unavailable: $it", color = Color(0xFFFFB4A9), fontSize = 14.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (controlsVisible && !optionsOpen) {
                        Text(
                            formatPlayerTime(state.durationMs),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            modifier = Modifier.semantics {
                                contentDescription = "Total duration ${formatPlayerTime(state.durationMs)}"
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    PlayerProgress(
                        positionMs = state.positionMs,
                        bufferedPositionMs = state.bufferedPositionMs,
                        durationMs = state.durationMs,
                        onSeekBy = { delta -> interacted { playback.seekBy(delta) } },
                        modifier = Modifier.weight(1f),
                    )
                    if (controlsVisible && !optionsOpen) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            formatPlayerTime(state.positionMs),
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp,
                            modifier = Modifier.semantics {
                                contentDescription = "Elapsed time ${formatPlayerTime(state.positionMs)}"
                            },
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    SubtitleAudioControls(
                        subtitlesEnabled = state.subtitlesEnabled,
                        subtitlesAvailable = state.subtitlesAvailable,
                        showAudio = state.audioChoices.size > 1,
                        audioFocus = audioFocus,
                        modifier = Modifier.align(Alignment.CenterStart),
                        onToggleSubtitles = { interacted(playback::toggleSubtitles) },
                        onOpenAudio = { interacted { openOptions(OptionsFocusOrigin.AUDIO) } },
                    )
                    TransportControls(
                        isPlaying = state.isPlaying,
                        enabled = state.title != null,
                        playFocus = playFocus,
                        modifier = Modifier.align(Alignment.Center),
                        onSeekBackward = { seekTenSeconds(-10_000L) },
                        onTogglePlayback = { interacted(playback::togglePlayPause) },
                        onSeekForward = { seekTenSeconds(10_000L) },
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlayerCircleButton(
                            onClick = { interacted(playback::cycleSpeed) },
                            enabled = state.title != null,
                            modifier = Modifier.semantics { contentDescription = "Playback speed: ${state.speed}×" },
                        ) { Text("${state.speed}×", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        PlayerCircleButton(
                            onClick = { interacted { openOptions(OptionsFocusOrigin.BOTTOM) } },
                            modifier = Modifier.focusRequester(bottomOptionsFocus)
                                .semantics { contentDescription = "Options" },
                        ) { Text("⋯", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleAudioControls(
    subtitlesEnabled: Boolean,
    subtitlesAvailable: Boolean,
    showAudio: Boolean,
    audioFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onToggleSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlayerCircleButton(
            onClick = onToggleSubtitles,
            enabled = subtitlesAvailable,
            modifier = Modifier.semantics { contentDescription = if (subtitlesEnabled) "Turn subtitles off" else "Turn subtitles on" },
        ) { Text("CC", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        if (showAudio) {
            PlayerCircleButton(onClick = onOpenAudio, modifier = Modifier.focusRequester(audioFocus).semantics { contentDescription = "Audio tracks" }) {
                Text("◖))", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    enabled: Boolean,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onSeekBackward: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekForward: () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PlayerCircleButton(onClick = {}, enabled = false, modifier = Modifier.semantics { contentDescription = "Previous item unavailable" }, diameter = 56.dp) {
            PlayerTransportIcon(PlayerTransportGlyph.PREVIOUS)
        }
        PlayerCircleButton(
            onClick = onSeekBackward,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Seek backward 10 seconds" },
            diameter = 56.dp,
        ) { Text("-10", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        PlayerCircleButton(
            onClick = onTogglePlayback,
            enabled = enabled,
            modifier = Modifier.focusRequester(playFocus).semantics {
                contentDescription = if (isPlaying) "Pause video" else "Play video"
            },
            diameter = 64.dp,
        ) { PlayerTransportIcon(if (isPlaying) PlayerTransportGlyph.PAUSE else PlayerTransportGlyph.PLAY) }
        PlayerCircleButton(
            onClick = onSeekForward,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Seek forward 10 seconds" },
            diameter = 56.dp,
        ) { Text("+10", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        PlayerCircleButton(onClick = {}, enabled = false, modifier = Modifier.semantics { contentDescription = "Next item unavailable" }, diameter = 56.dp) {
            PlayerTransportIcon(PlayerTransportGlyph.NEXT)
        }
    }
}

@Composable
private fun PlayerCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: androidx.compose.ui.unit.Dp = 50.dp,
    content: @Composable () -> Unit,
) {
    TvButton(
        onClick = onClick,
        modifier = modifier.size(diameter),
        enabled = enabled,
        shape = ButtonDefaults.shape(
            shape = CircleShape,
            focusedShape = CircleShape,
            pressedShape = CircleShape,
            disabledShape = CircleShape,
            focusedDisabledShape = CircleShape,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF252525),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
            pressedContainerColor = Color.White,
            pressedContentColor = Color(0xFF111111),
            disabledContainerColor = Color(0xFF151515),
            disabledContentColor = Color(0xFF5E5E5E),
        ),
        content = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { content() }
        },
    )
}

@Composable
private fun PlayerTransportIcon(glyph: PlayerTransportGlyph) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        when (glyph) {
            PlayerTransportGlyph.PREVIOUS -> {
                drawRect(color, Offset(w * 0.19f, h * 0.18f), Size(w * 0.12f, h * 0.64f))
                drawPath(
                    Path().apply {
                        moveTo(w * 0.84f, h * 0.18f)
                        lineTo(w * 0.42f, h * 0.5f)
                        lineTo(w * 0.84f, h * 0.82f)
                        close()
                    },
                    color,
                )
            }
            PlayerTransportGlyph.PLAY -> drawPath(
                Path().apply {
                    moveTo(w * 0.30f, h * 0.14f)
                    lineTo(w * 0.99f, h * 0.5f)
                    lineTo(w * 0.30f, h * 0.86f)
                    close()
                },
                color,
            )
            PlayerTransportGlyph.PAUSE -> {
                drawRect(color, Offset(w * 0.27f, h * 0.15f), Size(w * 0.17f, h * 0.70f))
                drawRect(color, Offset(w * 0.58f, h * 0.15f), Size(w * 0.17f, h * 0.70f))
            }
            PlayerTransportGlyph.NEXT -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.16f, h * 0.18f)
                        lineTo(w * 0.58f, h * 0.5f)
                        lineTo(w * 0.16f, h * 0.82f)
                        close()
                    },
                    color,
                )
                drawRect(color, Offset(w * 0.69f, h * 0.18f), Size(w * 0.12f, h * 0.64f))
            }
        }
    }
}

@Composable
private fun PlayerProgress(positionMs: Long, bufferedPositionMs: Long, durationMs: Long, onSeekBy: (Long) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(progress, 1f) else 0f
    Box(
        modifier = Modifier
            .then(modifier)
            .height(12.dp)
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
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.CenterStart).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(2.dp)))
        Box(Modifier.fillMaxWidth(buffered).height(1.dp).align(Alignment.CenterStart).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(2.dp)))
        Box(Modifier.fillMaxWidth(progress).height(1.dp).align(Alignment.CenterStart).background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(2.dp)))
        Box(Modifier.fillMaxWidth(progress).height(12.dp), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.size(if (focused) 12.dp else 8.dp).background(if (focused) Color(0xFF52A6FF) else Color.White, CircleShape))
        }
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
    Box(modifier.height(2.5.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(buffered).fillMaxSize().background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp)))
        Box(Modifier.fillMaxWidth(progress).fillMaxSize().background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp)))
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
    maximumBufferMinutes: Int,
    onMaximumBufferMinutesChange: (Int) -> Unit,
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
            Text("VIDEO QUALITY", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
            if (state.videoVariantChoices.size > 1) {
                AppButton(onClick = playback::selectAutomaticVideoVariant) {
                    Text("${if (state.selectedVideoVariantKey == null) "✓ " else ""}Auto select")
                }
                state.videoVariantChoices.forEach { choice ->
                    AppButton(onClick = { playback.selectTrack(choice) }) {
                        Text("${if (choice.selected) "✓ " else ""}${choice.label}")
                    }
                }
            } else {
                Text("No alternate quality available for this video", color = Muted, fontSize = 16.sp)
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
