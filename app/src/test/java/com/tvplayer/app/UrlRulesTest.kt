package com.tvplayer.app

import org.junit.Assert.*
import org.junit.Test

class UrlRulesTest {
    @Test fun acceptsHttpsAndHttpUrls() {
        assertTrue(UrlRules.validHttpUrl("https://cdn.example.org/movie.mp4?expires=123&sig=secret"))
        assertTrue(UrlRules.validHttpUrl("http://192.168.1.40:8080/stream.m3u8"))
    }
    @Test fun rejectsUnsupportedOrMalformedUrls() {
        listOf("", "ftp://example.org/x", "file:///etc/passwd", "javascript:alert(1)",
            "https://", "https://user:pass@example.org/video", "https://example.org/has space")
            .forEach { assertFalse(it, UrlRules.validHttpUrl(it)) }
    }
    @Test fun fingerprintsIgnoreSignedQueryButNotPath() {
        val a = UrlRules.sourceId("https://cdn.example.org/video.mp4?token=abc")
        assertEquals(a, UrlRules.sourceId("https://cdn.example.org/video.mp4?token=xyz"))
        assertNotEquals(a, UrlRules.sourceId("https://cdn.example.org/other.mp4"))
    }
    @Test fun userFacingLabelsNeverLeakSignedQuery() {
        val label = UrlRules.sanitizedLabel("https://cdn.example.org/movie.mp4?token=private-value")
        assertFalse(label.contains("private-value"))
        assertTrue(label.contains("movie.mp4"))
    }
}
