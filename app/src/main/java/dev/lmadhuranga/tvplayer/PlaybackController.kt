package dev.lmadhuranga.tvplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem.SubtitleConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
    val subtitlesAvailable: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    val subtitleOffsetMs: Long = 0L,
    val subtitleError: String? = null,
)

@OptIn(UnstableApi::class)
class PlaybackController(context: Context) : Player.Listener {
    private val appContext = context.applicationContext
    val player: ExoPlayer = ExoPlayer.Builder(appContext).build()
    private val subtitleRepository = SubtitleRepository(appContext.cacheDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            publishState()
            if (!released) handler.postDelayed(this, 500L)
        }
    }
    private var released = false
    private var currentVideoUrl: String? = null
    private var downloadedSubtitle: CachedSubtitle? = null
    private var subtitleJob: Job? = null
    private var sourceGeneration = 0L

    init {
        player.addListener(this)
        handler.post(ticker)
    }

    fun playUrl(url: String, title: String? = null, subtitleUrl: String? = null) {
        if (released) return
        val generation = ++sourceGeneration
        subtitleJob?.cancel()
        subtitleJob = null
        player.stop()
        player.clearMediaItems()
        subtitleRepository.discard(downloadedSubtitle)
        downloadedSubtitle = null
        currentVideoUrl = url
        mutableState.value = PlaybackUiState(
            title = title?.takeIf(String::isNotBlank) ?: "Video",
            subtitlesEnabled = true,
        )
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
        if (subtitleUrl != null) {
            subtitleJob = scope.launch {
                try {
                    val cached = subtitleRepository.download(subtitleUrl)
                    if (released || generation != sourceGeneration) {
                        subtitleRepository.discard(cached)
                        return@launch
                    }
                    downloadedSubtitle = cached
                    mutableState.value = mutableState.value.copy(subtitlesAvailable = true, subtitleError = null)
                    replaceMediaWithSubtitle(cached, generation)
                } catch (error: SubtitleLoadException) {
                    if (!released && generation == sourceGeneration) {
                        mutableState.value = mutableState.value.copy(subtitleError = error.message)
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        if (released) return
        if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
        publishState()
    }

    fun pauseForBackground() {
        if (released) return
        player.pause()
        publishState()
    }

    fun seekBy(deltaMs: Long) {
        if (released) return
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
        publishState()
    }

    fun cycleSpeed() {
        if (released) return
        val next = when (player.playbackParameters.speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player.setPlaybackSpeed(next)
        publishState()
    }

    fun toggleSubtitles() {
        if (released || !mutableState.value.subtitlesAvailable) return
        val enabled = !mutableState.value.subtitlesEnabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        mutableState.value = mutableState.value.copy(subtitlesEnabled = enabled)
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
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        sourceGeneration++
        subtitleJob?.cancel()
        handler.removeCallbacks(ticker)
        player.removeListener(this)
        player.release()
        subtitleRepository.discard(downloadedSubtitle)
        downloadedSubtitle = null
        scope.cancel()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()
    override fun onPlaybackStateChanged(playbackState: Int) = publishState()

    override fun onPlayerError(error: PlaybackException) {
        // Media errors can include a signed URL. Keep their user-facing form generic.
        mutableState.value = mutableState.value.copy(
            isPlaying = false,
            isBuffering = false,
            error = "This video could not be played. Check the link or try another video.",
        )
    }

    private fun publishState() {
        if (released) return
        mutableState.value = mutableState.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            speed = player.playbackParameters.speed,
        )
    }

    private fun replaceMediaWithSubtitle(subtitle: CachedSubtitle, generation: Long) {
        if (released || generation != sourceGeneration) return
        val url = currentVideoUrl ?: return
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition.coerceAtLeast(0L)
        val speed = player.playbackParameters.speed
        val configuration = SubtitleConfiguration.Builder(Uri.fromFile(subtitle.adjustedFile))
            .setMimeType(subtitle.mimeType)
            .setLanguage("und")
            .setLabel("External subtitles")
            .setSelectionFlags(if (mutableState.value.subtitlesEnabled) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
        val item = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setSubtitleConfigurations(listOf(configuration))
            .build()
        player.setMediaItem(item, position)
        player.prepare()
        player.setPlaybackSpeed(speed)
        player.playWhenReady = wasPlaying
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !mutableState.value.subtitlesEnabled)
            .build()
    }
}
