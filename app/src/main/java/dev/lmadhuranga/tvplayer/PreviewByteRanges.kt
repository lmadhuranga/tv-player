package dev.lmadhuranga.tvplayer

/** Resolves a single HTTP bytes range; multiple ranges and malformed input are rejected. */
internal object PreviewByteRanges {
    data class Window(val first: Long, val last: Long) {
        val length: Long get() = last - first + 1L
    }

    fun parse(header: String?, fileLength: Long): Window? {
        if (fileLength <= 0L) return null
        if (header == null) return Window(0L, fileLength - 1L)
        if (!header.startsWith("bytes=") || ',' in header) return null
        val value = header.removePrefix("bytes=")
        val dash = value.indexOf('-')
        if (dash < 0 || value.indexOf('-', dash + 1) != -1) return null
        val firstText = value.substring(0, dash)
        val lastText = value.substring(dash + 1)
        if (firstText.isEmpty()) {
            val suffix = lastText.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            return Window((fileLength - suffix.coerceAtMost(fileLength)), fileLength - 1L)
        }
        val first = firstText.toLongOrNull() ?: return null
        if (first < 0L || first >= fileLength) return null
        val last = if (lastText.isEmpty()) fileLength - 1L else {
            (lastText.toLongOrNull() ?: return null).coerceAtMost(fileLength - 1L)
        }
        return if (last < first) null else Window(first, last)
    }
}
