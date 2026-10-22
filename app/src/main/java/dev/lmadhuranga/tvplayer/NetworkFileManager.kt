package dev.lmadhuranga.tvplayer

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException as NioFileAlreadyExistsException
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Restricts browser file operations to mounted removable roots and public Movies storage. */
internal class NetworkFileManager(
    private val context: Context?,
    private val rootProvider: (() -> List<Root>)? = null,
) {
    private val activeUploads = ConcurrentHashMap.newKeySet<String>()

    data class Root(val id: String, val name: String, val directory: File, val removable: Boolean)

    fun roots(): List<Root> {
        rootProvider?.let { return it() }
        val result = mutableListOf<Root>()
        if (hasStorageReadAccess()) {
            val appContext = requireNotNull(context)
            appContext.getSystemService(StorageManager::class.java).storageVolumes
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .forEach { volume ->
                    val directory = volume.directoryForFileManager() ?: return@forEach
                    if (directory.isDirectory && directory.canRead()) {
                        val idPart = (volume.uuid ?: directory.name).filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                        if (idPart.isNotBlank()) result += Root("usb-$idPart", volume.getDescription(appContext).orEmpty().ifBlank { "Removable drive" }, directory, true)
                    }
                }
        }
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (movies.isDirectory && movies.canRead()) result += Root("shared-movies", "Shared Movies", movies, false)
        return result.distinctBy { runCatching { it.directory.canonicalPath }.getOrNull() }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun rootsJson(): String = JSONObject().put("storageAccess", hasStorageReadAccess()).put("roots", JSONArray().apply {
        roots().forEach { root ->
            val free = root.directory.usableSpace.coerceAtLeast(0L)
            put(JSONObject().put("id", root.id).put("name", root.name).put("removable", root.removable)
                .put("readOnly", !canWrite(root, root.directory)).put("freeBytes", free))
        }
    }).toString()

    fun listJson(rootId: String, relativePath: String): String {
        val directory = resolveDirectory(rootId, relativePath)
        val entries = directory.listFiles() ?: throw IOException("This folder could not be read.")
        val root = resolveRoot(rootId)
        return JSONObject().put("root", JSONObject().put("id", root.id).put("name", root.name))
            .put("path", relativePath).put("readOnly", !canWrite(root, directory))
            .put("freeBytes", root.directory.usableSpace.coerceAtLeast(0L))
            .put("entries", JSONArray().apply {
                entries.asSequence().filter { visibleEntry(it.name) && !isSymbolicLink(it) && (it.isDirectory || it.isFile) }
                    .mapNotNull { file -> safeChild(root, directory, file.name)?.let { file to it } }
                    .sortedWith(compareBy({ !it.first.isDirectory }, { it.first.name.lowercase(Locale.ROOT) }))
                    .forEach { (file, safe) ->
                        val children = if (file.isDirectory) file.listFiles()?.filter { child ->
                            visibleEntry(child.name) && !isSymbolicLink(child) && (child.isDirectory || child.isFile) && safeChild(root, file, child.name) != null
                        }.orEmpty() else emptyList()
                        put(JSONObject().put("name", file.name).put("directory", file.isDirectory)
                            .put("type", if (file.isDirectory) "Folder" else file.extension.uppercase(Locale.ROOT).ifBlank { "File" })
                            .put("folderCount", if (file.isDirectory && file.canRead()) children.count(File::isDirectory) else JSONObject.NULL)
                            .put("fileCount", if (file.isDirectory && file.canRead()) children.count(File::isFile) else JSONObject.NULL)
                            .put("size", if (file.isFile) file.length().coerceAtLeast(0L) else JSONObject.NULL))
                    }
            }).toString()
    }

    data class PreviewFile(val file: File, val mimeType: String)

    /** Read-only preview, always resolved afresh beneath a permitted storage root. */
    fun previewFile(rootId: String, relativePath: String, name: String): PreviewFile {
        val parent = resolveDirectory(rootId, relativePath)
        validateName(name)
        val root = resolveRoot(rootId)
        val file = safeChild(root, parent, name) ?: throw IOException("This file is not available.")
        if (!file.isFile || !file.canRead()) throw IOException("The selected file cannot be read.")
        val type = when (file.extension.lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"
            "mpg", "mpeg" -> "video/mpeg"
            "3gp" -> "video/3gpp"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> throw IOException("Preview supports video and image files only.")
        }
        return PreviewFile(file, type)
    }

    /** Validates any ordinary visible file for download; never follows links or escapes a drive. */
    fun downloadFile(rootId: String, relativePath: String, name: String): File {
        val parent = resolveDirectory(rootId, relativePath)
        validateName(name)
        val root = resolveRoot(rootId)
        val file = safeChild(root, parent, name) ?: throw IOException("That file is not available.")
        if (!file.isFile || !file.canRead()) throw IOException("The selected file cannot be downloaded.")
        return file
    }

    fun createFolder(rootId: String, relativePath: String, name: String) {
        val parent = resolveDirectory(rootId, relativePath)
        requireWritable(resolveRoot(rootId), parent)
        validateName(name)
        val target = safeChild(resolveRoot(rootId), parent, name) ?: throw IOException("That folder name is not allowed.")
        if (target.exists() || isSymbolicLink(target)) throw FileExistsException("An item with that name already exists.")
        if (!target.mkdir()) throw IOException("The folder could not be created.")
    }

    fun delete(rootId: String, relativePath: String, name: String) {
        val parent = resolveDirectory(rootId, relativePath)
        requireWritable(resolveRoot(rootId), parent)
        validateName(name)
        val target = safeChild(resolveRoot(rootId), parent, name) ?: throw IOException("That item cannot be deleted.")
        if (isSymbolicLink(target)) throw IOException("Symbolic links cannot be deleted from the file manager.")
        if (!target.exists() || (!target.isFile && !target.isDirectory)) throw IOException("That item is no longer available.")
        if (target.isDirectory && target.list()?.isNotEmpty() == true) throw IOException("Only empty folders can be deleted.")
        if (!target.delete()) throw IOException("The item could not be deleted.")
    }

    fun upload(rootId: String, relativePath: String, name: String, length: Long, input: InputStream, keepGoing: () -> Boolean) {
        val directory = resolveDirectory(rootId, relativePath)
        requireWritable(resolveRoot(rootId), directory)
        cleanStaleUploads(directory)
        validateName(name)
        if (length <= 0L || length > MAX_FILE_BYTES) throw IOException("Files must be between 1 byte and 8 GiB.")
        val root = resolveRoot(rootId)
        val free = directory.usableSpace
        if (free < FILE_MANAGER_RESERVE_BYTES || length > free - FILE_MANAGER_RESERVE_BYTES) throw InsufficientSpaceException("Not enough free space on this drive. The existing files were left unchanged.")
        val destination = safeChild(root, directory, name) ?: throw IOException("That filename is not allowed.")
        if (destination.exists() || isSymbolicLink(destination)) throw FileExistsException("An item with that name already exists. Choose a different name.")
        val temporary = File(directory, ".tvplayer-upload-${UUID.randomUUID()}.tmp")
        val temporaryKey = temporary.toPath().toAbsolutePath().normalize().toString()
        activeUploads.add(temporaryKey)
        try {
            FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                Channels.newOutputStream(channel).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = length
                    while (remaining > 0) {
                        if (!keepGoing()) throw IOException("The upload session ended.")
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("The upload ended early.")
                        output.write(buffer, 0, count)
                        remaining -= count
                        if (directory.usableSpace < FILE_MANAGER_RESERVE_BYTES) throw InsufficientSpaceException("The drive ran low on free space. The incomplete upload was removed.")
                    }
                    output.flush()
                    channel.force(true)
                }
            }
            if (!keepGoing()) throw IOException("The upload session ended.")
            if (!resolveDirectory(rootId, relativePath).canonicalFile.sameFile(directory.canonicalFile)) throw IOException("The destination changed during upload.")
            if (destination.exists() || isSymbolicLink(destination)) throw FileExistsException("An item with that name already exists. Choose a different name.")
            try {
                Files.move(temporary.toPath(), destination.toPath())
            } catch (_: NioFileAlreadyExistsException) {
                throw FileExistsException("An item with that name already exists. Choose a different name.")
            }
        } finally {
            temporary.delete()
            activeUploads.remove(temporaryKey)
        }
    }

    private fun cleanStaleUploads(directory: File) {
        directory.listFiles()?.forEach { file ->
            if (!TEMP_UPLOAD_NAME.matches(file.name) || isSymbolicLink(file)) return@forEach
            val path = file.toPath()
            val key = runCatching { path.toAbsolutePath().normalize().toString() }.getOrNull() ?: return@forEach
            if (key in activeUploads || Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).not()) return@forEach
            file.delete()
        }
    }

    private fun resolveRoot(id: String): Root = roots().firstOrNull { it.id == id } ?: throw IOException("That drive is no longer mounted or accessible. Refresh the list.")

    private fun resolveDirectory(rootId: String, relativePath: String): File {
        if (relativePath.length > MAX_RELATIVE_PATH || relativePath.startsWith('/') || '\\' in relativePath || '\u0000' in relativePath) throw IOException("Invalid folder path.")
        val root = resolveRoot(rootId)
        val rootFile = root.directory.canonicalFile
        var target = rootFile
        if (relativePath.isNotEmpty()) {
            val segments = relativePath.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." || !visibleEntry(it) || isReserved(it) }) throw IOException("Invalid folder path.")
            segments.forEach { segment ->
                validateName(segment)
                target = File(target, segment)
                if (isSymbolicLink(target)) throw IOException("Symbolic links cannot be browsed from the file manager.")
            }
        }
        val canonical = target.canonicalFile
        if (!within(rootFile, canonical)) throw IOException("That folder is outside the selected drive.")
        if (!canonical.isDirectory || !canonical.canRead()) throw IOException("That folder is no longer available.")
        return canonical
    }

    private fun safeChild(root: Root, parent: File, name: String): File? {
        if (!visibleEntry(name) || isReserved(name)) return null
        val candidate = File(parent, name)
        if (isSymbolicLink(candidate)) return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { within(root.directory.canonicalFile, it) && it.parentFile == parent.canonicalFile }
    }

    private fun requireWritable(root: Root, directory: File) {
        if (!canWrite(root, directory)) throw IOException("This location is read-only or storage access is unavailable.")
    }

    private fun canWrite(root: Root, directory: File): Boolean = directory.canWrite() && when {
        rootProvider != null -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> context?.let {
            it.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                it.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } == true
        else -> false
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun validateName(name: String) {
        if (name.isBlank() || name.length > MAX_NAME_LENGTH || name == "." || name == ".." || name.startsWith('.') || name.any { it.isISOControl() || it == '/' || it == '\\' || it == ':' }) throw IOException("Use a filename without slashes, control characters, or a leading dot.")
        if (isReserved(name)) throw IOException("That system folder is not available here.")
    }

    private fun visibleEntry(name: String) = name.isNotEmpty() && !name.startsWith('.') && !name.any(Char::isISOControl)
    private fun isReserved(name: String) = name.equals("Android", true) || name.equals("data", true) || name.equals("obb", true)
    private fun within(root: File, file: File) = file.path == root.path || file.path.startsWith(root.path + File.separator)
    private fun File.sameFile(other: File) = path == other.path

    private fun hasStorageReadAccess(): Boolean = rootProvider != null || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context?.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun StorageVolume.directoryForFileManager(): File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { directory }.getOrNull()
    } else uuid?.let { File("/storage", it) }

    class FileExistsException(message: String) : IOException(message)
    class InsufficientSpaceException(message: String) : IOException(message)

    companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
        private const val FILE_MANAGER_RESERVE_BYTES = 16L * 1024 * 1024
        private val TEMP_UPLOAD_NAME = Regex("\\.tvplayer-upload-[0-9a-fA-F-]{36}\\.tmp")
        private const val MAX_RELATIVE_PATH = 2048
        private const val MAX_NAME_LENGTH = 180
    }
}

internal object FileManagerPage {
    val html = """
        <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>File Manager · TV Player</title>
        <style>
         :root{color-scheme:dark;font:15px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;background:#08111f;color:#eef6ff;--panel:#101d2d;--line:#223952;--muted:#9dafc5;--accent:#59c5ff;--gold:#65baff}
         *{box-sizing:border-box}body{margin:0;min-height:100vh;background:#08111f}button,input{font:inherit;color:inherit}button{border:1px solid #344d6c;border-radius:9px;background:#14273c;padding:8px 11px;cursor:pointer}button:hover:not(:disabled){background:#1b3756}button:disabled{opacity:.42;cursor:default}button:focus-visible,input:focus-visible{outline:3px solid #81d6ff;outline-offset:2px}
         header{height:68px;display:flex;align-items:center;gap:12px;padding:0 18px;border-bottom:1px solid var(--line);background:#0a192e}header a{color:#b6c9e9;text-decoration:none}header strong{font-size:21px}header #connection{margin-left:auto;color:var(--muted);font-size:13px}.brand-icon{color:#56c7ff;display:inline-grid;place-items:center}.brand-icon svg{width:35px;height:35px}
         .layout{height:calc(100vh - 68px);min-height:400px;display:grid;grid-template-columns:300px minmax(0,1fr)}aside{min-width:0;border-right:1px solid var(--line);background:#0b1a2c;overflow:auto;padding:16px 10px;scrollbar-color:#406287 transparent;scrollbar-width:thin}.side-title{padding:7px 10px 16px;color:#9fb7d4;font-size:12px;font-weight:700;letter-spacing:.15em;text-transform:uppercase}.tree{display:block}.tree-row{display:flex;align-items:center;min-width:0;gap:2px}.tree .tree-select{flex:1;min-width:0;min-height:38px;display:flex;align-items:center;gap:9px;text-align:left;border:0;background:transparent;padding:8px;border-radius:8px;font-size:13px}.tree .tree-select:hover{background:#173554}.tree .tree-select.active{background:#14558c;color:#fff}.tree .tree-label{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.tree .icon{display:inline-flex;flex:none;color:#69bbff}.tree .icon svg{width:20px;height:20px}.tree .tree-toggle{display:grid;place-items:center;flex:0 0 25px;width:25px;min-width:25px;height:34px;border:0;background:transparent;padding:0;color:#a9c8e8}.tree .tree-toggle:hover{background:#24415e}.tree .tree-toggle svg{width:14px;height:14px}.tree .tree-toggle[aria-expanded=true] svg{transform:rotate(90deg)}.tree .tree-branch{margin:0 0 3px 12px;padding-left:8px;border-left:1px solid #2a445e}.tree .tree-drive{margin-bottom:4px}.tree .tree-drive>.tree-row .tree-select{font-weight:700}.sidebar-shade{display:none}
         .workspace{min-width:0;display:flex;flex-direction:column}.toolbar{display:flex;align-items:center;gap:7px;min-height:57px;padding:9px 14px;border-bottom:1px solid var(--line);flex-wrap:wrap}.toolbar .spacer{flex:1}.toolbar button{min-height:36px;padding:7px 10px;font-size:13px}.toolbar #upload{border-color:#277cad;background:#155786}.toolbar #upload:hover{background:#1c71a9}.toolbar .icon-button{width:37px;padding:6px}.toolbar .icon-button svg{width:19px;height:19px}.breadcrumb{display:flex;align-items:center;gap:8px;min-height:46px;padding:7px 18px;border-bottom:1px solid var(--line);color:var(--muted);overflow:auto;white-space:nowrap}.breadcrumb button{border:0;background:none;padding:4px;color:#dbe6f1}.breadcrumb .sep{color:#61748c}.storage-info{padding:7px 18px;color:var(--muted);font-size:12px;border-bottom:1px solid #1b2a3d}
         .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(124px,1fr));gap:10px;padding:16px;overflow:auto;align-content:start;flex:1}.item{min-width:0;min-height:120px;border:1px solid #1b334e;border-radius:11px;background:#0c1c30;padding:12px 8px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;text-align:center;touch-action:manipulation}.item:hover{background:#153451;border-color:#446c95}.item.selected{border-color:#5bc4ff;background:#173d62}.item .glyph{display:inline-grid;place-items:center;width:42px;height:42px;color:#64baff}.item .glyph svg{width:39px;height:39px}.item .glyph.video{color:#ac88ff}.item .glyph.audio{color:#fa74aa}.item .glyph.image{color:#55e6ba}.item .glyph.subtitle{color:#e5b967}.item .glyph.file{color:#a6bad5}.item .name{width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.item .meta{color:var(--muted);font-size:11px}.grid.list{display:flex;flex-direction:column;gap:2px}.grid.list .item{min-height:50px;width:100%;flex-direction:row;justify-content:flex-start;text-align:left;padding:7px 12px;gap:12px}.grid.list .glyph{width:29px;height:29px;flex:none}.grid.list .glyph svg{width:26px;height:26px}.grid.list .name{flex:1}.grid.list .meta{min-width:82px;text-align:right}.empty{grid-column:1/-1;padding:36px;text-align:center;color:var(--muted)}footer{min-height:35px;border-top:1px solid var(--line);padding:8px 16px;color:var(--muted);font-size:12px}
        #status{position:fixed;bottom:48px;left:50%;transform:translateX(-50%);max-width:min(90vw,680px);background:#1b2b40;border:1px solid #40536c;border-radius:9px;padding:10px 14px;box-shadow:0 8px 30px #0009;display:none;z-index:4}#uploadInput{display:none}.readonly{color:#e3b987}
         @media(max-width:700px){.layout{grid-template-columns:minmax(0,1fr)}aside{position:fixed;z-index:4;inset:68px auto 0 0;width:min(85vw,310px);box-shadow:12px 0 35px #0008;transform:translateX(-105%);transition:transform .18s}.layout.sidebar-open aside{transform:translateX(0)}.layout.sidebar-open .sidebar-shade{display:block;position:fixed;z-index:3;inset:68px 0 0;background:#000a;border:0;border-radius:0}.toolbar{gap:5px;padding:8px}.toolbar button{font-size:12px;padding:7px}.toolbar .hide-small{display:none}.grid{grid-template-columns:repeat(auto-fill,minmax(100px,1fr));padding:10px;gap:5px}.item{min-height:104px}.grid.list .item{min-height:45px}header{padding:0 12px}header strong{font-size:17px}header #connection{font-size:11px}.brand-icon svg{width:26px;height:26px}}@media(min-width:701px){.toolbar #sidebar{display:none}}
        .preview-overlay[hidden]{display:none!important}.preview-overlay{position:fixed;inset:0;z-index:10;background:#020912ed;display:grid;place-items:center;padding:20px}.preview-card{background:#0c1e34;border:1px solid #335478;border-radius:16px;width:min(94vw,1000px);max-height:94vh;display:flex;flex-direction:column;gap:12px;padding:15px;box-shadow:0 25px 90px #000c}.preview-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.preview-heading strong{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:16px}.preview-heading button{flex:none}.preview-body{min-height:100px;display:grid;place-items:center;overflow:auto}.preview-body video,.preview-body img{max-width:100%;max-height:75vh;object-fit:contain;border-radius:8px;background:#030a14}.preview-body video{width:100%;max-height:72vh}.preview-note{color:var(--muted);font-size:12px}
        .item-card{position:relative;min-width:0}.item-card .item{width:100%;height:100%;padding-right:25px}.item-more{position:absolute;right:5px;top:5px;z-index:1;display:grid;place-items:center;width:32px;height:32px;padding:0;border:1px solid #2e557d;background:#183653db;border-radius:9px;color:#e6f4ff;font-size:22px;line-height:1}.item-more:hover{background:#23608b}.grid.list .item-card{width:100%}.grid.list .item-more{top:7px;right:8px}.grid.list .item-card .item{padding-right:56px}.selection-actions{padding:9px 16px;display:flex;align-items:center;flex-wrap:wrap;gap:9px;border-bottom:1px solid var(--line);background:#11243a}.selection-actions[hidden]{display:none}.selection-actions strong{max-width:270px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:13px}.selection-actions .action-buttons{display:flex;gap:6px;flex-wrap:wrap}.selection-actions .action-buttons button{font-size:12px;padding:7px 10px}.selection-actions button:disabled{opacity:.45}.context-menu{position:fixed;z-index:30;min-width:204px;max-width:min(90vw,300px);padding:6px;background:#142b44;border:1px solid #4b7298;border-radius:12px;box-shadow:0 14px 50px #000c}.context-menu[hidden]{display:none}.context-menu .menu-title{padding:7px 10px;color:#a8bdd8;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.context-menu button{width:100%;display:flex;align-items:center;gap:10px;padding:10px 11px;background:transparent;border:0;border-radius:7px;text-align:left;font-size:14px}.context-menu button:hover:not(:disabled),.context-menu button:focus-visible{background:#22527c}.context-menu button.danger{color:#ffb0ad}.context-menu button:disabled{opacity:.38}.tree-more{flex:0 0 28px;border:0;background:transparent;padding:0;width:28px;height:32px;color:#b8d7ff;font-size:20px}.tree-more:hover{background:#255073}@media(max-width:700px){.selection-actions{padding:8px 10px}.selection-actions strong{max-width:100%}.item-more{width:38px;height:38px}}
        </style>
         <header><span id="brandIcon" class="brand-icon" aria-hidden="true"></span><a href="/" aria-label="Back to Send to TV">‹ Send to TV</a><strong>File Manager</strong><span id="connection">Connecting…</span></header>
         <div class="layout" id="layout"><aside id="folderSidebar"><div class="side-title">Storage</div><nav id="tree" class="tree" aria-label="Storage folders"></nav></aside><button id="sidebarShade" type="button" class="sidebar-shade" aria-label="Close sidebar"></button><section class="workspace" aria-label="File browser">
         <div class="toolbar"><button id="sidebar" class="icon-button" aria-label="Show folder tree" aria-controls="folderSidebar" aria-expanded="false">☰</button><button id="back" class="icon-button" aria-label="Go back">←</button><button id="up" class="icon-button" aria-label="Go up">↑</button><button id="refresh" class="icon-button" aria-label="Refresh">⟳</button><button id="newFolder">＋ New folder</button><button id="delete" disabled>Delete</button><button id="download" disabled>↓ Download</button><button id="cancelUpload" hidden>Cancel upload</button><span class="spacer"></span><input id="uploadInput" type="file"><button id="upload">Upload file</button><button id="gridMode" class="icon-button" aria-label="Grid view" aria-pressed="true">▦</button><button id="listMode" class="icon-button" aria-label="List view" aria-pressed="false">☷</button></div>
        <nav id="breadcrumb" class="breadcrumb" aria-label="Current folder"></nav><div id="storageInfo" class="storage-info"></div><div id="selectionActions" class="selection-actions" hidden><strong id="selectionName"></strong><div id="selectionButtons" class="action-buttons"></div></div><div id="items" class="grid" role="list"></div><footer id="footer">Waiting for TV approval…</footer></section></div><div id="status" role="status" aria-live="polite"></div><div id="contextMenu" class="context-menu" role="menu" aria-label="File and folder actions" hidden></div><div id="previewOverlay" class="preview-overlay" hidden role="dialog" aria-modal="true" aria-labelledby="previewTitle"><div class="preview-card"><div class="preview-heading"><strong id="previewTitle">Preview</strong><button id="previewDownload" type="button">↓ Download</button><button id="previewClose" type="button">✕ Close</button></div><div id="previewBody" class="preview-body"></div><div id="previewNote" class="preview-note"></div></div></div>
        <script>
        history.replaceState(null,'',location.pathname);let token=null,roots=[],root=null,path=[],selected=null,selectedIsDirectory=false,selectedEntry=null,menuTarget=null,uploadTarget=null,previewTarget=null,view='grid',busy=false,uploadRequest=null,treeExpanded=new Set(),treeCache={};
        const $=id=>document.getElementById(id),items=$('items'),footer=$('footer');
        function message(text){const box=$('status');box.textContent=text;box.style.display='block';clearTimeout(message.timer);message.timer=setTimeout(()=>box.style.display='none',5000)}
        const trustKey='tv-player.trusted-browser.v1';
        function savedBrowser(){try{return localStorage.getItem(trustKey)||''}catch(_){return ''}}
        function saveBrowser(value){try{if(value)localStorage.setItem(trustKey,value);else localStorage.removeItem(trustKey)}catch(_){}}
        async function pair(){if(token)return;const saved=savedBrowser();footer.textContent=saved?'Reconnecting trusted browser…':'Waiting for approval on the TV…';$('connection').textContent=saved?'Reconnecting…':'Approval needed';const headers=saved?{'X-Trusted-Browser':saved}:{};const res=await fetch('/api/pair',{method:'POST',headers,cache:'no-store'});if(!res.ok)throw Error(await res.text());const data=await res.json();token=data.token;if(data.trustedToken)saveBrowser(data.trustedToken);else if(saved&&!data.trusted)saveBrowser('');$('connection').textContent=data.trusted?'Trusted browser':'Connected'}
        async function api(url,options){await pair();options=options||{};options.cache='no-store';options.headers=Object.assign({},options.headers||{}, {'X-Pairing-Token':token});const res=await fetch(url,options);if(res.status===401){token=null;$('connection').textContent='Approval needed';throw Error('TV connection expired — reconnect and approve access on the TV.')}if(!res.ok)throw Error(await res.text());return options.method==='POST'?res.text():res.json()}
        function queryUrl(endpoint,rootId,relative){return endpoint+'?root='+encodeURIComponent(rootId)+'&path='+encodeURIComponent(relative||'')}
        function relative(){return path.join('/')}
         function formatSize(size){if(size===null||size===undefined)return '';if(size<1024)return size+' B';const units=['KiB','MiB','GiB','TiB'];let n=size/1024,i=0;while(n>=1024&&i<units.length-1){n/=1024;i++}return n.toFixed(n>=10?0:1)+' '+units[i]}
function svg(kind){const paths={
tv:'<rect x="3" y="5" width="18" height="14" rx="3"/><path d="m9 10 6 2-6 2z"/><path d="m8 2 4 3 4-3"/>',
drive:'<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M5 15h14M8 17h.01M11 17h.01"/>',
folder:'<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
video:'<rect x="3" y="3" width="18" height="18" rx="3"/><path d="M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4"/><path d="m10 9 5 3-5 3z"/>',
image:'<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="8" cy="8" r="1.5"/><path d="m4 18 6-6 4 4 3-3 4 4"/>',
audio:'<path d="M9 18V5l11-2v13"/><ellipse cx="6" cy="18" rx="3" ry="2"/><ellipse cx="17" cy="16" rx="3" ry="2"/>',
subtitle:'<rect x="2" y="5" width="20" height="14" rx="3"/><path d="M6 11h5M6 15h12M14 11h4"/>',
file:'<path d="M6 2h8l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/><path d="M14 2v6h5M8 13h8M8 17h6"/>',
chevron:'<path d="m9 5 7 7-7 7"/>',
menu:'<path d="M4 6h16M4 12h16M4 18h16"/>',
back:'<path d="m14 5-7 7 7 7"/>',up:'<path d="m5 14 7-7 7 7"/>',
refresh:'<path d="M20 7v5h-5M4 17v-5h5"/><path d="M5 9a8 8 0 0 1 14-2l1 5M4 12l1 5a8 8 0 0 0 14-2"/>',
grid:'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
list:'<path d="M9 6h12M9 12h12M9 18h12M4 6h.01M4 12h.01M4 18h.01"/>'};
return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+(paths[kind]||paths.file)+'</svg>'}
function iconNode(kind){const node=document.createElement('span');node.className='icon';node.innerHTML=svg(kind);return node}
function textNode(value){const node=document.createElement('span');node.className='tree-label';node.textContent=value;return node}
function fileKind(entry){if(entry.directory)return 'folder';const ext=entry.name.toLowerCase().split('.').pop();if(['mp4','mkv','mov','avi','webm','m4v','ts','m2ts','mpg','mpeg','3gp'].includes(ext))return 'video';if(['mp3','wav','flac','aac','m4a','ogg'].includes(ext))return 'audio';if(['png','jpg','jpeg','gif','webp','bmp'].includes(ext))return 'image';if(['srt','vtt','ass','ssa'].includes(ext))return 'subtitle';return 'file'}
function closeSidebar(){$('layout').classList.remove('sidebar-open');$('sidebar').setAttribute('aria-expanded','false');$('sidebar').setAttribute('aria-label','Show folder tree')}
function targetFor(entry,rootId,parentPath,isDrive){return {entry,rootId,parentPath,isDrive:Boolean(isDrive)}}
function selectedActionItems(target){if(!target)return [];const drive=roots.find(r=>r.id===target.rootId),writable=drive&&!drive.readOnly,entry=target.entry,k=fileKind(entry),empty=entry.folderCount===0&&entry.fileCount===0;
if(target.isDrive)return [{id:'open',label:'Open drive'},{id:'create',label:'New folder',disabled:!writable},{id:'upload',label:'Upload here',disabled:!writable}];
if(entry.directory)return [{id:'open',label:'Open folder'},{id:'create',label:'New folder inside',disabled:!writable},{id:'upload',label:'Upload here',disabled:!writable},{id:'delete',label:'Delete folder',disabled:!writable||entry.folderCount!==null&&entry.fileCount!==null&&!empty,danger:true}];
const result=[];if(k==='video')result.push({id:'preview',label:'Play video'});else if(k==='image')result.push({id:'preview',label:'Preview image'});result.push({id:'download',label:'Download file'});result.push({id:'delete',label:'Delete file',disabled:!writable,danger:true});return result}
function hideContextMenu(){const menu=$('contextMenu');menu.hidden=true;menu.replaceChildren();menuTarget=null}
function selectionClear(){selected=null;selectedIsDirectory=false;selectedEntry=null;$('selectionActions').hidden=true;$('selectionButtons').replaceChildren();document.querySelectorAll('.item.selected').forEach(b=>b.classList.remove('selected'));hideContextMenu()}
function actionButton(action,target,inMenu){const button=document.createElement('button');button.type='button';button.textContent=action.label;button.disabled=Boolean(action.disabled)||busy;button.className=action.danger?'danger':'';if(inMenu)button.setAttribute('role','menuitem');button.onclick=()=>{hideContextMenu();Promise.resolve().then(()=>executeAction(action.id,target)).catch(showError)};return button}
function renderSelectionActions(){const pane=$('selectionActions'),buttons=$('selectionButtons');buttons.replaceChildren();pane.hidden=!selectedEntry;if(!selectedEntry)return;$('selectionName').textContent=selectedEntry.entry.name;for(const action of selectedActionItems(selectedEntry))buttons.append(actionButton(action,selectedEntry,false))}
function selectTarget(target,tile){selectedEntry=target;selected=target.isDrive?null:target.entry.name;selectedIsDirectory=target.entry.directory;document.querySelectorAll('.item.selected').forEach(b=>b.classList.remove('selected'));if(tile&&tile.classList.contains('item'))tile.classList.add('selected');renderSelectionActions();setBusy(busy)}
function showContextMenu(target,x,y){if(busy)return;menuTarget=target;const menu=$('contextMenu');menu.replaceChildren();const heading=document.createElement('div');heading.className='menu-title';heading.textContent=target.entry.name;menu.append(heading);const actions=selectedActionItems(target);for(const action of actions)menu.append(actionButton(action,target,true));menu.hidden=false;const width=menu.offsetWidth,height=menu.offsetHeight;menu.style.left=Math.max(8,Math.min(x,window.innerWidth-width-8))+'px';menu.style.top=Math.max(8,Math.min(y,window.innerHeight-height-8))+'px';const first=menu.querySelector('button:not(:disabled)');if(first)first.focus()}
function contextForElement(event,target,element){event.preventDefault();event.stopPropagation();selectTarget(target,element);showContextMenu(target,event.clientX,event.clientY)}
function openContextAtButton(target,button,tile){selectTarget(target,tile);const r=button.getBoundingClientRect();showContextMenu(target,r.left,r.bottom+5)}
function folderPath(target){return target.isDrive?'':target.parentPath?target.parentPath+'/'+target.entry.name:target.entry.name}
async function executeAction(action,target){if(busy||!target)return;const drive=roots.find(r=>r.id===target.rootId);if(!drive)throw Error('The selected drive is not available. Refresh the file manager.');
if(action==='open'){root=drive;path=(target.isDrive?'':folderPath(target)).split('/').filter(Boolean);selectionClear();closeSidebar();await loadDirectory();return}
if(action==='preview'){previewTarget=target;await showPreview(target.entry,target.rootId,target.parentPath);return}
if(action==='download'){await downloadTarget(target);return}
if(action==='delete'){if(drive.readOnly)throw Error('This drive is read-only.');const label=target.entry.directory?'folder':'file';if(!confirm('Permanently delete '+label+' "'+target.entry.name+'"? This cannot be undone.' ))return;await api('/api/files/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({root:target.rootId,path:target.parentPath,name:target.entry.name})});message('Deleted '+target.entry.name);treeCache={};selectionClear();await loadDirectory();return}
if(action==='create'){if(drive.readOnly)throw Error('This drive is read-only.');const name=prompt('New folder name');if(name===null)return;if(!name.trim())throw Error('Enter a folder name.');await api('/api/files/create',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({root:target.rootId,path:folderPath(target),name})});message('Folder created.');treeCache={};await loadDirectory();return}
if(action==='upload'){if(drive.readOnly)throw Error('This drive is read-only.');uploadTarget={rootId:target.rootId,parentPath:folderPath(target)};$('uploadInput').click();return}}

async function downloadTarget(target){if(!target||target.entry.directory)throw Error('Select a file to download.');const name=target.entry.name;const result=await api(queryUrl('/api/files/download-ticket',target.rootId,target.parentPath)+'&name='+encodeURIComponent(name));const link=document.createElement('a');link.href=result.url;link.download=name;link.referrerPolicy='no-referrer';document.body.appendChild(link);link.click();link.remove();message('Download started: '+name)}
async function downloadSelected(){if(!selectedEntry||selectedEntry.entry.directory)throw Error('Select a file to download.');await downloadTarget(selectedEntry)}
function closePreview(){previewTarget=null;const body=$('previewBody');for(const video of body.querySelectorAll('video')){video.pause();video.removeAttribute('src');video.load()}body.replaceChildren();$('previewOverlay').hidden=true;$('previewNote').textContent=''}
async function showPreview(entry,rootId,parentPath){if(!root)return;const kind=fileKind(entry);if(kind!=='video'&&kind!=='image')return;const result=await api(queryUrl('/api/files/preview-ticket',rootId||root.id,parentPath===undefined?relative():parentPath)+'&name='+encodeURIComponent(entry.name));const body=$('previewBody');body.replaceChildren();$('previewTitle').textContent=entry.name;$('previewNote').textContent=kind==='video'?'Playback depends on your browser supporting this video format.':'Click Close or press Escape to return to the file manager.';const media=document.createElement(kind==='video'?'video':'img');if(kind==='video'){media.controls=true;media.playsInline=true;media.preload='metadata';media.onerror=()=>{$('previewNote').textContent='Your browser cannot play this video format or the connection expired. Try MP4 (H.264/AAC).'}}else{media.alt=entry.name;media.onerror=()=>{$('previewNote').textContent='The image could not be displayed. Check its format or reconnect to the TV.'}}media.src=result.url;body.append(media);$('previewOverlay').hidden=false;$('previewClose').focus()}
for(const [id,kind] of [['brandIcon','tv'],['sidebar','menu'],['back','back'],['up','up'],['refresh','refresh'],['gridMode','grid'],['listMode','list']])$(id).innerHTML=svg(kind);
        function setBusy(value){busy=value;const item=selectedEntry&&selectedEntry.entry;const drive=selectedEntry&&roots.find(r=>r.id===selectedEntry.rootId);const nonempty=item&&item.directory&&item.folderCount!==null&&item.fileCount!==null&&(item.folderCount>0||item.fileCount>0);$('newFolder').disabled=value||!root||root.readOnly;$('upload').disabled=value||!root||root.readOnly;$('delete').disabled=value||!item||selectedEntry.isDrive||!drive||drive.readOnly||nonempty;$('download').disabled=value||!item||item.directory;$('back').disabled=value||path.length===0;$('up').disabled=value||path.length===0;renderSelectionActions()}
        async function loadRoots(){await pair();const result=await api('/api/files/roots');const currentId=root&&root.id;roots=result.roots;root=roots.find(d=>d.id===currentId)||roots[0]||null;if(!root){path=[];selectionClear();treeCache={};renderTree();items.replaceChildren();const empty=document.createElement('div');empty.className='empty';empty.textContent=result.storageAccess?'No accessible removable drives or shared Movies folder were found.':'USB browsing needs all-files access. Enable it in TV Player, then refresh this page.';items.appendChild(empty);$('storageInfo').textContent='';footer.textContent='0 items';setBusy(false);return}if(root.id!==currentId)path=[];await loadDirectory()}
        function renderTree(){const tree=$('tree');tree.replaceChildren();for(const drive of roots){const block=document.createElement('div'),row=document.createElement('div'),button=document.createElement('button'),more=document.createElement('button');block.className='tree-drive';row.className='tree-row';button.type='button';button.className='tree-select drive'+(root&&root.id===drive.id&&path.length===0?' active':'');button.append(iconNode('drive'),textNode(drive.name));button.title=drive.name;const target=targetFor({name:drive.name,directory:true,folderCount:null,fileCount:null},drive.id,'',true);button.onclick=()=>{root=drive;path=[];selectionClear();closeSidebar();loadDirectory().catch(showError)};row.oncontextmenu=e=>contextForElement(e,target,button);more.type='button';more.className='tree-more';more.textContent='⋮';more.title='Actions for '+drive.name;more.setAttribute('aria-label','Actions for '+drive.name);more.onclick=()=>openContextAtButton(target,more,button);row.append(button,more);block.append(row);if(root&&root.id===drive.id)drawTreeFolders(block,'',0);tree.append(block)}}
        function drawTreeFolders(parent,relativePath,depth){const names=treeCache[root.id+':'+relativePath]||[];if(!names.length)return;const group=document.createElement('div');group.className='tree-branch';for(const name of names){const folderPath=relativePath?relativePath+'/'+name:name,key=root.id+':'+folderPath,open=treeExpanded.has(key),branch=document.createElement('div'),row=document.createElement('div'),toggle=document.createElement('button'),select=document.createElement('button'),more=document.createElement('button');row.className='tree-row';toggle.type='button';toggle.className='tree-toggle';toggle.innerHTML=svg('chevron');toggle.setAttribute('aria-expanded',String(open));toggle.setAttribute('aria-label',(open?'Collapse ':'Expand ')+name);toggle.title=(open?'Collapse ':'Expand ')+name;toggle.onclick=async()=>{if(treeExpanded.has(key)){treeExpanded.delete(key);renderTree();return}try{if(!Object.prototype.hasOwnProperty.call(treeCache,key)){const data=await api(queryUrl('/api/files/list',root.id,folderPath));treeCache[key]=data.entries.filter(entry=>entry.directory).map(entry=>entry.name)}treeExpanded.add(key);renderTree()}catch(error){showError(error)}};select.type='button';select.className='tree-select'+(root&&relative()===folderPath?' active':'');select.append(iconNode('folder'),textNode(name));select.title=folderPath;select.onclick=()=>{path=folderPath.split('/');selectionClear();closeSidebar();loadDirectory().catch(showError)};const target=targetFor({name,directory:true,folderCount:null,fileCount:null},root.id,relativePath,false);row.oncontextmenu=e=>contextForElement(e,target,select);more.type='button';more.className='tree-more';more.textContent='⋮';more.title='Actions for '+name;more.setAttribute('aria-label','Actions for '+name);more.onclick=()=>openContextAtButton(target,more,select);row.append(toggle,select,more);branch.append(row);if(open)drawTreeFolders(branch,folderPath,depth+1);group.append(branch)}parent.append(group)}
        function showError(error){message(error.message||'Could not complete that action.');footer.textContent=error.message||'Could not complete that action.'}
        async function loadDirectory(){if(!root)return;selectionClear();setBusy(true);try{const data=await api(queryUrl('/api/files/list',root.id,relative()));root.readOnly=data.readOnly;root.freeBytes=data.freeBytes;treeCache[root.id+':'+relative()]=data.entries.filter(entry=>entry.directory).map(entry=>entry.name);for(let index=1;index<=path.length;index++)treeExpanded.add(root.id+':'+path.slice(0,index).join('/'));renderBreadcrumb();renderItems(data.entries);renderTree();$('storageInfo').textContent=(root.removable?'Removable drive':'Shared Movies')+' · '+formatSize(data.freeBytes)+' free'+(root.readOnly?' · Read only':'');$('storageInfo').className='storage-info'+(root.readOnly?' readonly':'');const folders=data.entries.filter(entry=>entry.directory).length,files=data.entries.length-folders;footer.textContent=folders+' folder'+(folders===1?'':'s')+' · '+files+' file'+(files===1?'':'s')+(root.readOnly?' · Read only':'');setBusy(false)}catch(error){setBusy(false);showError(error)}}
        function renderBreadcrumb(){const bar=$('breadcrumb');bar.replaceChildren();if(!root)return;const segments=[root.name].concat(path);segments.forEach((name,index)=>{if(index) {const sep=document.createElement('span');sep.className='sep';sep.textContent='›';bar.appendChild(sep)}const button=document.createElement('button');button.type='button';button.textContent=name;button.onclick=()=>{path=path.slice(0,index);loadDirectory().catch(showError)};bar.appendChild(button)})}
        function renderItems(entries){items.replaceChildren();items.className='grid'+(view==='list'?' list':'');if(!entries.length){const empty=document.createElement('div');empty.className='empty';empty.textContent='This folder is empty';items.appendChild(empty);return}for(const entry of entries){const wrapper=document.createElement('div'),button=document.createElement('button'),more=document.createElement('button');wrapper.className='item-card';wrapper.setAttribute('role','listitem');button.type='button';button.className='item';button.title=entry.name;const kind=fileKind(entry),glyph=document.createElement('span');glyph.className='glyph '+kind;glyph.innerHTML=svg(kind);const name=document.createElement('span');name.className='name';name.textContent=entry.name;name.title=entry.name;const meta=document.createElement('span');meta.className='meta';meta.textContent=entry.directory?'Folder · '+(entry.folderCount===null?'—':entry.folderCount+' folders, '+entry.fileCount+' files'):entry.type+(entry.size===null?'':' · '+formatSize(entry.size));button.append(glyph,name,meta);const target=targetFor(entry,root.id,relative(),false);let hold=null,held=false,pressX=0,pressY=0;const openItem=()=>{if(entry.directory)executeAction('open',target).catch(showError);else if(kind==='video'||kind==='image')executeAction('preview',target).catch(showError)};button.onclick=event=>{if(held){held=false;event.preventDefault();return}selectTarget(target,button);if(event.detail===0)openItem()};button.ondblclick=()=>{selectTarget(target,button);openItem()};wrapper.oncontextmenu=event=>contextForElement(event,target,button);button.addEventListener('pointerdown',event=>{if(event.pointerType!=='touch'&&event.pointerType!=='pen')return;pressX=event.clientX;pressY=event.clientY;held=false;clearTimeout(hold);hold=setTimeout(()=>{held=true;selectTarget(target,button);showContextMenu(target,pressX,pressY)},550)});button.addEventListener('pointermove',event=>{if(Math.abs(event.clientX-pressX)>12||Math.abs(event.clientY-pressY)>12)clearTimeout(hold)});for(const e of ['pointerup','pointercancel','pointerleave'])button.addEventListener(e,()=>clearTimeout(hold));more.type='button';more.className='item-more';more.textContent='⋮';more.title='Actions for '+entry.name;more.setAttribute('aria-label','Actions for '+entry.name);more.onclick=()=>openContextAtButton(target,more,button);wrapper.append(button,more);items.append(wrapper)}}
         $('sidebar').onclick=()=>{const opened=$('layout').classList.toggle('sidebar-open');$('sidebar').setAttribute('aria-expanded',String(opened));$('sidebar').setAttribute('aria-label',opened?'Hide folder tree':'Show folder tree')};$('sidebarShade').onclick=closeSidebar;$('download').onclick=()=>downloadSelected().catch(showError);$('previewDownload').onclick=()=>downloadTarget(previewTarget||selectedEntry).catch(showError);$('previewClose').onclick=closePreview;$('previewOverlay').onclick=event=>{if(event.target===$('previewOverlay'))closePreview()};document.addEventListener('keydown',event=>{if(event.key==='Escape'){if(!$('contextMenu').hidden){hideContextMenu();event.preventDefault()}else if(!$('previewOverlay').hidden)closePreview();else closeSidebar()}else if((event.key==='F10'&&event.shiftKey)||event.key==='ContextMenu'){if(selectedEntry){event.preventDefault();const elem=document.activeElement;const rect=elem.getBoundingClientRect();showContextMenu(selectedEntry,rect.left,rect.bottom+5)}}});document.addEventListener('pointerdown',event=>{if(!$('contextMenu').hidden&&!$('contextMenu').contains(event.target))hideContextMenu()});$('back').onclick=()=>{if(path.length){path.pop();loadDirectory().catch(showError)}};$('up').onclick=$('back').onclick;$('refresh').onclick=()=>{loadRoots().catch(showError)};$('gridMode').onclick=()=>{view='grid';$('gridMode').setAttribute('aria-pressed','true');$('listMode').setAttribute('aria-pressed','false');loadDirectory().catch(showError)};$('listMode').onclick=()=>{view='list';$('gridMode').setAttribute('aria-pressed','false');$('listMode').setAttribute('aria-pressed','true');loadDirectory().catch(showError)};
        $('newFolder').onclick=async()=>{const name=prompt('New folder name');if(name===null)return;if(!name.trim()){message('Enter a folder name.');return}setBusy(true);try{await api('/api/files/create',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({root:root.id,path:relative(),name:name})});message('Folder created.');await loadDirectory()}catch(error){showError(error);setBusy(false)}};
        $('delete').onclick=()=>{if(selectedEntry)executeAction('delete',selectedEntry).catch(showError)};
        $('upload').onclick=()=>{if(!busy){uploadTarget=null;$('uploadInput').click()}};$('cancelUpload').onclick=()=>{if(uploadRequest)uploadRequest.abort()};$('uploadInput').onchange=()=>{const destination=uploadTarget||{rootId:root&&root.id,parentPath:relative()};uploadTarget=null;const file=$('uploadInput').files[0];if(!file)return;if(file.size>8*1024*1024*1024){message('Files are limited to 8 GiB.');$('uploadInput').value='';return}const url='/api/files/upload?root='+encodeURIComponent(destination.rootId)+'&path='+encodeURIComponent(destination.parentPath)+'&name='+encodeURIComponent(file.name);setBusy(true);$('cancelUpload').hidden=false;pair().then(()=>{const request=new XMLHttpRequest();uploadRequest=request;request.open('POST',url);request.setRequestHeader('X-Pairing-Token',token);request.setRequestHeader('Content-Type','application/octet-stream');request.upload.onprogress=event=>{if(event.lengthComputable)footer.textContent='Uploading '+file.name+' · '+Math.floor(event.loaded/event.total*100)+'%'};request.onload=()=>{if(uploadRequest===request)uploadRequest=null;$('cancelUpload').hidden=true;if(request.status>=200&&request.status<300){$('uploadInput').value='';message(file.name+' added.');loadDirectory().catch(showError)}else if(request.status===401){token=null;$('connection').textContent='Approval needed';setBusy(false);showError(new Error('TV connection expired — approve access on the TV and retry the upload.'))}else{setBusy(false);showError(new Error(request.responseText||'Upload failed'))}};request.onerror=()=>{if(uploadRequest===request)uploadRequest=null;token=null;$('connection').textContent='Reconnect needed';$('cancelUpload').hidden=true;setBusy(false);showError(new Error('Upload connection lost. Keep TV Player open, reconnect and try again.'))};request.onabort=()=>{if(uploadRequest===request)uploadRequest=null;$('cancelUpload').hidden=true;setBusy(false);message('Upload cancelled. You can select the file again.');loadDirectory().catch(showError)};request.send(file)}).catch(error=>{$('cancelUpload').hidden=true;setBusy(false);showError(error)})};
        loadRoots().catch(showError);
        </script></html>
    """.trimIndent()
}
