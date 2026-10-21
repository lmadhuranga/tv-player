package com.tvplayer.app

import android.os.Bundle
import androidx.media3.session.MediaSession
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: PlaybackEngine
    private lateinit var mediaSession: MediaSession
    private lateinit var subtitleManager: SubtitleManager
    private lateinit var prefs: PlaybackPrefs
    private lateinit var server: LanHandoffServer

    private var screen by mutableStateOf(TvScreen.HOME)
    private var settings by mutableStateOf(AppSettings())
    private var recents by mutableStateOf<List<RecentItem>>(emptyList())
    private var handoffInfo by mutableStateOf<LanHandoffServer.Info?>(null)
    private var notification by mutableStateOf<String?>(null)
    private var pendingResume by mutableStateOf<Pair<VideoEntry, Long>?>(null)
    private var currentEntry: VideoEntry? = null
    private var saveJob: Job? = null
    private var launchJob: Job? = null
    private var openedRecentWithoutUrl by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PlaybackEngine(this)
        mediaSession = MediaSession.Builder(this, engine.player).build()
        subtitleManager = SubtitleManager(this)
        prefs = PlaybackPrefs(this)
        server = LanHandoffServer(this) { entry -> runOnUiThread { submit(entry) } }

        scope.launch { prefs.settings.collectLatest { settings = it } }
        scope.launch { prefs.history.collectLatest { recents = it } }
        setContent {
            TvPlayerApp(
                screen = screen, settings = settings, recents = recents, engine = engine,
                handoff = handoffInfo, notification = notification,
                hasPendingResume = pendingResume != null, recentNeedsUrl = openedRecentWithoutUrl,
                onScreen = { target ->
                    notification = null
                    if (target == TvScreen.HOME && screen == TvScreen.PLAYER) engine.player.pause()
                    if (target == TvScreen.PLAYER && screen == TvScreen.HOME && engine.hasMedia) engine.player.play()
                    screen = target
                },
                onSubmit = ::submit,
                onSettings = ::changeSettings,
                onClearHistory = { scope.launch { prefs.clearHistory() } },
                onRecentItem = ::openRecent,
                onResumeChoice = { resume ->
                    pendingResume?.let { (entry, position) ->
                        pendingResume = null
                        startPlayback(entry, if (resume) position else 0)
                    }
                },
                onReloadSubtitles = {
                    currentEntry?.let { entry -> startPlayback(entry, engine.player.currentPosition) }
                },
                onBack = ::navigateBack,
                onNoticeDismiss = { notification = null }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        server.start()
        handoffInfo = server.info
        if (currentEntry != null && engine.hasMedia) startSaveTicker()
    }

    override fun onStop() {
        launchJob?.cancel()
        saveJob?.cancel()
        savePosition()
        server.stop()
        handoffInfo = null
        engine.player.pause() // No background service, no background playback.
        super.onStop()
    }

    override fun onDestroy() {
        launchJob?.cancel()
        saveJob?.cancel()
        server.stop()
        scope.cancel()
        subtitleManager.clear()
        mediaSession.release()
        engine.release()
        super.onDestroy()
    }

    private fun changeSettings(next: AppSettings) {
        settings = next
        scope.launch { prefs.updateSettings(next) }
    }

    private fun submit(entry: VideoEntry) {
        if (!UrlRules.validHttpUrl(entry.videoUrl) ||
            (entry.subtitleUrl != null && !UrlRules.validHttpUrl(entry.subtitleUrl))) {
            notification = "Enter a valid HTTP or HTTPS URL without embedded credentials."
            screen = TvScreen.URL_ENTRY
            return
        }
        notification = null
        openedRecentWithoutUrl = false
        val matching = recents.firstOrNull { it.id == UrlRules.sourceId(entry.videoUrl) && it.positionMs > 10_000 }
        if (matching != null) {
            pendingResume = entry to matching.positionMs
            screen = TvScreen.RESUME
        } else startPlayback(entry, 0)
    }

    fun openRecent(item: RecentItem) { // kept public for a future deep-link handler
        val url = item.savedVideoUrl
        if (url != null && UrlRules.validHttpUrl(url)) {
            submit(VideoEntry(url, item.savedSubtitleUrl))
        } else {
            openedRecentWithoutUrl = true
            notification = "Paste a fresh URL for this video. Position will be offered if its source matches."
            screen = TvScreen.URL_ENTRY
        }
    }

    private fun startPlayback(entry: VideoEntry, positionMs: Long) {
        launchJob?.cancel()
        saveJob?.cancel()
        savePosition()
        notification = null
        currentEntry = entry
        screen = TvScreen.PLAYER
        launchJob = scope.launch {
            val subtitle = if (entry.subtitleUrl != null) {
                try { subtitleManager.prepare(entry.subtitleUrl, settings.subtitleOffsetMs) }
                catch (_: CancellationException) { throw CancellationException() }
                catch (_: Exception) {
                    notification = "Subtitle unavailable. Check the subtitle link and SRT/WebVTT format. Video will still play."
                    null
                }
            } else null
            engine.play(entry, subtitle, positionMs)
            prefs.record(entry, positionMs, settings.saveFullUrls)
            startSaveTicker()
        }
    }

    private fun startSaveTicker() {
        saveJob?.cancel()
        saveJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (engine.hasMedia) savePosition()
            }
        }
    }

    private fun savePosition() {
        val entry = currentEntry ?: return
        if (!engine.hasMedia) return
        val position = engine.player.currentPosition.coerceAtLeast(0)
        scope.launch { prefs.record(entry, position, settings.saveFullUrls) }
    }

    private fun navigateBack() {
        notification = null
        screen = when (screen) {
            TvScreen.HOME -> if (engine.hasMedia) TvScreen.PLAYER else TvScreen.HOME
            TvScreen.PLAYER -> { engine.player.pause(); TvScreen.HOME }
            TvScreen.RESUME -> { pendingResume = null; TvScreen.URL_ENTRY }
            TvScreen.CONTROLS, TvScreen.SUBTITLES, TvScreen.DISPLAY, TvScreen.TRACKS -> TvScreen.PLAYER
            else -> TvScreen.HOME
        }
    }

}
