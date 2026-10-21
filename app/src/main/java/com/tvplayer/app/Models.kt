package com.tvplayer.app

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

data class VideoEntry(val videoUrl: String, val subtitleUrl: String? = null)

data class RecentItem(
    val id: String,
    val label: String,
    val positionMs: Long,
    val updatedMs: Long,
    val savedVideoUrl: String? = null,
    val savedSubtitleUrl: String? = null
)

data class AppSettings(
    val saveFullUrls: Boolean = false,
    val subtitleSize: Float = 1f,
    val subtitleColor: Int = 0xffffffff.toInt(),
    val subtitleBackground: Boolean = true,
    val subtitleOffsetMs: Long = 0,
    val displayMode: String = "Fit"
)

/** Standalone validation that can be unit-tested without an Android runtime. */
object UrlRules {
    fun validHttpUrl(input: String): Boolean {
        if (input.length !in 8..4096 || input.any { it <= ' ' || it == '\u007f' }) return false
        return try {
            val uri = URI(input)
            (uri.scheme?.lowercase(Locale.ROOT) == "http" || uri.scheme?.lowercase(Locale.ROOT) == "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in -1..65535 &&
                (uri.port == -1 || uri.port > 0)
        } catch (_: Exception) { false }
    }

    fun sanitizedLabel(url: String): String {
        val uri = URI(url)
        val host = (uri.host ?: "Video").take(70)
        val leaf = (uri.path ?: "").substringAfterLast('/').take(80)
        return if (leaf.isBlank()) host else "$host / $leaf"
    }

    /** Hash public host/path only; signed query parameters are deliberately excluded. */
    fun sourceId(url: String): String {
        val uri = URI(url)
        val key = "${uri.scheme?.lowercase(Locale.ROOT)}://${uri.host?.lowercase(Locale.ROOT)}:${uri.port}${uri.rawPath}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

object SubtitleTiming {
    private val timestamp = Regex("(?<!\\d)(\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{3}(?!\\d)")
    private fun millis(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        val time = parts.last().split('.')
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        val minutes = parts[parts.size - 2].toLong()
        return (((hours * 60 + minutes) * 60) + time[0].toLong()) * 1000 + time[1].toLong()
    }
    private fun format(ms: Long, vtt: Boolean, includeHours: Boolean): String {
        val clipped = ms.coerceAtLeast(0)
        val h = clipped / 3_600_000
        val m = clipped / 60_000 % 60
        val s = clipped / 1000 % 60
        val milli = clipped % 1000
        val sep = if (vtt) '.' else ','
        return if (vtt && !includeHours && h == 0L) "%02d:%02d%c%03d".format(m, s, sep, milli)
        else "%02d:%02d:%02d%c%03d".format(h, m, s, sep, milli)
    }
    /** Shifts only cue timing lines, leaving subtitle text and metadata untouched. */
    fun shift(input: String, offsetMs: Long, vtt: Boolean): String {
        if (offsetMs == 0L) return input
        return input.lines().joinToString("\n") { line ->
            if (!line.contains("-->")) line else timestamp.replace(line) { match ->
                format(millis(match.value) + offsetMs, vtt, match.value.count { it == ':' } == 2)
            }
        }
    }
}

/** Strict, bounded application/x-www-form-urlencoded decoder for the paired LAN form. */
object HandoffForm {
    fun decode(body: String): Map<String, String> {
        require(body.length in 1..8192) { "Request size out of bounds" }
        val values = mutableMapOf<String, String>()
        for (piece in body.split('&')) {
            val delimiter = piece.indexOf('=')
            require(delimiter >= 0) { "Malformed form field" }
            val key = URLDecoder.decode(piece.substring(0, delimiter), "UTF-8")
            require(key in setOf("pairing", "video", "subtitle")) { "Unsupported form field" }
            require(!values.containsKey(key)) { "Duplicate form field" }
            values[key] = URLDecoder.decode(piece.substring(delimiter + 1), "UTF-8")
        }
        return values
    }
}
