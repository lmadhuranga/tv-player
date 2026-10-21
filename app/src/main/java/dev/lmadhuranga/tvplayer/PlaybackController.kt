package dev.lmadhuranga.tvplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUiState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
)

@OptIn(UnstableApi::class)
class PlaybackController(context: Context) : Player.Listener {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
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

    init {
        player.addListener(this)
        handler.post(ticker)
    }

    fun playUrl(url: String, title: String? = null) {
        mutableState.value = PlaybackUiState(title = title?.takeIf(String::isNotBlank) ?: "Video")
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
        publishState()
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
        publishState()
    }

    fun cycleSpeed() {
        val next = when (player.playbackParameters.speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player.setPlaybackSpeed(next)
        publishState()
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(ticker)
        player.removeListener(this)
        player.release()
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
}
