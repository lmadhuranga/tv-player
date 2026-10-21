@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tvplayer.app

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import java.util.Locale

enum class TvScreen { HOME, URL_ENTRY, HANDOFF, HISTORY, SETTINGS, PLAYER, CONTROLS, SUBTITLES, DISPLAY, TRACKS, RESUME }

private val sky = Color(0xFF68D4FF)
private val foreground = Color(0xFFF1F8FF)
private val muted = Color(0xFFB1C3D1)
private val card = Color(0xFF173047)
private val background = Color(0xFF071522)

@Composable
fun TvPlayerApp(
    screen: TvScreen, settings: AppSettings, recents: List<RecentItem>, engine: PlaybackEngine,
    handoff: LanHandoffServer.Info?, notification: String?, hasPendingResume: Boolean,
    recentNeedsUrl: Boolean,
    onScreen: (TvScreen) -> Unit, onSubmit: (VideoEntry) -> Unit,
    onSettings: (AppSettings) -> Unit, onClearHistory: () -> Unit,
    onRecentItem: (RecentItem) -> Unit, onResumeChoice: (Boolean) -> Unit,
    onReloadSubtitles: () -> Unit, onBack: () -> Unit, onNoticeDismiss: () -> Unit
) {
    BackHandler(enabled = screen != TvScreen.HOME) { onBack() }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0C243A), background)))) {
            if (screen == TvScreen.PLAYER || screen in setOf(TvScreen.CONTROLS, TvScreen.SUBTITLES, TvScreen.DISPLAY, TvScreen.TRACKS)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> PlayerView(context).apply { player = engine.player; useController = false; keepScreenOn = true } },
                    update = { view ->
                        view.player = engine.player
                        engine.applyViewSettings(view, settings)
                    }
                )
            }
            Box(Modifier.fillMaxSize().then(if (screen == TvScreen.PLAYER) Modifier
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.MediaRewind -> { engine.skip(-10_000); true }
                        Key.MediaFastForward -> { engine.skip(10_000); true }
                        Key.MediaPlayPause -> { engine.toggle(); true }
                        else -> false
                    }
                } else Modifier)) {
                when (screen) {
                    TvScreen.HOME -> HomeScreen(engine.hasMedia, onScreen)
                    TvScreen.URL_ENTRY -> EntryScreen(notification, recentNeedsUrl, onSubmit, onBack)
                    TvScreen.HANDOFF -> HandoffScreen(handoff, onBack)
                    TvScreen.HISTORY -> HistoryScreen(recents, onRecentItem, onClearHistory, onBack)
                    TvScreen.SETTINGS -> SettingsScreen(settings, onSettings, onBack)
                    TvScreen.RESUME -> ResumeScreen(hasPendingResume, onResumeChoice, onBack)
                    TvScreen.PLAYER -> PlayerScreen(engine, notification, onScreen, onNoticeDismiss)
                    TvScreen.CONTROLS -> PlaybackMenu(engine, onScreen)
                    TvScreen.SUBTITLES -> SubtitleMenu(engine, settings, onSettings, onReloadSubtitles, onScreen)
                    TvScreen.DISPLAY -> DisplayMenu(engine, settings, onSettings, onScreen)
                    TvScreen.TRACKS -> TracksMenu(engine, onScreen)
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, subtitle: String? = null) {
    Text(text, color = foreground, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    if (subtitle != null) {
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = muted, fontSize = 17.sp)
    }
}

@Composable
private fun TvAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, secondary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) sky else if (secondary) Color(0xFF11263A) else card)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0xFF34516A), RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = if (focused) background else foreground, fontSize = 19.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScreenColumn(title: String, caption: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 54.dp, vertical = 33.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Title(title, caption)
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HomeScreen(hasMedia: Boolean, onScreen: (TvScreen) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    ScreenColumn("TV Player", "Play direct links on this TV. Navigate with your remote.") {
        Text("START PLAYING", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = sky)
        if (hasMedia) TvAction("Resume current player", { onScreen(TvScreen.PLAYER) }, Modifier.width(390.dp).focusRequester(first))
        TvAction("Enter video URL on TV", { onScreen(TvScreen.URL_ENTRY) }, Modifier.width(390.dp).then(if (!hasMedia) Modifier.focusRequester(first) else Modifier))
        TvAction("Send link from phone / laptop", { onScreen(TvScreen.HANDOFF) }, Modifier.width(390.dp))
        TvAction("Recent videos", { onScreen(TvScreen.HISTORY) }, Modifier.width(390.dp))
        TvAction("Settings and privacy", { onScreen(TvScreen.SETTINGS) }, Modifier.width(390.dp))
        Spacer(Modifier.height(12.dp))
        Text("Supports HTTP(S) video links that Media3 can decode. No media is sent through your phone.", color = muted, fontSize = 15.sp)
    }
}

@Composable
private fun TvInput(label: String, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Text(label, color = foreground, fontSize = 19.sp, fontWeight = FontWeight.Medium)
    Box(modifier
        .onFocusChanged { focused = it.isFocused }
        .background(Color(0xFF0D2133), RoundedCornerShape(10.dp))
        .border(if (focused) 3.dp else 1.dp, if (focused) sky else Color(0xFF4D6B82), RoundedCornerShape(10.dp))
        .padding(15.dp)
    ) {
        if (value.isEmpty()) Text(hint, color = Color(0xFF738A9B), fontSize = 17.sp)
        BasicTextField(value, onChange, singleLine = true,
            textStyle = TextStyle(fontSize = 18.sp, color = foreground),
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EntryScreen(notification: String?, recentNeedsUrl: Boolean,
                        onSubmit: (VideoEntry) -> Unit, onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var subs by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    ScreenColumn("Play a link", "Enter a direct playable HTTP or HTTPS video URL.") {
        if (notification != null) Text(notification, color = Color(0xFFFFBC85), fontSize = 17.sp)
        if (recentNeedsUrl) Text("A replacement signed URL can use your last position if the source matches.", color = muted)
        TvInput("Video URL", url, { url = it }, "https://example.com/movie.mp4", Modifier.widthIn(max = 940.dp).fillMaxWidth().focusRequester(first))
        Spacer(Modifier.height(10.dp))
        TvInput("External subtitles (optional: .srt / .vtt)", subs, { subs = it }, "https://example.com/subtitles.vtt", Modifier.widthIn(max = 940.dp).fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction("Play on TV", { onSubmit(VideoEntry(url.trim(), subs.trim().ifBlank { null })) }, Modifier.width(220.dp))
            TvAction("Back", onBack, Modifier.width(160.dp), secondary = true)
        }
    }
}

@Composable
private fun HandoffScreen(handoff: LanHandoffServer.Info?, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    ScreenColumn("Send a link from your phone", "Keep TV Player open and connect both devices to the same home network.") {
        if (handoff == null) {
            Text("No reachable private IPv4 network detected, or port 8765 is unavailable.", color = Color(0xFFFFBC85), fontSize = 20.sp)
            Text("Connect this TV to your Wi-Fi/LAN, then return to this screen.", color = muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1 · Open in your phone browser", color = muted, fontSize = 18.sp)
                    Text(handoff.address, fontSize = 27.sp, color = sky, fontWeight = FontWeight.Bold)
                    Text("2 · Enter this pairing code", color = muted, fontSize = 18.sp)
                    Text(handoff.pin, fontSize = 45.sp, letterSpacing = 5.sp, color = foreground, fontWeight = FontWeight.Bold)
                    Text("3 · Paste video and optional subtitle URLs, then tap Play on TV.", color = muted, fontSize = 18.sp)
                }
                val bitmap = remember(handoff.address) { makeQr(handoff.address) }
                bitmap?.let { Image(it.asImageBitmap(), "QR code for local TV link-entry page", Modifier.size(190.dp).background(Color.White).padding(8.dp), contentScale = ContentScale.Fit) }
            }
            Text("The pairing code changes each time the app starts. Only the TV downloads and plays media.", color = muted, fontSize = 15.sp)
        }
        TvAction("Back to home", onBack, Modifier.width(250.dp).focusRequester(first))
    }
}

private fun makeQr(value: String): Bitmap? = try {
    val bits: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 256, 256)
    Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 256) for (x in 0 until 256)
            setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
} catch (_: Exception) { null }

@Composable
private fun HistoryScreen(items: List<RecentItem>, onRecent: (RecentItem) -> Unit,
                          onClear: () -> Unit, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    ScreenColumn("Recent videos", "Positions are local. By default full URLs are not saved.") {
        if (items.isEmpty()) Text("No recent videos yet.", color = muted)
        items.forEachIndexed { index, item ->
            TvAction("${item.label}  ·  ${timestamp(item.positionMs)}${if (item.savedVideoUrl == null) "  ·  new URL needed" else ""}",
                { onRecent(item) }, Modifier.fillMaxWidth(0.9f).then(if (index == 0) Modifier.focusRequester(first) else Modifier))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAction("Clear history", onClear, Modifier.width(210.dp).then(if (items.isEmpty()) Modifier.focusRequester(first) else Modifier), secondary = true)
            TvAction("Back", onBack, Modifier.width(150.dp), secondary = true)
        }
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, onSettings: (AppSettings) -> Unit, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    ScreenColumn("Settings & privacy", "Stored only on this TV.") {
        TvAction("Save complete video/subtitle URLs: ${if (settings.saveFullUrls) "ON" else "OFF (private default)"}",
            { onSettings(settings.copy(saveFullUrls = !settings.saveFullUrls)) }, Modifier.width(650.dp).focusRequester(first))
        Text("When OFF, recent items store a title, fingerprint and playback position only. Turning OFF strips previously saved URLs.", color = muted, fontSize = 16.sp)
        TvAction("Default display: ${settings.displayMode}", {
            val next = when (settings.displayMode) { "Fit" -> "Fill"; "Fill" -> "Zoom"; else -> "Fit" }
            onSettings(settings.copy(displayMode = next))
        }, Modifier.width(650.dp))
        TvAction("Reset subtitle appearance and timing", { onSettings(settings.copy(
            subtitleSize = 1f, subtitleColor = 0xffffffff.toInt(), subtitleBackground = true, subtitleOffsetMs = 0)) }, Modifier.width(650.dp))
        TvAction("Back", onBack, Modifier.width(180.dp), secondary = true)
    }
}

@Composable
private fun ResumeScreen(hasPending: Boolean, choose: (Boolean) -> Unit, back: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    ScreenColumn("Resume playback?", "This link appears to have the same host and path as a recent video. Verify before resuming.") {
        if (hasPending) {
            TvAction("Resume from saved position", { choose(true) }, Modifier.width(385.dp).focusRequester(first))
            TvAction("Start from beginning", { choose(false) }, Modifier.width(385.dp))
        }
        TvAction("Cancel", back, Modifier.width(200.dp).then(if (!hasPending) Modifier.focusRequester(first) else Modifier), secondary = true)
    }
}

private fun timestamp(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun PlayerScreen(engine: PlaybackEngine, notification: String?, onScreen: (TvScreen) -> Unit, dismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    var time by remember { mutableLongStateOf(0) }
    LaunchedEffect(engine.hasMedia) {
        while (true) { time = engine.player.currentPosition; delay(500) }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(
            Brush.verticalGradient(listOf(Color(0xD8000000), Color.Transparent))).padding(24.dp)) {
            Text(engine.currentTitle, color = foreground, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE03101A))))
            .padding(horizontal = 44.dp, vertical = 25.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (engine.isLoading) Text("Buffering…", color = sky, fontSize = 17.sp)
            if (engine.error != null) Text(engine.error.orEmpty(), color = Color(0xFFFFBB8E), fontSize = 19.sp)
            if (notification != null) Text(notification, color = Color(0xFFFFBB8E), fontSize = 16.sp)
            Text("${timestamp(time)}  /  ${if (engine.player.duration > 0 && engine.player.duration != C.TIME_UNSET) timestamp(engine.player.duration) else "Live / unknown"}",
                color = foreground, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TvAction(if (engine.isPlaying) "Pause" else "Play", { engine.toggle() }, Modifier.width(116.dp).focusRequester(first))
                TvAction("−10 sec", { engine.skip(-10_000) }, Modifier.width(132.dp))
                TvAction("+10 sec", { engine.skip(10_000) }, Modifier.width(132.dp))
                TvAction("Menus", { onScreen(TvScreen.CONTROLS) }, Modifier.width(125.dp))
                TvAction("Home", { onScreen(TvScreen.HOME) }, Modifier.width(125.dp), secondary = true)
                if (engine.error != null) TvAction("Retry", { engine.retry() }, Modifier.width(120.dp))
                if (notification != null) TvAction("Dismiss", dismiss, Modifier.width(150.dp), secondary = true)
            }
        }
    }
}

@Composable
private fun PlaybackMenu(engine: PlaybackEngine, onScreen: (TvScreen) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color(0xBB03101A))) {
        ScreenColumn("Playback menus", "Remote-accessible player options") {
            TvAction("Subtitles", { onScreen(TvScreen.SUBTITLES) }, Modifier.width(400.dp).focusRequester(first))
            TvAction("Display fit / fill / zoom", { onScreen(TvScreen.DISPLAY) }, Modifier.width(400.dp))
            TvAction("Audio and available quality tracks", { onScreen(TvScreen.TRACKS) }, Modifier.width(400.dp))
            Text("Playback speed", color = muted, fontSize = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    TvAction("${speed}×", { engine.setSpeed(speed) }, Modifier.width(115.dp))
                }
            }
            TvAction("Return to video", { onScreen(TvScreen.PLAYER) }, Modifier.width(270.dp), secondary = true)
        }
    }
}

@Composable
private fun SubtitleMenu(engine: PlaybackEngine, settings: AppSettings,
                         onSettings: (AppSettings) -> Unit, reload: () -> Unit, onScreen: (TvScreen) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color(0xCB03101A))) {
        ScreenColumn("Subtitle settings", "Timing changes reload the current subtitle from the URL.") {
            TvAction("Subtitles ${if (engine.subtitleEnabled()) "ON" else "OFF"}",
                { engine.setSubtitleEnabled(!engine.subtitleEnabled()) }, Modifier.width(430.dp).focusRequester(first))
            Text("Size", color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TvAction("Smaller", { onSettings(settings.copy(subtitleSize = (settings.subtitleSize - 0.2f).coerceAtLeast(0.5f))) }, Modifier.width(165.dp))
                Text("${(settings.subtitleSize * 100).toInt()}%", Modifier.align(Alignment.CenterVertically), fontSize = 20.sp, color = foreground)
                TvAction("Larger", { onSettings(settings.copy(subtitleSize = (settings.subtitleSize + 0.2f).coerceAtMost(1.8f))) }, Modifier.width(165.dp))
            }
            TvAction("Text color: ${if (settings.subtitleColor == 0xffffffff.toInt()) "White" else "Yellow"}",
                { onSettings(settings.copy(subtitleColor = if (settings.subtitleColor == 0xffffffff.toInt()) 0xffffff00.toInt() else 0xffffffff.toInt())) }, Modifier.width(430.dp))
            TvAction("Dark subtitle background: ${if (settings.subtitleBackground) "ON" else "OFF"}",
                { onSettings(settings.copy(subtitleBackground = !settings.subtitleBackground)) }, Modifier.width(430.dp))
            Text("Timing offset: ${settings.subtitleOffsetMs} ms", color = muted, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvAction("−500 ms", { onSettings(settings.copy(subtitleOffsetMs = (settings.subtitleOffsetMs - 500).coerceAtLeast(-60_000))) }, Modifier.width(155.dp))
                TvAction("+500 ms", { onSettings(settings.copy(subtitleOffsetMs = (settings.subtitleOffsetMs + 500).coerceAtMost(60_000))) }, Modifier.width(155.dp))
                TvAction("Apply timing", reload, Modifier.width(210.dp))
            }
            TvAction("Reset subtitles", { onSettings(settings.copy(subtitleSize = 1f, subtitleColor = 0xffffffff.toInt(), subtitleBackground = true, subtitleOffsetMs = 0)); reload() }, Modifier.width(300.dp))
            TvAction("Back to video", { onScreen(TvScreen.PLAYER) }, Modifier.width(255.dp), secondary = true)
        }
    }
}

@Composable
private fun DisplayMenu(engine: PlaybackEngine, settings: AppSettings,
                        onSettings: (AppSettings) -> Unit, onScreen: (TvScreen) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color(0xC303101A))) {
        ScreenColumn("Picture display", "Video dimensions: ${engine.videoDimensions()} (not an offered quality variant).") {
            listOf("Fit", "Fill", "Zoom").forEachIndexed { i, mode ->
                val description = when (mode) { "Fit" -> "entire frame"; "Fill" -> "stretch to display"; else -> "crop edges to fill" }
                TvAction("${if (settings.displayMode == mode) "✓ " else ""}$mode · $description",
                    { onSettings(settings.copy(displayMode = mode)) }, Modifier.width(445.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier))
            }
            TvAction("Back to video", { onScreen(TvScreen.PLAYER) }, Modifier.width(265.dp), secondary = true)
        }
    }
}

@Composable
private fun TracksMenu(engine: PlaybackEngine, onScreen: (TvScreen) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val revision = engine.trackRevision
    val audio = remember(revision) { engine.choices(C.TRACK_TYPE_AUDIO) }
    val quality = remember(revision) { engine.choices(C.TRACK_TYPE_VIDEO) }
    Box(Modifier.fillMaxSize().background(Color(0xCC03101A))) {
        ScreenColumn("Media tracks", "Only tracks supplied by this stream are shown.") {
            Text("Audio", color = sky, fontSize = 23.sp)
            if (audio.size <= 1) Text("No alternative audio tracks available.", color = muted)
            else {
                TvAction("Audio: automatic", { engine.chooseTrack(C.TRACK_TYPE_AUDIO, null) }, Modifier.width(450.dp).focusRequester(first))
                audio.forEach { track ->
                    TvAction(track.label, { engine.chooseTrack(C.TRACK_TYPE_AUDIO, track) }, Modifier.width(450.dp))
                }
            }
            Text("Available video variants", color = sky, fontSize = 23.sp)
            if (quality.size <= 1) Text("No alternative video variants available.", color = muted)
            else {
                TvAction("Video: automatic (adaptive)", { engine.chooseTrack(C.TRACK_TYPE_VIDEO, null) }, Modifier.width(460.dp))
                quality.forEach { track -> TvAction(track.label, { engine.chooseTrack(C.TRACK_TYPE_VIDEO, track) }, Modifier.width(460.dp)) }
            }
            Text("Decoded video dimensions: ${engine.videoDimensions()}", color = muted)
            TvAction("Back to video", { onScreen(TvScreen.PLAYER) }, Modifier.width(255.dp)
                .then(if (audio.size <= 1) Modifier.focusRequester(first) else Modifier), secondary = true)
        }
    }
}
