package dev.lmadhuranga.tvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleMatcherTest {
    @Test
    fun prefersCaseInsensitiveMatchingBasename() {
        val result = SubtitleMatcher.match("Film.MKV", listOf("other.srt", "film.VTT", "notes.txt"))

        assertEquals(SubtitleMatch("film.VTT", SubtitleMatchStatus.MATCHED_BASENAME), result)
    }

    @Test
    fun usesOnlySupportedFileWhenNoBasenameMatches() {
        val result = SubtitleMatcher.match("Film.mp4", listOf("captions.srt", "readme.txt"))

        assertEquals(SubtitleMatch("captions.srt", SubtitleMatchStatus.ONLY_SUBTITLE), result)
    }

    @Test
    fun doesNotGuessWhenFolderHasMultipleUnmatchedSubtitles() {
        val result = SubtitleMatcher.match("Film.mp4", listOf("english.srt", "සිංහල.vtt"))

        assertNull(result.filename)
        assertEquals(SubtitleMatchStatus.AMBIGUOUS, result.status)
    }

    @Test
    fun doesNotGuessWhenBasenameMatchesMoreThanOneSubtitle() {
        val result = SubtitleMatcher.match("Film.mkv", listOf("film.srt", "FILM.vtt"))

        assertNull(result.filename)
        assertEquals(SubtitleMatchStatus.AMBIGUOUS, result.status)
    }

    @Test
    fun ignoresUnsupportedExtensions() {
        val result = SubtitleMatcher.match("Film.mkv", listOf("film.ass", "notes.txt"))

        assertEquals(SubtitleMatch(null, SubtitleMatchStatus.NONE), result)
    }
}
