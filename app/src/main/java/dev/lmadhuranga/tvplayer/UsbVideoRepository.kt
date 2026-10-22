package dev.lmadhuranga.tvplayer

import android.content.Context
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.os.storage.StorageVolume
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UsbRoot(
    val name: String,
    val directory: File,
    val folderCount: Int?,
    val fileCount: Int?,
)

data class UsbIndexedVideo(val uri: Uri, val name: String, val folder: String, val sizeBytes: Long?)

data class UsbEntry(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val folderCount: Int? = null,
    val fileCount: Int? = null,
) {
    val typeLabel: String
        get() = when {
            isDirectory -> "Folder"
            file.extension.equals("srt", ignoreCase = true) || file.extension.equals("vtt", ignoreCase = true) -> "Subtitle"
            file.extension.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS -> "Video"
            else -> "File"
        }

    val canPlay: Boolean get() = !isDirectory && typeLabel == "Video"

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "m4v", "webm", "mov", "avi", "mpg", "mpeg", "ts", "3gp")
    }
}

data class UsbVideoSelection(
    val video: UsbEntry,
    val subtitles: List<UsbEntry>,
    val automaticSubtitle: UsbEntry?,
    val subtitleStatus: SubtitleMatchStatus,
)

enum class SubtitleMatchStatus { MATCHED_BASENAME, ONLY_SUBTITLE, NONE, AMBIGUOUS }

/** Reads only the current directory on a mounted removable volume; it never caches stale file URIs. */
class UsbVideoRepository(private val context: Context) {
    /** Legacy MediaStore fallback for devices where direct folder access is unavailable. */
    suspend fun listIndexedVideos(): List<UsbIndexedVideo> = withContext(Dispatchers.IO) {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val mountedUsbIds = storageManager.storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .flatMap { volume -> buildList {
                volume.uuid?.let { add(it.lowercase(Locale.ROOT)) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { volume.directory?.name?.lowercase(Locale.ROOT) }.getOrNull()?.let(::add)
            } }.toSet()
        val videos = mutableListOf<UsbIndexedVideo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).filter { it.lowercase(Locale.ROOT) in mountedUsbIds }.forEach { volumeName ->
                val collection = MediaStore.Video.Media.getContentUri(volumeName)
                val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.MediaColumns.RELATIVE_PATH)
                context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { cursor ->
                    while (cursor.moveToNext()) videos += cursor.toIndexedVideo(collection, true)
                }
            }
        } else {
            val roots = storageManager.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { it.uuid?.let { uuid -> "/storage/$uuid/" } }
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATA)
            context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)).orEmpty()
                    if (roots.any { path.startsWith(it, ignoreCase = true) }) videos += cursor.toIndexedVideo(collection, false)
                }
            }
        }
        videos
    }

    suspend fun listRoots(): List<UsbRoot> = withContext(Dispatchers.IO) {
        context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { volume -> volume.toRoot() }
            .filter { it.directory.isDirectory && it.directory.canRead() }
            .distinctBy { it.directory.canonicalPath }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    suspend fun listEntries(root: UsbRoot, directory: File): List<UsbEntry> = withContext(Dispatchers.IO) {
        val safeDirectory = directory.canonicalFile
        val safeRoot = root.directory.canonicalFile
        require(safeDirectory.path == safeRoot.path || safeDirectory.path.startsWith(safeRoot.path + File.separator)) {
            "This folder is outside the mounted USB drive."
        }
        require(safeDirectory.isDirectory && safeDirectory.canRead()) { "This USB folder is no longer available." }
        (safeDirectory.listFiles() ?: throw IllegalStateException("Could not read the contents of this USB folder."))
            .asSequence()
            .filter { it.isDirectory || it.isFile }
            .filterNot { it.isDirectory && it.name.startsWith('.') }
            .map { file ->
                val isDirectory = file.isDirectory
                val childCounts = if (isDirectory) file.childCounts() else null
                UsbEntry(
                    file.name,
                    file,
                    isDirectory,
                    fileSizeBytes(file),
                    childCounts?.first,
                    childCounts?.second,
                )
            }
            .sortedWith(compareBy<UsbEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            .toList()
    }

    suspend fun selection(video: UsbEntry): UsbVideoSelection = withContext(Dispatchers.IO) {
        require(!video.isDirectory && video.file.isFile && video.file.canRead()) { "This USB video is no longer available." }
        val siblings = video.file.parentFile?.listFiles().orEmpty()
        val subtitles = siblings.asSequence()
            .filter { it.isFile && isSupportedMedia(it.extension, SUBTITLE_EXTENSIONS) }
            .map { UsbEntry(it.name, it, false, fileSizeBytes(it)) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toList()
        val match = SubtitleMatcher.match(video.name, subtitles.map { it.name })
        val selected = when (match.status) {
            SubtitleMatchStatus.MATCHED_BASENAME, SubtitleMatchStatus.ONLY_SUBTITLE -> subtitles.firstOrNull { it.name == match.filename }
            SubtitleMatchStatus.NONE, SubtitleMatchStatus.AMBIGUOUS -> null
        }
        UsbVideoSelection(video, subtitles, selected, match.status)
    }

    private fun StorageVolume.toRoot(): UsbRoot? {
        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { this.directory }.getOrNull()
        } else {
            uuid?.let { File("/storage", it) }
        } ?: return null
        val childCounts = directory.childCounts()
        return UsbRoot(directory.name.ifBlank { getDescription(context) }, directory, childCounts?.first, childCounts?.second)
    }

    /** Counts immediate visible folders and regular files. Null means the directory could not be read. */
    private fun File.childCounts(): Pair<Int, Int>? {
        if (!isDirectory || !canRead()) return null
        val children = listFiles() ?: return null
        return children.count { it.isDirectory && !it.name.startsWith('.') } to children.count { it.isFile }
    }

    private fun Cursor.toIndexedVideo(collection: Uri, hasRelativePath: Boolean): UsbIndexedVideo {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Video.Media._ID))
        val name = getString(getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))?.takeIf(String::isNotBlank) ?: "Video"
        val pathColumn = if (hasRelativePath) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.Video.Media.DATA
        val rawPath = getString(getColumnIndexOrThrow(pathColumn)).orEmpty()
        val folder = if (hasRelativePath) rawPath.trim('/').ifBlank { "USB root" } else rawPath.substringBeforeLast('/', "USB root")
        val sizeIndex = getColumnIndex(MediaStore.Video.Media.SIZE)
        val size = if (sizeIndex >= 0 && !isNull(sizeIndex)) getLong(sizeIndex).takeIf { it >= 0L } else null
        return UsbIndexedVideo(ContentUris.withAppendedId(collection, id), name, folder, size)
    }

    private fun fileSizeBytes(file: File): Long? =
        if (file.isFile && file.canRead()) file.length().takeIf { it >= 0L } else null

    private fun isSupportedMedia(extension: String, extensions: Set<String>): Boolean =
        extension.lowercase(Locale.ROOT) in extensions

    companion object {
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt")
    }
}

data class SubtitleMatch(val filename: String?, val status: SubtitleMatchStatus)

/** Deterministic sidecar choice: exact basename wins; otherwise a single file is safe to infer. */
object SubtitleMatcher {
    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt")

    fun match(videoName: String, subtitleNames: List<String>): SubtitleMatch {
        val supported = subtitleNames.filter { it.substringAfterLast('.', "").lowercase(Locale.ROOT) in SUBTITLE_EXTENSIONS }
        val basename = videoName.substringBeforeLast('.', videoName).lowercase(Locale.ROOT)
        val exact = supported.filter {
            it.substringBeforeLast('.', it).equals(basename, ignoreCase = true)
        }
        return when {
            exact.size == 1 -> SubtitleMatch(exact.single(), SubtitleMatchStatus.MATCHED_BASENAME)
            exact.size > 1 -> SubtitleMatch(null, SubtitleMatchStatus.AMBIGUOUS)
            supported.size == 1 -> SubtitleMatch(supported.single(), SubtitleMatchStatus.ONLY_SUBTITLE)
            supported.isEmpty() -> SubtitleMatch(null, SubtitleMatchStatus.NONE)
            else -> SubtitleMatch(null, SubtitleMatchStatus.AMBIGUOUS)
        }
    }
}
