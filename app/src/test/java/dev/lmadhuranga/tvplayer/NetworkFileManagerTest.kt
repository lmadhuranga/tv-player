package dev.lmadhuranga.tvplayer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NetworkFileManagerTest {
    private lateinit var sandbox: File
    private lateinit var root: File
    private lateinit var manager: NetworkFileManager

    @Before fun setUp() {
        sandbox = Files.createTempDirectory("tv-player-file-manager-test").toFile()
        root = File(sandbox, "drive").apply { mkdir() }
        manager = NetworkFileManager(null) { listOf(NetworkFileManager.Root("usb-fixture", "Fixture", root, true)) }
    }

    @After fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test fun uploadCreatesFileAndNeverOverwritesAnExistingFile() {
        manager.upload("usb-fixture", "", "movie.mp4", 4, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))) { true }
        val destination = File(root, "movie.mp4")
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.readBytes())

        try {
            manager.upload("usb-fixture", "", "movie.mp4", 1, ByteArrayInputStream(byteArrayOf(9))) { true }
            fail("Expected overwrite to be rejected")
        } catch (_: NetworkFileManager.FileExistsException) {
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.readBytes())
        }
    }

    @Test fun interruptedUploadRemovesItsTemporaryFile() {
        try {
            manager.upload("usb-fixture", "", "partial.bin", 8, ByteArrayInputStream(byteArrayOf(1, 2))) { true }
            fail("Expected an incomplete upload to fail")
        } catch (_: IOException) {
            assertFalse(File(root, "partial.bin").exists())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun traversalHiddenAndReservedNamesAreRejected() {
        assertRejected { manager.createFolder("usb-fixture", "../", "escape") }
        assertRejected { manager.createFolder("usb-fixture", "", ".private") }
        assertRejected { manager.createFolder("usb-fixture", "", "Android") }
        assertFalse(File(sandbox, "escape").exists())
    }

    @Test fun symlinkCannotEscapeTheMountedRoot() {
        val outside = File(sandbox, "outside").apply { mkdir() }
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        assertRejected { manager.createFolder("usb-fixture", "escape", "created") }
        assertFalse(File(outside, "created").exists())
    }

    @Test fun symlinksInsideTheMountedRootAreNotBrowsableOrDeletable() {
        val real = File(root, "real").apply { mkdir() }
        Files.createSymbolicLink(File(root, "alias").toPath(), real.toPath())
        assertRejected { manager.listJson("usb-fixture", "alias") }
        assertRejected { manager.createFolder("usb-fixture", "alias", "created") }
        assertRejected { manager.delete("usb-fixture", "", "alias") }
        assertFalse(File(real, "created").exists())
    }

    @Test fun onlyEmptyFoldersAndFilesCanBeDeleted() {
        val folder = File(root, "folder").apply { mkdir() }
        File(folder, "keep.txt").writeText("user data")
        assertRejected { manager.delete("usb-fixture", "", "folder") }
        assertTrue(File(folder, "keep.txt").exists())
        manager.createFolder("usb-fixture", "", "empty")
        manager.delete("usb-fixture", "", "empty")
        assertFalse(File(root, "empty").exists())
        File(root, "remove.txt").writeText("selected")
        manager.delete("usb-fixture", "", "remove.txt")
        assertEquals(listOf("folder"), root.list()?.toList())
    }

    @Test fun cancelledUploadRemovesTemporaryFileAndPreservesExistingFiles() {
        File(root, "keep.txt").writeText("keep")
        try {
            manager.upload("usb-fixture", "", "new.bin", 4, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))) { false }
            fail("Expected a cancelled upload to fail")
        } catch (_: IOException) {
            assertEquals("keep", File(root, "keep.txt").readText())
            assertFalse(File(root, "new.bin").exists())
            assertEquals(listOf("keep.txt"), root.list()?.filterNot { it.startsWith(".tvplayer-upload-") })
        }
    }

    @Test fun nextUploadPrunesOnlyStaleOwnedTemporaryFiles() {
        val stale = File(root, ".tvplayer-upload-00000000-0000-0000-0000-000000000000.tmp").apply {
            writeText("partial")
        }
        val unrelated = File(root, ".other-hidden.tmp").apply { writeText("keep") }
        val target = File(sandbox, "outside.tmp").apply { writeText("safe") }
        val link = File(root, ".tvplayer-upload-11111111-1111-1111-1111-111111111111.tmp")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        manager.upload("usb-fixture", "", "new.bin", 1, ByteArrayInputStream(byteArrayOf(7))) { true }

        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("safe", target.readText())
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("Expected unsafe file operation to be rejected")
        } catch (_: IOException) {
            // Expected validation or filesystem failure.
        }
    }
}
