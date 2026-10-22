package dev.lmadhuranga.tvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRateTest {
    @Test
    fun calculatesBytesPerSecondWithoutBitConversion() {
        assertEquals(2_048L, receiveBytesPerSecond(10_000L, 12_048L, 1_000L))
        assertEquals(1_024L, receiveBytesPerSecond(0L, 2_048L, 2_000L))
    }

    @Test
    fun rejectsUnsupportedResetAndInvalidSamples() {
        assertNull(receiveBytesPerSecond(-1L, 50L, 1_000L))
        assertNull(receiveBytesPerSecond(100L, 90L, 1_000L))
        assertNull(receiveBytesPerSecond(0L, 50L, 0L))
    }

    @Test
    fun formatsRatesAsBytesBasedKilobytesAndMegabytes() {
        assertEquals("0.0 KB/s", formatReceiveRate(0L))
        assertEquals("1.0 KB/s", formatReceiveRate(1_024L))
        assertEquals("1.5 MB/s", formatReceiveRate(1_572_864L))
    }
}
