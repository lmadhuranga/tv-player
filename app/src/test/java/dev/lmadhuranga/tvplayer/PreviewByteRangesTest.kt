package dev.lmadhuranga.tvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewByteRangesTest {
    @Test fun completeFileWithoutRange() {
        assertEquals(PreviewByteRanges.Window(0, 999), PreviewByteRanges.parse(null, 1000))
    }

    @Test fun rangeFromStartAndOpenEndedRange() {
        assertEquals(PreviewByteRanges.Window(0, 99), PreviewByteRanges.parse("bytes=0-99", 1000))
        assertEquals(PreviewByteRanges.Window(900, 999), PreviewByteRanges.parse("bytes=900-", 1000))
        assertEquals(PreviewByteRanges.Window(500, 999), PreviewByteRanges.parse("bytes=500-2000", 1000))
    }

    @Test fun suffixRangeWorksWithoutUnderflow() {
        assertEquals(PreviewByteRanges.Window(900, 999), PreviewByteRanges.parse("bytes=-100", 1000))
        assertEquals(PreviewByteRanges.Window(0, 999), PreviewByteRanges.parse("bytes=-9999", 1000))
    }

    @Test fun malformedAndUnsatisfiableRangesAreRejected() {
        for (range in listOf("bytes=1000-", "bytes=99-20", "bytes=-0", "bytes=0-5,10-20",
            "bytes=abc-20", "items=0-4", "bytes=-", "bytes=--1", "bytes=0-99999999999999999999")) {
            assertNull(range, PreviewByteRanges.parse(range, 1000))
        }
        assertNull(PreviewByteRanges.parse(null, 0))
    }
}
