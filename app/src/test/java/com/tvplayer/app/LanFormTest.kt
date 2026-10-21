package com.tvplayer.app

import org.junit.Assert.*
import org.junit.Test

class LanFormTest {
    @Test fun decodesFormFields() {
        val params = HandoffForm.decode("pairing=ABCDEFGH&video=https%3A%2F%2Fexample.org%2Fmovie.mp4&subtitle=")
        assertEquals("ABCDEFGH", params["pairing"])
        assertEquals("https://example.org/movie.mp4", params["video"])
        assertEquals("", params["subtitle"])
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicatePairing() { HandoffForm.decode("pairing=A&pairing=B&video=https%3A%2F%2Fexample.org") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnrecognizedFields() { HandoffForm.decode("pairing=A&secret=unexpected") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedRequest() { HandoffForm.decode("x".repeat(8193)) }
}
