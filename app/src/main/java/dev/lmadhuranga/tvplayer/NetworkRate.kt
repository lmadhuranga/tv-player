package dev.lmadhuranga.tvplayer

import java.util.Locale

/** Returns null when TrafficStats cannot provide a monotonic counter sample. */
internal fun receiveBytesPerSecond(previousBytes: Long, currentBytes: Long, elapsedMillis: Long): Long? {
    if (previousBytes < 0L || currentBytes < previousBytes || elapsedMillis <= 0L) return null
    val delta = currentBytes - previousBytes
    return (delta.toDouble() * 1_000.0 / elapsedMillis).toLong().coerceAtLeast(0L)
}

internal fun formatReceiveRate(bytesPerSecond: Long): String {
    val safeRate = bytesPerSecond.coerceAtLeast(0L)
    val kibibyte = 1024.0
    return if (safeRate >= 1024L * 1024L) {
        String.format(Locale.US, "%.1f MB/s", safeRate / (kibibyte * kibibyte))
    } else {
        String.format(Locale.US, "%.1f KB/s", safeRate / kibibyte)
    }
}
