package dev.lmadhuranga.tvplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.media3.ui.AspectRatioFrameLayout
import kotlin.math.abs

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
data class PlaybackUiState(
    val title: String? = null,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioChoices: List<MediaTrackChoice> = emptyList(),
    val videoVariantChoices: List<MediaTrackChoice> = emptyList(),
    val selectedVideoVariantKey: String? = null,
    val speed: Float = 1f,
    val error: String? = null,
    val canRetry: Boolean = false,
    val subtitlesAvailable: Boolean = false,
    val hasExternalSubtitles: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    val subtitleOffsetMs: Long = 0L,
    val subtitleError: String? = null,
    val subtitleLoading: Boolean = false,
    val displayMode: VideoDisplayMode = VideoDisplayMode.FIT,
    val subtitleSizeIndex: Int = 1,
    val subtitleColorIndex: Int = 0,
    val subtitleBackgroundEnabled: Boolean = true,
)

data class MediaTrackChoice(
    val key: String,
    val type: Int,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
enum class VideoDisplayMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackController(context: Context, private val historyRepository: HistoryRepository) : Player.Listener {
    private val appContext = context.applicationContext
    val cacheDirectory: java.io.File get() = appContext.cacheDir
    private var selectedBufferMinutes = PlayerSettingsRepository.DEFAULT_BUFFER_MINUTES
    private var activeBufferMinutes = PlayerSettingsRepository.DEFAULT_BUFFER_MINUTES
    var player: ExoPlayer = createPlayer(activeBufferMinutes)
        private set
    private val subtitleRepository = SubtitleRepository(appContext.cacheDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            publishPlaybackState()
            maybeSaveHistory()
            if (!released) handler.postDelayed(this, 500L)
        }
    }
    private var released = false
    private var currentVideoUrl: String? = null
    private var ownedUploadVideo: java.io.File? = null
    private var ownedUploadSubtitle: java.io.File? = null
    private var hasTemporaryUploadSource = false
    private var downloadedSubtitle: CachedSubtitle? = null
    private var subtitleJob: Job? = null
    private var sourceGeneration = 0L
    private var historyEntry: HistoryEntry? = null
    private var lastHistorySaveMs = 0L
    private var bufferingTimeoutJob: Job? = null
    private var playbackIntent = false
    private var timedOutGeneration: Long? = null
    private val trackOverrides = mutableMapOf<String, TrackSelectionOverride>()
    private var selectedVideoVariantKey: String? = null

    init {
        java.io.File(appContext.cacheDir, "playback-uploads").apply { mkdirs() }
            .listFiles()?.forEach { it.delete() } // Remove local-media leftovers from a previous process.
        player.addListener(this)
        handler.post(ticker)
    }

    fun playUrl(
        url: String,
        subtitleUrl: String? = null,
        startPositionMs: Long = 0L,
        uploadedSubtitleFile: java.io.File? = null,
        titleOverride: String? = null,
        uploadedVideoFile: java.io.File? = null,
    ) {
        if (released) return
        saveHistoryNow()
        val generation = ++sourceGeneration
        timedOutGeneration = null
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        subtitleJob?.cancel()
        subtitleJob = null
        applySelectedBufferPolicy()
        playbackIntent = true
        player.stop()
        player.clearMediaItems()
        clearOwnedUploads()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        selectedVideoVariantKey = null
        subtitleRepository.discard(downloadedSubtitle)
        downloadedSubtitle = null
        currentVideoUrl = url
        ownedUploadVideo = uploadedVideoFile
        ownedUploadSubtitle = uploadedSubtitleFile
        hasTemporaryUploadSource = uploadedVideoFile != null || uploadedSubtitleFile != null
        val cleanTitle = titleOverride ?: videoFilenameFromUrl(url)
        val host = if (uploadedVideoFile != null) "Local upload" else Uri.parse(url).host.orEmpty()
        historyEntry = null
        historyEntry = historyRepository.newEntry(cleanTitle, host, url)
        saveHistoryNow()
        mutableState.value = PlaybackUiState(
            title = cleanTitle,
            hasMedia = true,
            subtitlesEnabled = true,
            subtitleLoading = subtitleUrl != null || uploadedSubtitleFile != null,
        )
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)), startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
        playbackIntent = true
        if (subtitleUrl != null || uploadedSubtitleFile != null) {
            subtitleJob = scope.launch {
                try {
                    val cached = if (uploadedSubtitleFile != null) subtitleRepository.importFile(uploadedSubtitleFile)
                    else subtitleRepository.download(subtitleUrl!!)
                    if (released || generation != sourceGeneration) {
                        subtitleRepository.discard(cached)
                        return@launch
                    }
                    downloadedSubtitle = cached
                    mutableState.value = mutableState.value.copy(subtitlesAvailable = true, hasExternalSubtitles = true, subtitleError = null, subtitleLoading = false)
                    replaceMediaWithSubtitle(cached, generation)
                } catch (error: SubtitleLoadException) {
                    if (!released && generation == sourceGeneration) {
                        mutableState.value = mutableState.value.copy(subtitleError = error.message, subtitleLoading = false)
                    }
                } catch (error: CancellationException) {
                    throw error
                } finally {
                    uploadedSubtitleFile?.delete()
                    if (ownedUploadSubtitle == uploadedSubtitleFile) ownedUploadSubtitle = null
                }
            }
        }
    }

    fun playUploadedFile(videoFile: java.io.File, displayName: String, subtitleUrl: String?, subtitleFile: java.io.File?) {
        if (released) {
            videoFile.delete()
            subtitleFile?.delete()
            return
        }
        val safeTitle = displayName.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl).take(120).ifBlank { "Uploaded video" }
        playUrl(Uri.fromFile(videoFile).toString(), subtitleUrl, uploadedSubtitleFile = subtitleFile, titleOverride = safeTitle, uploadedVideoFile = videoFile)
    }

    fun togglePlayPause() {
        if (released) return
        playbackIntent = !(player.isPlaying || player.playWhenReady)
        if (playbackIntent) player.play() else player.pause()
        publishPlaybackState()
    }

    /** Keep the existing player intact until a new video is selected. */
    fun setMaximumBufferMinutes(minutes: Int) {
        if (minutes in PlayerSettingsRepository.BUFFER_CHOICES) selectedBufferMinutes = minutes
    }

    private fun applySelectedBufferPolicy() {
        if (activeBufferMinutes == selectedBufferMinutes) return
        player.removeListener(this)
        player.release()
        trackOverrides.clear()
        selectedVideoVariantKey = null
        activeBufferMinutes = selectedBufferMinutes
        player = createPlayer(activeBufferMinutes)
        player.addListener(this)
    }

    private fun createPlayer(bufferMinutes: Int): ExoPlayer {
        val maxBufferMs = bufferMinutes * 60_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, maxBufferMs, 2_500, 5_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(appContext).setLoadControl(loadControl).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
    }

    fun pauseForBackground() {
        if (released) return
        player.pause()
        playbackIntent = false
        publishPlaybackState()
        saveHistoryNow()
        if (hasTemporaryUploadSource) {
            player.stop()
            player.clearMediaItems()
            subtitleJob?.cancel()
            subtitleJob = null
            subtitleRepository.discard(downloadedSubtitle)
            downloadedSubtitle = null
            currentVideoUrl = null
            historyEntry = null
            clearOwnedUploads()
            sourceGeneration++
            mutableState.value = PlaybackUiState()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (released) return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
        publishPlaybackState()
    }

    fun retryPlayback() {
        if (released) return
        val url = currentVideoUrl ?: return
        timedOutGeneration = null
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        val position = player.currentPosition.coerceAtLeast(0L)
        val speed = player.playbackParameters.speed
        val item = mediaItem(url, downloadedSubtitle)
        player.setMediaItem(item, position)
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.playWhenReady = playbackIntent
        mutableState.value = mutableState.value.copy(error = null, canRetry = false, isBuffering = true)
        val generation = sourceGeneration
        startBufferingTimeout(generation)
    }

    fun selectTrack(choice: MediaTrackChoice) {
        if (released) return
        val override = trackOverrides[choice.key] ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
        if (choice.type == C.TRACK_TYPE_VIDEO) selectedVideoVariantKey = choice.key
        publishState()
    }

    fun selectAutomaticVideoVariant() {
        if (released) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        selectedVideoVariantKey = null
        publishState()
    }

    fun cycleSpeed() {
        if (released) return
        val speeds = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
        val current = player.playbackParameters.speed
        val index = speeds.indexOfFirst { it == current }
        val next = speeds[(index + 1).mod(speeds.size)]
        player.setPlaybackSpeed(next)
        publishPlaybackState()
    }

    fun toggleSubtitles() {
        if (released || !mutableState.value.subtitlesAvailable) return
        val enabled = !mutableState.value.subtitlesEnabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        mutableState.value = mutableState.value.copy(subtitlesEnabled = enabled)
    }

    fun loadSubtitleFile(uri: Uri) {
        if (released) return
        if (currentVideoUrl == null) {
            mutableState.value = mutableState.value.copy(subtitleError = "Start a video before adding a subtitle file.")
            return
        }
        val generation = sourceGeneration
        subtitleJob?.cancel()
        mutableState.value = mutableState.value.copy(subtitleLoading = true, subtitleError = null)
        subtitleJob = scope.launch {
            try {
                val imported = subtitleRepository.importLocal(uri, appContext.contentResolver)
                if (released || generation != sourceGeneration) {
                    subtitleRepository.discard(imported)
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    subtitlesAvailable = true,
                    hasExternalSubtitles = true,
                    subtitlesEnabled = true,
                    subtitleOffsetMs = 0L,
                    subtitleLoading = false,
                    subtitleError = null,
                )
                replaceMediaWithSubtitle(imported, generation)
            } catch (error: SubtitleLoadException) {
                if (!released && generation == sourceGeneration) {
                    mutableState.value = mutableState.value.copy(subtitleLoading = false, subtitleError = error.message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!released && generation == sourceGeneration) {
                    mutableState.value = mutableState.value.copy(
                        subtitleLoading = false,
                        subtitleError = "The selected subtitle file could not be read.",
                    )
                }
            }
        }
    }

    fun adjustSubtitleOffset(deltaMs: Long) {
        if (released) return
        val nextOffset = (mutableState.value.subtitleOffsetMs + deltaMs)
            .coerceIn(-SubtitleRepository.MAX_OFFSET_MS, SubtitleRepository.MAX_OFFSET_MS)
        mutableState.value = mutableState.value.copy(subtitleOffsetMs = nextOffset, subtitleError = null)
        val current = downloadedSubtitle ?: return
        val generation = sourceGeneration
        subtitleJob?.cancel()
        subtitleJob = scope.launch {
            try {
                val adjusted = subtitleRepository.applyOffset(current, nextOffset)
                if (released || generation != sourceGeneration) return@launch
                downloadedSubtitle = adjusted
                replaceMediaWithSubtitle(adjusted, generation)
            } catch (error: SubtitleLoadException) {
                if (!released && generation == sourceGeneration) {
                    mutableState.value = mutableState.value.copy(subtitleError = error.message)
                }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    fun cycleDisplayMode() {
        if (released) return
        val values = VideoDisplayMode.entries
        val next = values[(values.indexOf(mutableState.value.displayMode) + 1) % values.size]
        mutableState.value = mutableState.value.copy(displayMode = next)
    }

    fun cycleSubtitleSize() {
        if (released) return
        mutableState.value = mutableState.value.copy(subtitleSizeIndex = (mutableState.value.subtitleSizeIndex + 1) % 4)
    }

    fun cycleSubtitleColor() {
        if (released) return
        mutableState.value = mutableState.value.copy(subtitleColorIndex = (mutableState.value.subtitleColorIndex + 1) % 4)
    }

    fun toggleSubtitleBackground() {
        if (released) return
        mutableState.value = mutableState.value.copy(subtitleBackgroundEnabled = !mutableState.value.subtitleBackgroundEnabled)
    }

    fun release() {
        if (released) return
        val finalEntry = historyEntry
        val finalPosition = player.currentPosition.coerceAtLeast(0L)
        val finalDuration = player.duration.takeIf { it > 0L } ?: 0L
        released = true
        sourceGeneration++
        subtitleJob?.cancel()
        handler.removeCallbacks(ticker)
        bufferingTimeoutJob?.cancel()
        player.removeListener(this)
        player.release()
        clearOwnedUploads()
        subtitleRepository.discard(downloadedSubtitle)
        downloadedSubtitle = null
        if (finalEntry != null && finalPosition > 0L) {
            scope.launch {
                historyRepository.save(finalEntry.copy(positionMs = finalPosition, durationMs = finalDuration, updatedAtMs = System.currentTimeMillis()))
                scope.cancel()
            }
        } else {
            scope.cancel()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = publishPlaybackState()
    override fun onPlaybackStateChanged(playbackState: Int) {
        publishPlaybackState()
        if (playbackState == Player.STATE_ENDED && hasTemporaryUploadSource) {
            val uploadedVideoEnded = ownedUploadVideo != null
            clearOwnedUploads()
            subtitleRepository.discard(downloadedSubtitle)
            downloadedSubtitle = null
            if (uploadedVideoEnded) currentVideoUrl = null
        }
        if (playbackState == Player.STATE_BUFFERING) startBufferingTimeout(sourceGeneration)
        else {
            timedOutGeneration = null
            bufferingTimeoutJob?.cancel()
            bufferingTimeoutJob = null
            if (playbackState == Player.STATE_READY && mutableState.value.error == STALL_ERROR) {
                mutableState.value = mutableState.value.copy(error = null, canRetry = false)
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            timedOutGeneration = null
            startBufferingTimeout(sourceGeneration)
        }
        else if (!playWhenReady) {
            bufferingTimeoutJob?.cancel()
            bufferingTimeoutJob = null
        }
    }

    override fun onTracksChanged(tracks: Tracks) = publishState()

    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        mutableState.value = mutableState.value.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
    }

    override fun onPlayerError(error: PlaybackException) {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        val uploadedVideoFailure = ownedUploadVideo != null
        val uploadedFailure = hasTemporaryUploadSource
        if (uploadedFailure) {
            if (uploadedVideoFailure) currentVideoUrl = null
            clearOwnedUploads()
            subtitleRepository.discard(downloadedSubtitle)
            downloadedSubtitle = null
        }
        mutableState.value = mutableState.value.copy(
            isPlaying = false,
            isBuffering = false,
            error = if (uploadedVideoFailure) "The uploaded video could not be played. Its temporary file was removed; send it again or try another file." else playbackErrorMessage(error),
            canRetry = currentVideoUrl != null,
            hasMedia = currentVideoUrl != null,
        )
    }

    private fun publishTrackState() {
        if (released) return
        trackOverrides.clear()
        val audioChoices = trackChoices(C.TRACK_TYPE_AUDIO)
        val videoChoices = trackChoices(C.TRACK_TYPE_VIDEO).takeIf { it.size > 1 }.orEmpty()
        val hasEmbeddedText = player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any(group::isTrackSupported)
        }
        mutableState.value = mutableState.value.copy(
            audioChoices = audioChoices,
            videoVariantChoices = videoChoices,
            selectedVideoVariantKey = selectedVideoVariantKey,
            subtitlesAvailable = hasEmbeddedText || downloadedSubtitle != null,
            hasExternalSubtitles = downloadedSubtitle != null,
        )
    }

    private fun publishPlaybackState() {
        if (released) return
        val position = player.currentPosition.coerceAtLeast(0L)
        mutableState.value = mutableState.value.copy(
            isPlaying = player.isPlaying,
            hasMedia = currentVideoUrl != null,
            isBuffering = player.playbackState == Player.STATE_BUFFERING && timedOutGeneration != sourceGeneration,
            positionMs = position,
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(position),
            speed = player.playbackParameters.speed,
        )
    }

    private fun publishState() {
        publishTrackState()
        publishPlaybackState()
    }


    private fun startBufferingTimeout(generation: Long) {
        if (released || !playbackIntent || !player.playWhenReady || bufferingTimeoutJob?.isActive == true) return
        bufferingTimeoutJob = scope.launch {
            delay(BUFFERING_TIMEOUT_MS)
            if (!released && generation == sourceGeneration && player.playbackState == Player.STATE_BUFFERING) {
                timedOutGeneration = generation
                mutableState.value = mutableState.value.copy(
                    isBuffering = false,
                    error = STALL_ERROR,
                    canRetry = currentVideoUrl != null,
                )
            }
        }
    }

    private fun playbackErrorMessage(error: PlaybackException): String {
        val status = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        return when {
            status == 401 || status == 403 || status == 410 -> "This direct link was refused or may have expired. Enter a fresh link, then retry."
            status == 404 -> "This video is missing or its link may have expired. Check the link or enter a fresh one, then retry."
            status != null -> "The video server returned an error (HTTP $status). Check the link or try another source."
            error.errorCodeName.contains("NETWORK", ignoreCase = true) || error.errorCodeName.contains("TIMEOUT", ignoreCase = true) ->
                "The TV could not reach the video server. Check the network, then retry or enter a fresh link."
            error.errorCodeName.contains("UNSUPPORTED", ignoreCase = true) || error.errorCodeName.contains("DECODING", ignoreCase = true) ->
                "This TV cannot decode the video format. Try a compatible source or another stream."
            else -> "This video could not be played. Check the link or try another video."
        }
    }

    private fun mediaItem(url: String, subtitle: CachedSubtitle?): MediaItem {
        if (subtitle == null) return MediaItem.fromUri(Uri.parse(url))
        val configuration = SubtitleConfiguration.Builder(Uri.fromFile(subtitle.adjustedFile))
            .setMimeType(subtitle.mimeType)
            .setLanguage("und")
            .setLabel("External subtitles")
            .setSelectionFlags(if (mutableState.value.subtitlesEnabled) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
        return MediaItem.Builder().setUri(Uri.parse(url)).setSubtitleConfigurations(listOf(configuration)).build()
    }

    private fun trackChoices(type: Int): List<MediaTrackChoice> {
        val choices = mutableListOf<MediaTrackChoice>()
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            repeat(group.length) { trackIndex ->
                if (!group.isTrackSupported(trackIndex)) return@repeat
                val format = group.mediaTrackGroup.getFormat(trackIndex)
                val key = "$type:$groupIndex:$trackIndex"
                trackOverrides[key] = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                val label = if (type == C.TRACK_TYPE_AUDIO) {
                    val language = format.language?.takeIf { it.isNotBlank() && it != "und" } ?: "Audio ${choices.size + 1}"
                    val channels = format.channelCount.takeIf { it > 0 }?.let { " · $it channels" }.orEmpty()
                    "$language$channels"
                } else {
                    val dimensions = if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else "Variant ${choices.size + 1}"
                    val bitrate = format.bitrate.takeIf { it > 0 }?.let { " · ${it / 1_000} kb/s" }.orEmpty()
                    "$dimensions$bitrate"
                }
                val selected = if (type == C.TRACK_TYPE_VIDEO) key == selectedVideoVariantKey else group.isTrackSelected(trackIndex)
                choices += MediaTrackChoice(key, type, groupIndex, trackIndex, label, selected)
            }
        }
        return choices
    }

    companion object {
        private const val BUFFERING_TIMEOUT_MS = 45_000L
        private const val STALL_ERROR = "Playback has been waiting for data for too long. Check the TV connection, then retry or enter a fresh link."
    }

    private fun maybeSaveHistory() {
        if (released || !player.isPlaying || abs(player.currentPosition - lastHistorySaveMs) < 15_000L) return
        saveHistoryNow()
    }

    private fun clearOwnedUploads() {
        ownedUploadVideo?.delete()
        ownedUploadVideo = null
        ownedUploadSubtitle?.delete()
        ownedUploadSubtitle = null
        hasTemporaryUploadSource = false
    }

    private fun saveHistoryNow() {
        val entry = historyEntry ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        lastHistorySaveMs = position
        scope.launch {
            historyRepository.save(entry.copy(
                positionMs = position,
                durationMs = duration,
                updatedAtMs = System.currentTimeMillis(),
            ))
        }
    }

    private fun replaceMediaWithSubtitle(subtitle: CachedSubtitle, generation: Long) {
        if (released || generation != sourceGeneration) return
        val url = currentVideoUrl ?: return
        val previousSubtitle = downloadedSubtitle
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition.coerceAtLeast(0L)
        val speed = player.playbackParameters.speed
        player.setMediaItem(mediaItem(url, subtitle), position)
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.playWhenReady = wasPlaying
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !mutableState.value.subtitlesEnabled)
            .build()
        downloadedSubtitle = subtitleRepository.discardObsoleteAdjustedFiles(subtitle)
        if (previousSubtitle != null && previousSubtitle.sourceFile != subtitle.sourceFile) {
            subtitleRepository.discard(previousSubtitle)
        }
    }
}
