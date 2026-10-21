@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tvplayer.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** External subtitle bytes are fetched by the TV, capped and held only in app-private cache. */
class SubtitleManager(private val context: Context) {
    data class Prepared(val uri: Uri, val mime: String)

    suspend fun prepare(url: String, offsetMs: Long): Prepared = withContext(Dispatchers.IO) {
        require(UrlRules.validHttpUrl(url)) { "Subtitle URL must be HTTP or HTTPS." }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw IllegalArgumentException("Subtitle server returned HTTP ${conn.responseCode}.")
            val length = conn.contentLengthLong
            if (length > 2_000_000) throw IllegalArgumentException("Subtitle file is over the 2 MB limit.")
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 2_000_000) throw IllegalArgumentException("Subtitle file is over the 2 MB limit.")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            val isVtt = text.trimStart().startsWith("WEBVTT") ||
                Uri.parse(url).path.orEmpty().lowercase().endsWith(".vtt")
            if (!text.contains("-->")) throw IllegalArgumentException("Subtitle file does not contain SRT/WebVTT cues.")
            val rendered = SubtitleTiming.shift(text, offsetMs.coerceIn(-60_000, 60_000), isVtt)
            val directory = File(context.cacheDir, "external_subtitles").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() } // no history of signed subtitles on disk
            val file = File.createTempFile("subtitle_", if (isVtt) ".vtt" else ".srt", directory)
            file.writeText(rendered, Charsets.UTF_8)
            Prepared(Uri.fromFile(file), if (isVtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
        } finally { conn.disconnect() }
    }

    fun clear() {
        File(context.cacheDir, "external_subtitles").listFiles()?.forEach { it.delete() }
    }
}
