package dev.lmadhuranga.tvplayer

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.view.Window
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
        val usbVideos = UsbVideoRepository(applicationContext)
        playback = PlaybackController(applicationContext, history)
        setContent {
            MaterialTheme {
                TVPlayerApp(playback, history, playerSettings, usbVideos, window)
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

private enum class AppRoute { HOME, ADD_URL, HANDOFF, USB_BROWSER, USB_SUBTITLES, PLAYER }

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun TVPlayerApp(
    playback: PlaybackController,
    historyRepository: HistoryRepository,
    playerSettingsRepository: PlayerSettingsRepository,
    usbVideoRepository: UsbVideoRepository,
    window: Window,
) {
    var route by remember { mutableStateOf(AppRoute.HANDOFF) }
    var playerReturnRoute by remember { mutableStateOf(AppRoute.HANDOFF) }
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
    val context = LocalContext.current
    var usbRoots by remember { mutableStateOf<List<UsbRoot>>(emptyList()) }
    var usbRoot by remember { mutableStateOf<UsbRoot?>(null) }
    var usbDirectory by remember { mutableStateOf<java.io.File?>(null) }
    var usbEntries by remember { mutableStateOf<List<UsbEntry>>(emptyList()) }
    var usbIndexedVideos by remember { mutableStateOf<List<UsbIndexedVideo>>(emptyList()) }
    var usbSelection by remember { mutableStateOf<UsbVideoSelection?>(null) }
    var usbReturnFocusPath by remember { mutableStateOf<String?>(null) }
    var usbLoading by remember { mutableStateOf(false) }
    var usbError by remember { mutableStateOf<String?>(null) }
    fun loadUsbRoots() {
        route = AppRoute.USB_BROWSER
        usbLoading = true
        usbError = null
        appScope.launch {
            runCatching { usbVideoRepository.listRoots() }
                .onSuccess { roots ->
                    usbRoots = roots
                    usbRoot = null
                    usbDirectory = null
                    usbEntries = emptyList()
                    usbIndexedVideos = emptyList()
                    usbError = if (roots.isEmpty()) "No mounted USB drive is available. Connect a drive and try again." else null
                }
                .onFailure { error ->
                    usbError = error.message?.takeIf(String::isNotBlank) ?: "Could not read USB storage."
                }
            usbLoading = false
        }
    }
    fun loadIndexedUsbVideos() {
        route = AppRoute.USB_BROWSER
        usbLoading = true
        usbError = null
        appScope.launch {
            runCatching { usbVideoRepository.listIndexedVideos() }
                .onSuccess { videos ->
                    usbIndexedVideos = videos
                    usbError = if (videos.isEmpty()) "No indexed USB videos were found. Folder and subtitle browsing needs full storage access." else "Showing indexed videos. Enable full storage access to browse folders and subtitles."
                }
                .onFailure { error -> usbError = error.message?.takeIf(String::isNotBlank) ?: "Could not read indexed USB videos." }
            usbLoading = false
        }
    }
    fun openUsbDirectory(root: UsbRoot, directory: java.io.File) {
        usbLoading = true
        usbError = null
        appScope.launch {
            runCatching { usbVideoRepository.listEntries(root, directory) }
                .onSuccess { entries ->
                    usbRoot = root
                    usbDirectory = directory
                    usbEntries = entries
                    usbError = null
                }
                .onFailure { error ->
                    usbError = error.message?.takeIf(String::isNotBlank) ?: "Could not read this USB folder."
                }
            usbLoading = false
        }
    }
    val usbPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    val usbPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) loadIndexedUsbVideos() else loadUsbRoots()
        } else {
            route = AppRoute.USB_BROWSER
            usbError = "Video access was not allowed. Folder and subtitle browsing needs full storage access."
        }
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) loadUsbRoots() else {
            route = AppRoute.USB_BROWSER
            usbError = "Storage access was not allowed. File management needs read and write access on Android 8 and 9."
        }
    }
    val allFilesSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) loadUsbRoots()
        else {
            route = AppRoute.USB_BROWSER
            usbError = "USB folder browsing needs the storage access setting. Enable it, then try again."
        }
    }
    fun browseUsb() {
        usbReturnFocusPath = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) loadUsbRoots() else {
                route = AppRoute.USB_BROWSER
                usbError = "Allow TV Player to manage files so it can browse USB folders and subtitle files."
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            route = AppRoute.USB_BROWSER
            usbError = "Folder browsing on Android 10 is not available on this TV."
        } else if (context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            loadUsbRoots()
        } else {
            route = AppRoute.USB_BROWSER
            legacyStoragePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
    fun requestUsbStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            runCatching { allFilesSettingsLauncher.launch(intent) }
                .onFailure {
                    allFilesSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
        } else browseUsb()
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) playback.loadSubtitleFile(uri)
    }
    var pendingStart by remember { mutableStateOf<PendingPlayback?>(null) }

    LaunchedEffect(maximumBufferMinutes) {
        playback.setMaximumBufferMinutes(maximumBufferMinutes)
    }

    fun startVideo(url: String, subtitleUrl: String?) {
        playerReturnRoute = AppRoute.HANDOFF
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
            route = when (route) {
                AppRoute.USB_SUBTITLES -> AppRoute.USB_BROWSER
                AppRoute.USB_BROWSER -> {
                    val currentRoot = usbRoot
                    val current = usbDirectory
                    if (currentRoot != null && current != null && current.canonicalFile != currentRoot.directory.canonicalFile) {
                        openUsbDirectory(currentRoot, current.parentFile ?: currentRoot.directory)
                        AppRoute.USB_BROWSER
                    } else AppRoute.HOME
                }
                else -> AppRoute.HOME
            }
        }
    }

    when (route) {
        AppRoute.HOME -> HomeScreen(
            entries = recentEntries,
            settingsRequester = settingsOpenerRequester,
            onBrowseUsb = ::browseUsb,
            onSendToTv = { route = AppRoute.HANDOFF },
            onOpenSettings = { settingsOpen = true },
            onReplay = { entry ->
                val url = entry.mediaUrl ?: return@HomeScreen
                playerReturnRoute = AppRoute.HANDOFF
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

        AppRoute.USB_BROWSER -> UsbVideoBrowserScreen(
            roots = usbRoots,
            root = usbRoot,
            directory = usbDirectory,
            entries = usbEntries,
            needsStorageAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager(),
            indexedVideos = usbIndexedVideos,
            loading = usbLoading,
            error = usbError,
            onChooseRoot = { root ->
                usbReturnFocusPath = null
                openUsbDirectory(root, root.directory)
            },
            onOpenFolder = { entry ->
                usbReturnFocusPath = null
                usbRoot?.let { root -> openUsbDirectory(root, entry.file) }
            },
            onChooseVideo = { entry ->
                usbReturnFocusPath = entry.file.path
                usbLoading = true
                appScope.launch {
                    runCatching { usbVideoRepository.selection(entry) }
                        .onSuccess { usbSelection = it; usbLoading = false; route = AppRoute.USB_SUBTITLES }
                        .onFailure { usbLoading = false; usbError = it.message ?: "Could not read the selected USB video." }
                }
            },
            onParent = {
                usbReturnFocusPath = null
                val currentRoot = usbRoot
                val current = usbDirectory
                if (currentRoot == null || current == null || current.canonicalFile == currentRoot.directory.canonicalFile) loadUsbRoots()
                else openUsbDirectory(currentRoot, current.parentFile ?: currentRoot.directory)
            },
            onRequestAccess = ::requestUsbStorageAccess,
            onIndexedVideo = { video ->
                playerReturnRoute = AppRoute.USB_BROWSER
                playback.playDocument(video.uri)
                route = AppRoute.PLAYER
            },
            onShowIndexedVideos = {
                if (context.checkSelfPermission(usbPermission) != PackageManager.PERMISSION_GRANTED) {
                    usbPermissionLauncher.launch(usbPermission)
                } else loadIndexedUsbVideos()
            },
            onRetry = ::browseUsb,
            returnFocusPath = usbReturnFocusPath,
        )

        AppRoute.USB_SUBTITLES -> UsbSubtitleChoiceScreen(
            selection = usbSelection,
            loading = usbLoading,
            onPlay = { subtitle ->
                val selection = usbSelection ?: return@UsbSubtitleChoiceScreen
                playerReturnRoute = AppRoute.USB_BROWSER
                playback.playLocalFile(selection.video.file, subtitle?.file)
                route = AppRoute.PLAYER
            },
        )

        AppRoute.HANDOFF -> HandoffScreen(
            cacheDirectory = playback.cacheDirectory,
            hasActiveMedia = playbackState.hasMedia,
            recentEntries = recentEntries,
            settingsRequester = settingsOpenerRequester,
            onVideoSubmitted = { submission ->
                pendingStart = null
                playerReturnRoute = AppRoute.HANDOFF
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
                playerReturnRoute = AppRoute.HANDOFF
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
                route = playerReturnRoute
                if (playerReturnRoute == AppRoute.USB_BROWSER) {
                    val currentRoot = usbRoot
                    val currentDirectory = usbDirectory
                    if (currentRoot != null && currentDirectory != null) {
                        usbLoading = true
                        usbError = null
                        appScope.launch {
                            runCatching { usbVideoRepository.listRoots() }
                                .onSuccess { roots ->
                                    usbRoots = roots
                                    val mountedRoot = roots.firstOrNull { it.directory.canonicalFile == currentRoot.directory.canonicalFile }
                                    if (mountedRoot == null) {
                                        usbRoot = null
                                        usbDirectory = null
                                        usbEntries = emptyList()
                                        usbError = "The USB drive is no longer available. Reconnect it and scan again."
                                    } else {
                                        openUsbDirectory(mountedRoot, currentDirectory)
                                    }
                                }
                                .onFailure { error ->
                                    usbError = error.message?.takeIf(String::isNotBlank) ?: "Could not reconnect to the USB folder."
                                }
                            usbLoading = false
                        }
                    } else {
                        appScope.launch {
                            runCatching { usbVideoRepository.listRoots() }
                                .onSuccess { roots ->
                                    usbRoots = roots
                                    if (roots.isEmpty() && usbIndexedVideos.isNotEmpty()) {
                                        usbIndexedVideos = emptyList()
                                        usbError = "The USB drive is no longer available. Reconnect it and scan again."
                                    }
                                }
                                .onFailure { error ->
                                    usbError = error.message?.takeIf(String::isNotBlank) ?: "Could not reconnect to USB storage."
                                }
                        }
                    }
                }
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
                playerReturnRoute = AppRoute.HANDOFF
                pendingStart = null
                route = AppRoute.PLAYER
            },
            onStartOver = {
                playback.playUrl(pending.url, pending.subtitleUrl)
                playerReturnRoute = AppRoute.HANDOFF
                pendingStart = null
                route = AppRoute.PLAYER
            },
            onCancel = { pendingStart = null },
        )
    }
}

private data class PendingPlayback(val url: String, val subtitleUrl: String?, val entries: List<HistoryEntry>)

@Composable
private fun UsbVideoBrowserScreen(
    roots: List<UsbRoot>,
    root: UsbRoot?,
    directory: java.io.File?,
    entries: List<UsbEntry>,
    indexedVideos: List<UsbIndexedVideo>,
    loading: Boolean,
    error: String?,
    needsStorageAccess: Boolean,
    onChooseRoot: (UsbRoot) -> Unit,
    onOpenFolder: (UsbEntry) -> Unit,
    onChooseVideo: (UsbEntry) -> Unit,
    onParent: () -> Unit,
    onRequestAccess: () -> Unit,
    onShowIndexedVideos: () -> Unit,
    onIndexedVideo: (UsbIndexedVideo) -> Unit,
    onRetry: () -> Unit,
    returnFocusPath: String?,
) {
    val firstEntryRequester = remember { FocusRequester() }
    val emptyStateRequester = remember { FocusRequester() }
    val firstActionableEntry = entries.firstOrNull { it.isDirectory || it.canPlay }
    val focusTargetPath = returnFocusPath?.takeIf { path -> entries.any { it.file.path == path } }
    val listState = rememberLazyListState()
    var consumedParentKeyUp by remember { mutableStateOf(false) }
    val breadcrumb = if (root != null && directory != null) usbBreadcrumb(root, directory) else "USB drives"
    val folderTitle = when {
        root == null || directory == null -> "USB drives"
        directory.canonicalFile == root.directory.canonicalFile -> "USB drive"
        else -> directory.name
    }
    TVBackground(horizontalPadding = 36.dp, verticalPadding = 16.dp) {
        Column(
            modifier = Modifier.fillMaxSize()
                .focusRequester(emptyStateRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                if (root == null || directory == null || event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) onParent()
                        consumedParentKeyUp = true
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (consumedParentKeyUp) {
                            consumedParentKeyUp = false
                            true
                        } else false
                    }
                    else -> false
                }
            },
        ) {
            Text(
                folderTitle,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = Color(0xFF102239)),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    when {
                        loading -> item { Text("Scanning mounted USB drives…", color = Muted, fontSize = 20.sp) }
                        error != null -> {
                            item { Text(error, color = Muted, fontSize = 20.sp) }
                            if (needsStorageAccess) item {
                                AppButton(onClick = onRequestAccess, modifier = Modifier.focusRequester(firstEntryRequester)) { Text("Allow USB folder access") }
                            }
                            if (needsStorageAccess || indexedVideos.isEmpty()) item {
                                AppButton(onClick = onShowIndexedVideos, modifier = if (!needsStorageAccess && indexedVideos.isEmpty()) Modifier.focusRequester(firstEntryRequester) else Modifier) { Text("Show indexed videos") }
                            }
                            item {
                                AppButton(onClick = onRetry, modifier = if (!needsStorageAccess && !indexedVideos.isEmpty()) Modifier.focusRequester(firstEntryRequester) else Modifier) { Text("Scan USB drives") }
                            }
                            items(indexedVideos, key = { it.uri.toString() }) { video ->
                                UsbExplorerRow(video.name, video.sizeBytes?.let { "${video.folder} · ${formatFileSize(it)}" } ?: video.folder, ExplorerIcon.VIDEO, onClick = { onIndexedVideo(video) })
                            }
                        }
                        root == null -> {
                            items(roots, key = { it.directory.path }) { usbRoot ->
                                UsbExplorerRow(usbRoot.name, "${usbRoot.directory.path} · ${folderCountsLabel(usbRoot.folderCount, usbRoot.fileCount)}", ExplorerIcon.DRIVE, onClick = { onChooseRoot(usbRoot) }, modifier = if (usbRoot == roots.firstOrNull()) Modifier.focusRequester(firstEntryRequester) else Modifier)
                            }
                        }
                        else -> {
                            if (entries.isEmpty()) item { Text("This folder is empty.", color = Muted, fontSize = 18.sp, modifier = Modifier.padding(12.dp)) }
                            items(entries, key = { it.file.path }) { entry ->
                                val description = if (entry.isDirectory) folderCountsLabel(entry.folderCount, entry.fileCount)
                                    else entry.sizeBytes?.let { "${entry.typeLabel} · ${formatFileSize(it)}" } ?: entry.typeLabel
                                UsbExplorerRow(
                                    name = entry.name,
                                    metadata = description,
                                    icon = when { entry.isDirectory -> ExplorerIcon.FOLDER; entry.canPlay -> ExplorerIcon.VIDEO; else -> ExplorerIcon.FILE },
                                    enabled = entry.isDirectory || entry.canPlay,
                                    trailingChevron = entry.isDirectory,
                                    onClick = { if (entry.isDirectory) onOpenFolder(entry) else if (entry.canPlay) onChooseVideo(entry) },
                                    modifier = if (entry.file.path == focusTargetPath || focusTargetPath == null && entry == firstActionableEntry) Modifier.focusRequester(firstEntryRequester) else Modifier,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                breadcrumb,
                color = Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                    .semantics { contentDescription = breadcrumb },
            )
        }
    }
    LaunchedEffect(loading, error, roots.size, entries.size, root, directory, focusTargetPath) {
        if (!loading && (root != null || roots.isNotEmpty() || error != null)) {
            val returnedEntryIndex = focusTargetPath?.let { path -> entries.indexOfFirst { it.file.path == path } } ?: -1
            val focusIndex = when {
                error != null -> 1
                root == null -> 0
                returnedEntryIndex >= 0 -> returnedEntryIndex
                firstActionableEntry != null -> entries.indexOf(firstActionableEntry)
                else -> 0
            }
            listState.scrollToItem(focusIndex)
            withFrameNanos { }
            val hasBrowserAction = error != null || root == null && roots.isNotEmpty() || firstActionableEntry != null
            if (hasBrowserAction) firstEntryRequester.requestFocus()
            else emptyStateRequester.requestFocus()
        }
    }
}

private fun usbBreadcrumb(root: UsbRoot, directory: java.io.File): String {
    val relativePath = runCatching { directory.canonicalFile.relativeTo(root.directory.canonicalFile).path }
        .getOrNull()
        ?.takeUnless { it == ".." || it.startsWith("..${java.io.File.separator}") }
        .orEmpty()
    val folders = relativePath.split(java.io.File.separatorChar).filter { it.isNotBlank() && it != "." }
    return (listOf("USB drive") + folders).joinToString(" > ")
}

private enum class ExplorerIcon { DRIVE, FOLDER, VIDEO, FILE }

@Composable
private fun UsbExplorerRow(
    name: String,
    metadata: String,
    icon: ExplorerIcon,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingChevron: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val rowShape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (enabled) Modifier.clickable(onClick = onClick).focusable() else Modifier)
            .background(if (focused) Color(0xFF26445E) else Color.Transparent, rowShape)
            .border(1.5.dp, if (focused) Color(0xFF6FAED0) else Color.Transparent, rowShape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExplorerGlyph(icon, enabled)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(name, color = if (enabled) Color.White else Color(0xFFAAB8C6), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(metadata, color = if (enabled) Muted else Color(0xFF929EAC), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailingChevron) Text("›", color = Muted, fontSize = 26.sp)
        }
    }
}

@Composable
private fun ExplorerGlyph(icon: ExplorerIcon, enabled: Boolean) {
    val tint = if (enabled) Color(0xFF8EB7D8) else Color(0xFF687789)
    Canvas(Modifier.size(26.dp)) {
        val sx = size.width / 26f
        val sy = size.height / 26f
        when (icon) {
            ExplorerIcon.FOLDER, ExplorerIcon.DRIVE -> {
                val folder = Path().apply {
                    moveTo(2f * sx, 6f * sy); lineTo(10f * sx, 6f * sy); lineTo(12f * sx, 9f * sy)
                    lineTo(24f * sx, 9f * sy); lineTo(24f * sx, 21f * sy); lineTo(2f * sx, 21f * sy); close()
                }
                drawPath(folder, tint)
                if (icon == ExplorerIcon.DRIVE) drawRect(Color(0xFF102239), androidx.compose.ui.geometry.Offset(4f * sx, 17f * sy), androidx.compose.ui.geometry.Size(18f * sx, 2f * sy))
            }
            ExplorerIcon.VIDEO -> {
                drawRoundRect(tint, androidx.compose.ui.geometry.Offset(2f * sx, 5f * sy), androidx.compose.ui.geometry.Size(22f * sx, 16f * sy), androidx.compose.ui.geometry.CornerRadius(3f * sx))
                val play = Path().apply { moveTo(10f * sx, 9f * sy); lineTo(17f * sx, 13f * sy); lineTo(10f * sx, 17f * sy); close() }
                drawPath(play, Color(0xFF102239))
            }
            ExplorerIcon.FILE -> {
                val page = Path().apply { moveTo(6f * sx, 2f * sy); lineTo(16f * sx, 2f * sy); lineTo(21f * sx, 7f * sy); lineTo(21f * sx, 24f * sy); lineTo(6f * sx, 24f * sy); close() }
                drawPath(page, tint)
                drawLine(Color(0xFF102239), androidx.compose.ui.geometry.Offset(9f * sx, 13f * sy), androidx.compose.ui.geometry.Offset(18f * sx, 13f * sy), 1.5f * sx)
                drawLine(Color(0xFF102239), androidx.compose.ui.geometry.Offset(9f * sx, 17f * sy), androidx.compose.ui.geometry.Offset(18f * sx, 17f * sy), 1.5f * sx)
            }
        }
    }
}

private fun folderCountsLabel(folderCount: Int?, fileCount: Int?): String {
    if (folderCount == null || fileCount == null) return "Contents unavailable"
    val folders = if (folderCount == 1) "folder" else "folders"
    val files = if (fileCount == 1) "file" else "files"
    return "$fileCount $files · $folderCount $folders"
}

@Composable
private fun UsbSubtitleChoiceScreen(
    selection: UsbVideoSelection?,
    loading: Boolean,
    onPlay: (UsbEntry?) -> Unit,
) {
    var selected by remember(selection) { mutableStateOf(selection?.automaticSubtitle) }
    val playRequester = remember { FocusRequester() }
    TVBackground {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("READY TO PLAY", color = Teal, fontSize = 16.sp, letterSpacing = 2.sp)
            Text(selection?.video?.name ?: "Reading USB video…", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp), colors = SurfaceDefaults.colors(containerColor = Color(0xFF102239))) {
                Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SUBTITLE IN THIS FOLDER", color = Teal, fontSize = 14.sp, letterSpacing = 1.sp)
                    when {
                        loading -> Text("Checking the video folder…", color = Muted, fontSize = 18.sp)
                        selection?.subtitleStatus == SubtitleMatchStatus.AMBIGUOUS -> Text("More than one subtitle matches. Choose one to continue.", color = Muted, fontSize = 17.sp)
                        selection?.automaticSubtitle != null -> Text("A subtitle was selected automatically.", color = Muted, fontSize = 17.sp)
                        else -> Text("No subtitle was selected automatically.", color = Muted, fontSize = 17.sp)
                    }
                    selection?.subtitles.orEmpty().forEach { subtitle ->
                        AppButton(
                            onClick = { selected = subtitle },
                        ) {
                            Text((if (selected?.file == subtitle.file) "✓  " else "") + subtitle.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    AppButton(
                        onClick = { onPlay(selected) },
                        modifier = Modifier.focusRequester(playRequester),
                    ) { Text("Play video", fontSize = 18.sp) }
                }
            }
        }
    }
    LaunchedEffect(selection, loading) { if (!loading) playRequester.requestFocus() }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1_000L) return "$bytes B"
    if (bytes < 1_000_000L) return String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    val gigabytes = bytes / 1_000_000_000.0
    return if (gigabytes >= 1) String.format(Locale.US, "%.1f GB", gigabytes)
    else String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
}

@Composable
private fun HomeScreen(
    entries: List<HistoryEntry>,
    settingsRequester: FocusRequester,
    onBrowseUsb: () -> Unit,
    onSendToTv: () -> Unit,
    onOpenSettings: () -> Unit,
    onReplay: (HistoryEntry) -> Unit,
) {
    val sendToTvRequester = remember { FocusRequester() }
    // Select Send to TV whenever the Home route is entered from another screen.
    // This runs only when Home is newly composed, so closing Settings can restore
    // focus to the Settings button without this request overriding it.
    LaunchedEffect(Unit) { sendToTvRequester.requestFocus() }

    TVBackground {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeMenuButton(
                        label = "Browse USB videos",
                        icon = R.drawable.ic_home_usb,
                        onClick = onBrowseUsb,
                    )
                    HomeMenuButton(
                        label = "Send to TV",
                        icon = R.drawable.ic_home_send,
                        onClick = onSendToTv,
                        modifier = Modifier.focusRequester(sendToTvRequester),
                    )
                    HomeMenuButton(
                        label = "Settings",
                        icon = R.drawable.ic_home_settings,
                        onClick = onOpenSettings,
                        modifier = Modifier.focusRequester(settingsRequester),
                    )
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
                                    Text(
                                        entry.title.ifBlank { entry.sourceHost.ifBlank { "Video" } },
                                        fontSize = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
private fun HomeMenuButton(
    label: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    AppButton(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    ) {
        Image(
            painter = painterResource(id = icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (focused) Color(0xFF075D99) else Color(0xFF62CEFF)),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 18.sp)
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text("TV PLAYER", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}

@Composable
internal fun TVBackground(
    horizontalPadding: androidx.compose.ui.unit.Dp = 72.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Ink),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF142A46), Ink, Color(0xFF0A1220))))
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        ) {
            content()
        }
    }
}
