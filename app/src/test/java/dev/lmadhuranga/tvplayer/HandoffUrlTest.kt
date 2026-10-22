package dev.lmadhuranga.tvplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffUrlTest {
    @Test fun unprovisionedConnectionUsesHttp() {
        assertEquals("http://192.168.8.100:8765/", HandoffState(address = "192.168.8.100").pageUrl)
    }

    @Test fun provisionedConnectionUsesHttpsWithSamePort() {
        assertEquals(
            "https://192.168.8.100:8765/",
            HandoffState(address = "192.168.8.100", isHttps = true).pageUrl,
        )
    }

    @Test fun noAddressDoesNotAdvertiseInvalidLink() {
        assertNull(HandoffState(isHttps = true).pageUrl)
    }
}
