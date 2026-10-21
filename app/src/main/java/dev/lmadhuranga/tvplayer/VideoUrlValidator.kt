package dev.lmadhuranga.tvplayer

import java.net.URI
import java.util.Locale

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

    val uri = try {
        URI(value)
    } catch (_: Exception) {
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
