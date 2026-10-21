package com.tvplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTimingTest {
    @Test fun shiftsSrtCueForward() {
        val original = "1\n00:00:01,000 --> 00:00:02,500\nHello!"
        val actual = SubtitleTiming.shift(original, 500, vtt = false)
        assertTrue(actual.contains("00:00:01,500 --> 00:00:03,000"))
        assertTrue(actual.contains("Hello!"))
    }
    @Test fun shiftsVttCueBackwardAndClipsAtZero() {
        val original = "WEBVTT\n\n00:01.000 --> 00:03.000\nHello"
        val actual = SubtitleTiming.shift(original, -1500, vtt = true)
        assertTrue(actual.contains("00:00.000 --> 00:01.500"))
    }
    @Test fun doesNotChangeNormalTextContainingTimestamps() {
        val original = "WEBVTT\n\n00:01.000 --> 00:03.000\nMeet at 12:34.000"
        val actual = SubtitleTiming.shift(original, 500, vtt = true)
        assertTrue(actual.contains("Meet at 12:34.000"))
    }
    @Test fun zeroOffsetLeavesOriginalUnchanged() {
        val original = "WEBVTT\n00:00.900 --> 00:01.500\nHello"
        assertEquals(original, SubtitleTiming.shift(original, 0, vtt = true))
    }
}
