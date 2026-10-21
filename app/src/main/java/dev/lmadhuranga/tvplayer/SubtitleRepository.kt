package dev.lmadhuranga.tvplayer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class CachedSubtitle(
    val sourceFile: File,
    val adjustedFile: File,
    val mimeType: String,
    val generatedFiles: Set<File> = emptySet(),
)

/** Downloads a bounded subtitle to private cache and applies timing shifts to a local copy. */
class SubtitleRepository(cacheDirectory: File) {
    private val directory = File(cacheDirectory, "subtitles").apply { mkdirs() }

    suspend fun download(url: String): CachedSubtitle = withContext(Dispatchers.IO) {
        val mimeType = if (URI(url).path.orEmpty().lowercase(Locale.ROOT).endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
        val source = File.createTempFile("tv-player-${UUID.randomUUID()}-", ".source", directory)
        var adjusted: File? = null
        try {
            val connection = (URL(url).openConnection() as? HttpURLConnection)
                ?: throw SubtitleLoadException("This subtitle link could not be opened.")
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw SubtitleLoadException("The subtitle link could not be reached.")
                if (connection.contentLengthLong > MAX_SUBTITLE_BYTES) throw SubtitleLoadException("This subtitle file is too large.")
                connection.inputStream.use { input ->
                    source.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8_192)
                        var count = 0
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > MAX_SUBTITLE_BYTES) throw SubtitleLoadException("This subtitle file is too large.")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        coroutineContext.ensureActive()
        val result = applyOffset(CachedSubtitle(source, source, mimeType), 0L)
        adjusted = result.adjustedFile
        coroutineContext.ensureActive()
        result
        } catch (error: CancellationException) {
            source.delete()
            adjusted?.delete()
            throw error
        } catch (error: SubtitleLoadException) {
            source.delete()
            throw error
        } catch (_: Exception) {
            source.delete()
            throw SubtitleLoadException("The subtitle could not be downloaded. Check the link and try again.")
        }
    }

    suspend fun importLocal(uri: Uri, resolver: ContentResolver): CachedSubtitle = withContext(Dispatchers.IO) {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val declaredMime = runCatching { resolver.getType(uri) }.getOrNull()
        val mimeType = when {
            extension == "vtt" || declaredMime?.equals(MimeTypes.TEXT_VTT, ignoreCase = true) == true -> MimeTypes.TEXT_VTT
            extension == "srt" || declaredMime?.equals(MimeTypes.APPLICATION_SUBRIP, ignoreCase = true) == true -> MimeTypes.APPLICATION_SUBRIP
            else -> throw SubtitleLoadException("Choose an SRT or WebVTT subtitle file.")
        }
        val source = File.createTempFile("tv-player-${UUID.randomUUID()}-", ".source", directory)
        var adjusted: File? = null
        try {
            val input = resolver.openInputStream(uri) ?: throw SubtitleLoadException("The selected subtitle file could not be opened.")
            input.use { copyBoundedSubtitle(it, source) }
            coroutineContext.ensureActive()
            val result = applyOffset(CachedSubtitle(source, source, mimeType), 0L)
            adjusted = result.adjustedFile
            coroutineContext.ensureActive()
            result
        } catch (error: CancellationException) {
            source.delete()
            adjusted?.delete()
            throw error
        } catch (error: SubtitleLoadException) {
            source.delete()
            adjusted?.delete()
            throw error
        } catch (_: Exception) {
            source.delete()
            adjusted?.delete()
            throw SubtitleLoadException("The selected subtitle file could not be read.")
        }
    }

    private suspend fun copyBoundedSubtitle(input: InputStream, destination: File) {
        input.use { stream ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(8_192)
                var count = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > MAX_SUBTITLE_BYTES) throw SubtitleLoadException("This subtitle file is too large.")
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    suspend fun applyOffset(subtitle: CachedSubtitle, offsetMs: Long): CachedSubtitle = withContext(Dispatchers.IO) {
        if (offsetMs !in -MAX_OFFSET_MS..MAX_OFFSET_MS) throw SubtitleLoadException("Subtitle timing offset is out of range.")
        val raw = try { subtitle.sourceFile.readText(StandardCharsets.UTF_8).removePrefix("\uFEFF") }
        catch (_: Exception) { throw SubtitleLoadException("The subtitle file could not be read.") }
        val output = StringBuilder(raw.length)
        var previousIndex = 0
        var cueCount = 0
        TIMESTAMP_LINE.findAll(raw).forEach { match ->
            coroutineContext.ensureActive()
            output.append(raw, previousIndex, match.range.first)
            val start = shiftTimestamp(match.groupValues[1], offsetMs)
            val end = shiftTimestamp(match.groupValues[2], offsetMs)
            output.append(start).append(" --> ").append(end).append(match.groupValues[3])
            previousIndex = match.range.last + 1
            cueCount++
        }
        if (cueCount == 0) throw SubtitleLoadException("Use a valid SRT or WebVTT subtitle file.")
        output.append(raw, previousIndex, raw.length)
        val extension = if (subtitle.mimeType == MimeTypes.TEXT_VTT) ".vtt" else ".srt"
        val adjusted = File(directory, "${UUID.randomUUID()}$extension")
        try {
            adjusted.writeText(output.toString(), StandardCharsets.UTF_8)
            coroutineContext.ensureActive()
        } catch (error: CancellationException) {
            adjusted.delete()
            throw error
        } catch (_: Exception) {
            adjusted.delete()
            throw SubtitleLoadException("The subtitle file could not be prepared.")
        }
        subtitle.copy(adjustedFile = adjusted, generatedFiles = subtitle.generatedFiles + adjusted)
    }

    fun discard(subtitle: CachedSubtitle?) {
        subtitle?.sourceFile?.delete()
        subtitle?.generatedFiles?.forEach(File::delete)
    }

    /** Call only after the player has switched away from its previous adjusted file. */
    fun discardObsoleteAdjustedFiles(subtitle: CachedSubtitle): CachedSubtitle {
        val obsolete = subtitle.generatedFiles - subtitle.adjustedFile
        obsolete.forEach(File::delete)
        return subtitle.copy(generatedFiles = setOf(subtitle.adjustedFile))
    }

    companion object {
        const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
        const val MAX_OFFSET_MS = 30_000L
        private val TIMESTAMP_LINE = Regex("(?m)^((?:\\d{2}:)?\\d{2}:\\d{2}[,.]\\d{3})\\s+-->\\s+((?:\\d{2}:)?\\d{2}:\\d{2}[,.]\\d{3})(.*)$")

        private fun shiftTimestamp(value: String, offsetMs: Long): String {
            val splitAt = value.lastIndexOfAny(charArrayOf('.', ','))
            val delimiter = value[splitAt]
            val time = value.substring(0, splitAt).split(':').map(String::toLong)
            val milliseconds = value.substring(splitAt + 1).toLong()
            val seconds = if (time.size == 3) time[0] * 3_600 + time[1] * 60 + time[2] else time[0] * 60 + time[1]
            val shifted = (seconds * 1_000 + milliseconds + offsetMs).coerceAtLeast(0L)
            val hours = shifted / 3_600_000
            val minutes = shifted / 60_000 % 60
            val wholeSeconds = shifted / 1_000 % 60
            val millis = shifted % 1_000
            return if (time.size == 3) String.format(Locale.US, "%02d:%02d:%02d%c%03d", hours, minutes, wholeSeconds, delimiter, millis)
            else String.format(Locale.US, "%02d:%02d%c%03d", shifted / 60_000, wholeSeconds, delimiter, millis)
        }
    }
}

class SubtitleLoadException(message: String) : IOException(message)
