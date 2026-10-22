package dev.lmadhuranga.tvplayer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHiddenFilesPolicyTest {
    @Test fun hiddenFilesAreDisabledByDefaultAndDotNamesFollowThePreference() {
        assertFalse(PlayerSettingsRepository.DEFAULT_SHOW_HIDDEN_FILES)
        assertFalse(isUsbNameVisible(".Trash", PlayerSettingsRepository.DEFAULT_SHOW_HIDDEN_FILES))
        assertFalse(isUsbNameVisible(".DS_Store", false))
        assertTrue(isUsbNameVisible("movie.mp4", false))
        assertTrue(isUsbNameVisible(".Trash", true))
        assertTrue(isUsbNameVisible("._movie.mp4", true))
    }

    @Test fun folderCountsUseTheSameHiddenVisibilityPolicyAsListings() {
        val directory = Files.createTempDirectory("usb-hidden-counts").toFile()
        try {
            File(directory, "Movies").mkdir()
            File(directory, ".Trash").mkdir()
            File(directory, "movie.mp4").writeText("video")
            File(directory, ".DS_Store").writeText("metadata")

            assertEquals(1 to 1, countVisibleUsbChildren(directory.listFiles()!!.asIterable(), false))
            assertEquals(2 to 2, countVisibleUsbChildren(directory.listFiles()!!.asIterable(), true))
        } finally {
            directory.deleteRecursively()
        }
    }
}
