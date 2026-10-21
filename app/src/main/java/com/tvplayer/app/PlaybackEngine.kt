@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tvplayer.app

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

class PlaybackEngine(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
    }
    var isLoading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var hasMedia by mutableStateOf(false); private set
    var trackRevision by mutableStateOf(0); private set
    var currentTitle by mutableStateOf("Video"); private set

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isLoading = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) isPlaying = false
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onTracksChanged(tracks: Tracks) { trackRevision++ }
            override fun onPlayerError(cause: PlaybackException) {
                isLoading = false
                var current: Throwable? = cause
                var code: Int? = null
                while (current != null) {
                    if (current is HttpDataSource.InvalidResponseCodeException) code = current.responseCode
                    current = current.cause
                }
                error = when (code) {
                    401, 403 -> "Access denied (HTTP $code). Your link may be expired or need a new signature."
                    404, 410 -> "Video is no longer available (HTTP $code). Enter a new URL."
                    else -> when (cause.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network connection failed. Check TV connectivity and retry."
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Video codec is not supported by this TV."
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Media format is not supported. Try a direct playable stream."
                        else -> "Playback failed. Check the direct URL and media format, then retry."
                    }
                }
            }
        })
    }

    fun play(entry: VideoEntry, subtitles: SubtitleManager.Prepared?, startMs: Long = 0) {
        error = null
        currentTitle = UrlRules.sanitizedLabel(entry.videoUrl)
        val item = MediaItem.Builder().setUri(entry.videoUrl).apply {
            if (subtitles != null) setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(subtitles.uri)
                    .setMimeType(subtitles.mime)
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }.build()
        hasMedia = true
        player.setMediaItem(item)
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
        player.playWhenReady = true
    }

    fun retry() { error = null; player.prepare(); player.play() }
    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    fun skip(amountMs: Long) {
        val target = (player.currentPosition + amountMs).coerceAtLeast(0)
        val bounded = if (player.duration > 0 && player.duration != C.TIME_UNSET) target.coerceAtMost(player.duration) else target
        player.seekTo(bounded)
    }
    fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed) }
    fun setSubtitleEnabled(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build()
    }
    fun subtitleEnabled(): Boolean = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

    data class TrackOption(val group: Tracks.Group, val index: Int, val label: String)
    fun choices(type: Int): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                val format = group.getTrackFormat(index)
                val label = if (type == C.TRACK_TYPE_VIDEO) {
                    val dimensions = if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else "Video"
                    val bitrate = if (format.bitrate > 0) " · ${format.bitrate / 1000} kbps" else ""
                    "$dimensions$bitrate"
                } else {
                    listOfNotNull(format.label, format.language?.takeUnless { it == "und" },
                        if (format.channelCount > 0) "${format.channelCount} channels" else null).joinToString(" · ").ifBlank { "Audio ${index + 1}" }
                }
                options += TrackOption(group, index, label)
            }
        }
        return options
    }
    fun chooseTrack(type: Int, item: TrackOption?) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (item != null) b.addOverride(TrackSelectionOverride(item.group.mediaTrackGroup, listOf(item.index)))
        player.trackSelectionParameters = b.build()
    }

    fun videoDimensions(): String {
        val v = player.videoFormat ?: return "Not reported"
        return if (v.width > 0 && v.height > 0) "${v.width} × ${v.height} pixels" else "Not reported"
    }

    fun applyViewSettings(view: PlayerView, settings: AppSettings) {
        view.resizeMode = when (settings.displayMode) {
            "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        view.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setFractionalTextSize((0.055f * settings.subtitleSize).coerceIn(0.025f, 0.10f))
            setStyle(CaptionStyleCompat(
                settings.subtitleColor,
                if (settings.subtitleBackground) Color.argb(160, 0, 0, 0) else Color.TRANSPARENT,
                Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK, null
            ))
        }
    }
    fun release() { player.release() }
}
