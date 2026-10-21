package dev.lmadhuranga.tvplayer

import java.net.URI
import java.util.Locale
import java.net.URI

sealed interface VideoUrlValidation {
    data class Valid(val normalizedUrl: String, val sourceLabel: String) : VideoUrlValidation
    data class Invalid(val message: String) : VideoUrlValidation
}

/** Validate a bounded direct HTTP(S) URL without writing its possibly signed query to logs or disk. */
fun validateVideoUrl(input: String): VideoUrlValidation {
    val value = input.trim()
    if (value.isEmpty()) return VideoUrlValidation.Invalid("Enter a video URL.")
    if (value.length > 4_096) return VideoUrlValidation.Invalid("That URL is too long.")
    if (value.any(Char::isISOControl)) return VideoUrlValidation.Invalid("The URL contains an invalid character.")

    val uri = parseHttpUri(value) ?: run {
        return VideoUrlValidation.Invalid("Enter a valid HTTP or HTTPS URL.")
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") {
        return VideoUrlValidation.Invalid("Only HTTP and HTTPS video links are supported.")
    }
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
        return VideoUrlValidation.Invalid("Enter a link with a valid host and no embedded sign-in details.")
    }
    return VideoUrlValidation.Valid(value, uri.host)
}

fun validateSubtitleUrl(input: String): VideoUrlValidation {
    val result = validateVideoUrl(input)
    if (result is VideoUrlValidation.Invalid) return result
    val value = (result as VideoUrlValidation.Valid).normalizedUrl
    if (value.length > 4_096) return VideoUrlValidation.Invalid("The subtitle link is too long.")
    val path = parseHttpUri(value)?.path.orEmpty().lowercase(Locale.ROOT)
    return if (path.endsWith(".srt") || path.endsWith(".vtt")) result
    else VideoUrlValidation.Invalid("Subtitle links must end in .srt or .vtt.")
}

private fun parseHttpUri(value: String): URI? = try { URI(value) } catch (_: Exception) { null }
