package dev.lmadhuranga.tvplayer

import android.net.Uri

/** Returns a safe URL-path filename without exposing query credentials in the UI. */
fun videoFilenameFromUrl(url: String): String {
    val uri = Uri.parse(url)
    val encodedSegment = uri.encodedPath.orEmpty().trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank)
    val filename = encodedSegment
        ?.let { Uri.decode(it) }
        ?.substringAfterLast('/')
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(120)
        ?.takeIf(String::isNotBlank)
    return filename ?: uri.host?.takeIf(String::isNotBlank)?.let { "Video from $it" } ?: "Video"
}
