package dev.lmadhuranga.tvplayer

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackKeepAwakeTest {
    @Test
    fun keepsScreenOnWhileIntendedPlaybackBuffers() {
        assertTrue(
            shouldKeepScreenOn(
                playbackIntent = true,
                playWhenReady = true,
                hasMedia = true,
                playbackState = Player.STATE_BUFFERING,
                hasPlaybackError = false,
            ),
        )
    }

    @Test
    fun releasesScreenOnAfterPauseEndErrorOrMissingMedia() {
        assertFalse(shouldKeepScreenOn(false, true, true, Player.STATE_READY, false))
        assertFalse(shouldKeepScreenOn(true, false, true, Player.STATE_READY, false))
        assertFalse(shouldKeepScreenOn(true, true, true, Player.STATE_ENDED, false))
        assertFalse(shouldKeepScreenOn(true, true, true, Player.STATE_READY, true))
        assertFalse(shouldKeepScreenOn(true, true, false, Player.STATE_READY, false))
    }
}
