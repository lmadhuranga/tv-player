package dev.lmadhuranga.tvplayer

import android.content.ContentUris
import android.content.Context
import android.annotation.TargetApi
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UsbVideo(
    val uri: Uri,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
)

class UsbVideoRepository(private val context: Context) {
    suspend fun listVideos(): List<UsbVideo> = withContext(Dispatchers.IO) {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val mountedUsbIds = storageManager.storageVolumes
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .flatMap { volume ->
                buildList {
                    volume.uuid?.let { add(it.lowercase()) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { volume.directory?.name?.lowercase() }.getOrNull()?.let(::add)
                    }
                }
            }.toSet()

        val videos = mutableListOf<UsbVideo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            videos += listModernVolumes(mountedUsbIds)
        } else {
            val removableRoots = storageManager.storageVolumes
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { it.uuid?.let { uuid -> "/storage/$uuid/" } }
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA,
            )
            context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)).orEmpty()
                    if (removableRoots.any { path.startsWith(it, ignoreCase = true) }) {
                        videos += cursor.toUsbVideo(collection, hasRelativePath = false)
                    }
                }
            }
        }
        videos
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun listModernVolumes(mountedUsbIds: Set<String>): List<UsbVideo> {
        val videos = mutableListOf<UsbVideo>()
        MediaStore.getExternalVolumeNames(context)
            .filter { it.lowercase() in mountedUsbIds }
            .forEach { volumeName ->
                val collection = MediaStore.Video.Media.getContentUri(volumeName)
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                )
                context.contentResolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext()) videos += cursor.toUsbVideo(collection, hasRelativePath = true)
                }
            }
        return videos
    }

    private fun Cursor.toUsbVideo(collection: Uri, hasRelativePath: Boolean): UsbVideo {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Video.Media._ID))
        val name = getString(getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME))?.takeIf(String::isNotBlank) ?: "Video"
        val pathColumn = if (hasRelativePath) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.Video.Media.DATA
        val rawPath = getString(getColumnIndexOrThrow(pathColumn)).orEmpty()
        val folder = if (hasRelativePath) rawPath.trim('/').ifBlank { "USB root" } else rawPath.substringBeforeLast('/', "USB root")
        val sizeIndex = getColumnIndex(MediaStore.Video.Media.SIZE)
        val size = if (sizeIndex >= 0) getLong(sizeIndex).coerceAtLeast(0L) else 0L
        return UsbVideo(ContentUris.withAppendedId(collection, id), name, folder, size)
    }
}
