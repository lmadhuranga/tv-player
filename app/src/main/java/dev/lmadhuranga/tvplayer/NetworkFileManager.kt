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
        :root{color-scheme:dark;font:15px/1.4 system-ui,-apple-system,"Segoe UI",sans-serif;background:#08111f;color:#edf3fa;--panel:#101d2d;--line:#25354a;--muted:#9aabbd;--accent:#55d0c2;--gold:#f1c75b}
        *{box-sizing:border-box}body{margin:0;min-height:100vh;background:#08111f}button,input,select{font:inherit;color:inherit}button{border:1px solid #34465c;border-radius:8px;background:#162438;padding:8px 11px;cursor:pointer}button:hover:not(:disabled){background:#20344c}button:disabled{opacity:.4;cursor:default}button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid #6ad6ff;outline-offset:2px}
        header{height:58px;display:flex;align-items:center;gap:14px;padding:0 20px;border-bottom:1px solid var(--line);background:#0c1726}header a{color:var(--muted);text-decoration:none}header strong{font-size:17px}header #connection{margin-left:auto;color:var(--muted);font-size:13px}
        .layout{height:calc(100vh - 58px);min-height:400px;display:grid;grid-template-columns:260px minmax(0,1fr)}aside{min-width:0;border-right:1px solid var(--line);background:#0d1929;overflow:auto;padding:14px 10px}.side-title{padding:7px 10px;color:var(--muted);font-size:11px;letter-spacing:.12em;text-transform:uppercase}.tree{display:grid;gap:2px}.tree button{display:flex;width:100%;align-items:center;gap:8px;text-align:left;border:0;background:transparent;padding:8px 10px;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.tree button.active{background:#173548;color:#fff}.tree button .twisty{width:12px;color:#8193a8}.tree .nested{margin-left:16px;border-left:1px solid #26364a;padding-left:5px}.drive-icon{color:#d7e1ef}
        .workspace{min-width:0;display:flex;flex-direction:column}.toolbar{display:flex;align-items:center;gap:7px;min-height:56px;padding:9px 14px;border-bottom:1px solid var(--line);flex-wrap:wrap}.toolbar .spacer{flex:1}.toolbar button{min-height:36px;padding:7px 10px;font-size:13px}.toolbar #upload{border-color:#3e8790;background:#15505a}.toolbar #upload:hover{background:#1c6570}.toolbar .icon-button{width:37px;padding:6px}.toolbar .search{min-width:140px;width:clamp(140px,18vw,255px);min-height:36px;border:1px solid var(--line);border-radius:8px;padding:8px 11px;background:#0c1928}.toolbar select{min-height:36px;max-width:145px;border:1px solid var(--line);border-radius:8px;padding:7px;background:#132238}.browse-summary{padding:7px 18px;color:#b9c8d9;font-size:12px;border-bottom:1px solid #192b40}progress{width:min(360px,100%);height:10px;accent-color:var(--accent)}.breadcrumb{display:flex;align-items:center;gap:8px;min-height:46px;padding:7px 18px;border-bottom:1px solid var(--line);color:var(--muted);overflow:auto;white-space:nowrap}.breadcrumb button{border:0;background:none;padding:4px;color:#dbe6f1}.breadcrumb .sep{color:#61748c}.storage-info{padding:7px 18px;color:var(--muted);font-size:12px;border-bottom:1px solid #1b2a3d}
        .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(118px,1fr));gap:8px;padding:16px;overflow:auto;align-content:start;flex:1}.item{min-width:0;min-height:118px;border:1px solid transparent;border-radius:9px;background:transparent;padding:12px 8px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;text-align:center;touch-action:manipulation}.item:hover{background:#101e30}.item.selected{border-color:#4e9aa6;background:#153342}.item .glyph{font-size:42px;line-height:1;color:var(--gold);text-shadow:0 4px 13px #0007}.item .file-glyph{color:#9fb4ca}.item .name{width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.item .meta{color:var(--muted);font-size:11px}.grid.list{display:flex;flex-direction:column;gap:2px}.grid.list .item{min-height:48px;width:100%;flex-direction:row;justify-content:flex-start;text-align:left;padding:7px 12px;gap:12px}.grid.list .glyph{font-size:23px;width:28px;text-align:center}.grid.list .name{flex:1}.grid.list .meta{min-width:82px;text-align:right}.empty{grid-column:1/-1;padding:36px;text-align:center;color:var(--muted)}footer{min-height:35px;border-top:1px solid var(--line);padding:8px 16px;color:var(--muted);font-size:12px}
        #status{position:fixed;bottom:48px;left:50%;transform:translateX(-50%);max-width:min(90vw,680px);background:#1b2b40;border:1px solid #40536c;border-radius:9px;padding:10px 14px;box-shadow:0 8px 30px #0009;display:none;z-index:4}#uploadInput{display:none}.readonly{color:#e3b987}
        @media(max-width:700px){.layout{grid-template-columns:minmax(0,1fr)}aside{position:fixed;z-index:3;inset:58px auto 0 0;width:min(82vw,290px);box-shadow:12px 0 35px #0008;transform:translateX(-105%);transition:transform .18s}.layout.sidebar-open aside{transform:translateX(0)}.toolbar{gap:5px;padding:8px}.toolbar button{font-size:12px;padding:7px}.toolbar .hide-small{display:none}.toolbar .search{order:2;width:calc(65% - 8px);flex:1}.toolbar select{order:3;flex:1;max-width:none}.toolbar #upload{order:4}.grid{grid-template-columns:repeat(auto-fill,minmax(92px,1fr));padding:10px;gap:4px}.item{min-height:104px}.grid.list .item{min-height:45px}header{padding:0 12px}}
        </style>
        <header><a href="/" aria-label="Back to Send to TV">‹ Send to TV</a><strong>File Manager</strong><span id="connection">Connecting…</span></header>
        <div class="layout" id="layout"><aside><div class="side-title">Storage</div><nav id="tree" class="tree" aria-label="Storage folders"></nav></aside><section class="workspace" aria-label="File browser">
        <div class="toolbar"><button id="sidebar" class="icon-button" aria-label="Toggle folder tree">☰</button><button id="back" class="icon-button" aria-label="Go back">←</button><button id="up" class="icon-button" aria-label="Go up">↑</button><button id="refresh" class="icon-button" aria-label="Refresh">⟳</button><button id="newFolder">＋ New folder</button><button id="delete" disabled>Delete</button><button id="cancelUpload" hidden>Cancel upload</button><span class="spacer"></span><input id="search" class="search" type="search" placeholder="Find in folder…" aria-label="Search files in the current folder"><select id="sort" aria-label="Sort files"><option value="name">Name A–Z</option><option value="nameDesc">Name Z–A</option><option value="size">Largest first</option><option value="type">File type</option></select><input id="uploadInput" type="file"><button id="upload">Upload file</button><button id="gridMode" class="icon-button" aria-label="Grid view">▦</button><button id="listMode" class="icon-button" aria-label="List view">☷</button></div>
        <nav id="breadcrumb" class="breadcrumb" aria-label="Current folder"></nav><div id="storageInfo" class="storage-info"></div><div id="browseSummary" class="browse-summary" aria-live="polite">Choose a storage location to begin.</div><div id="items" class="grid" role="list"></div><progress id="uploadProgress" hidden max="100" value="0" aria-label="Upload progress"></progress><footer id="footer">Waiting for TV approval…</footer></section></div><div id="status" role="status" aria-live="polite"></div>
        <script>
        history.replaceState(null,'',location.pathname);let token=null,roots=[],root=null,path=[],selected=null,view='grid',busy=false,uploadRequest=null,treeExpanded=new Set(),treeCache={},directoryEntries=[];
        const $=id=>document.getElementById(id),items=$('items'),footer=$('footer');
        function message(text){const box=$('status');box.textContent=text;box.style.display='block';clearTimeout(message.timer);message.timer=setTimeout(()=>box.style.display='none',5000)}
        async function pair(){if(token)return;footer.textContent='Waiting for approval on the TV…';$('connection').textContent='Approval needed';const res=await fetch('/api/pair',{method:'POST',cache:'no-store'});if(!res.ok)throw Error(await res.text());token=(await res.json()).token;$('connection').textContent='Connected'}
        async function api(url,options){await pair();options=options||{};options.cache='no-store';options.headers=Object.assign({},options.headers||{}, {'X-Pairing-Token':token});const res=await fetch(url,options);if(res.status===401){token=null;throw Error('TV session ended. Refresh and approve the connection again.')}if(!res.ok)throw Error(await res.text());return options.method==='POST'?res.text():res.json()}
        function queryUrl(endpoint,rootId,relative){return endpoint+'?root='+encodeURIComponent(rootId)+'&path='+encodeURIComponent(relative||'')}
        function relative(){return path.join('/')}
        function formatSize(size){if(size===null||size===undefined)return '';if(size<1024)return size+' B';const units=['KiB','MiB','GiB','TiB'];let n=size/1024,i=0;while(n>=1024&&i<units.length-1){n/=1024;i++}return n.toFixed(n>=10?0:1)+' '+units[i]}
        function setBusy(value){busy=value;$('newFolder').disabled=value||!root||root.readOnly;$('upload').disabled=value||!root||root.readOnly;$('delete').disabled=value||!selected||!root||root.readOnly;$('back').disabled=value||path.length===0;$('up').disabled=value||path.length===0}
        async function loadRoots(){await pair();const result=await api('/api/files/roots');roots=result.roots;renderTree();if(roots.length){const previous=root;root=previous?roots.find(item=>item.id===previous.id)||roots[0]:roots[0];if(!previous||previous.id!==root.id)path=[];await loadDirectory()}else{root=null;path=[];directoryEntries=[];$('browseSummary').textContent='No storage is available';items.replaceChildren();const empty=document.createElement('div');empty.className='empty';empty.textContent=result.storageAccess?'No accessible removable drives or shared Movies folder were found.':'USB browsing needs all-files access. Enable it in TV Player, then refresh this page.';items.appendChild(empty);footer.textContent='0 items'}}
        function renderTree(){const tree=$('tree');tree.replaceChildren();for(const drive of roots){const block=document.createElement('div'),button=document.createElement('button');button.type='button';button.className='tree-root'+(root&&root.id===drive.id&&path.length===0?' active':'');button.textContent='▣  '+drive.name;button.title=drive.name;button.onclick=()=>{root=drive;path=[];selected=null;loadDirectory().catch(showError)};block.appendChild(button);if(root&&root.id===drive.id)drawTreeFolders(block,'',0);tree.appendChild(block)}}
        function drawTreeFolders(parent,relativePath,depth){const names=treeCache[root.id+':'+relativePath]||[];if(!names.length)return;const group=document.createElement('div');group.className='nested';for(const name of names){const folderPath=relativePath?relativePath+'/'+name:name,row=document.createElement('div'),open=treeExpanded.has(root.id+':'+folderPath),toggle=document.createElement('button'),select=document.createElement('button');row.style.display='flex';toggle.className='twisty';toggle.textContent=open?'▾':'▸';toggle.setAttribute('aria-label',(open?'Collapse ':'Expand ')+name);toggle.onclick=async()=>{const key=root.id+':'+folderPath;if(treeExpanded.has(key)){treeExpanded.delete(key);renderTree();return}try{if(!Object.prototype.hasOwnProperty.call(treeCache,key)){const data=await api(queryUrl('/api/files/list',root.id,folderPath));treeCache[key]=data.entries.filter(entry=>entry.directory).map(entry=>entry.name)}treeExpanded.add(key);renderTree()}catch(error){showError(error)}};select.textContent='📁  '+name;select.title=name;select.onclick=()=>{path=folderPath.split('/');selected=null;loadDirectory().catch(showError)};row.append(toggle,select);group.appendChild(row);if(open)drawTreeFolders(group,folderPath,depth+1)}parent.appendChild(group)}
        function showError(error){message(error.message||'Could not complete that action.');footer.textContent=error.message||'Could not complete that action.'}
        async function loadDirectory(){if(!root)return;setBusy(true);footer.textContent='Loading folder…';try{const data=await api(queryUrl('/api/files/list',root.id,relative()));root.readOnly=data.readOnly;root.freeBytes=data.freeBytes;selected=null;treeCache[root.id+':'+relative()]=data.entries.filter(entry=>entry.directory).map(entry=>entry.name);for(let index=1;index<=path.length;index++)treeExpanded.add(root.id+':'+path.slice(0,index).join('/'));renderBreadcrumb();renderItems(data.entries);renderTree();$('storageInfo').textContent=(root.removable?'Removable drive':'Shared Movies')+' · '+formatSize(data.freeBytes)+' free'+(root.readOnly?' · Read only':'');$('storageInfo').className='storage-info'+(root.readOnly?' readonly':'');const folders=data.entries.filter(entry=>entry.directory).length,files=data.entries.length-folders;footer.textContent=folders+' folder'+(folders===1?'':'s')+' · '+files+' file'+(files===1?'':'s')+(root.readOnly?' · Read only':'');setBusy(false)}catch(error){setBusy(false);showError(error)}}
        function renderBreadcrumb(){const bar=$('breadcrumb');bar.replaceChildren();if(!root)return;const segments=[root.name].concat(path);segments.forEach((name,index)=>{if(index) {const sep=document.createElement('span');sep.className='sep';sep.textContent='›';bar.appendChild(sep)}const button=document.createElement('button');button.type='button';button.textContent=name;button.onclick=()=>{path=path.slice(0,index);loadDirectory().catch(showError)};bar.appendChild(button)})}
        function renderItems(entries){directoryEntries=entries;renderVisibleItems()}
        function renderVisibleItems(){
            const term=$('search').value.trim().toLocaleLowerCase(),mode=$('sort').value;
            const visible=directoryEntries.filter(entry=>entry.name.toLocaleLowerCase().includes(term));
            visible.sort((a,b)=>{
                if(a.directory!==b.directory)return a.directory?-1:1;
                if(mode==='size'&&!a.directory)return (b.size||0)-(a.size||0)||a.name.localeCompare(b.name,undefined,{numeric:true,sensitivity:'base'});
                if(mode==='type')return (a.type||'').localeCompare(b.type||'')||a.name.localeCompare(b.name,undefined,{numeric:true,sensitivity:'base'});
                const comparison=a.name.localeCompare(b.name,undefined,{numeric:true,sensitivity:'base'});
                return mode==='nameDesc'?-comparison:comparison;
            });
            items.replaceChildren();items.className='grid'+(view==='list'?' list':'');
            $('browseSummary').textContent=visible.length+' of '+directoryEntries.length+' items'+(term?' matching “'+$('search').value.trim()+'”':'')+' · Select a file, or select then open a folder';
            if(!visible.length){const empty=document.createElement('div');empty.className='empty';empty.textContent=term?'No matching files. Try a different search.':'This folder is empty';items.appendChild(empty);return}
            for(const entry of visible){
                const button=document.createElement('button');button.type='button';button.className='item'+(selected===entry.name?' selected':'');button.setAttribute('role','listitem');
                const glyph=document.createElement('span');glyph.className='glyph'+(entry.directory?'':' file-glyph');glyph.textContent=entry.directory?'📁':'▤';
                const name=document.createElement('span');name.className='name';name.textContent=entry.name;name.title=entry.name;
                const meta=document.createElement('span');meta.className='meta';meta.textContent=entry.directory?'Folder · '+(entry.folderCount===null?'—':entry.folderCount+' folders, '+entry.fileCount+' files'):entry.type+(entry.size===null?'':' · '+formatSize(entry.size));
                button.append(glyph,name,meta);
                button.setAttribute('aria-label',(entry.directory?'Folder ':'File ')+entry.name+'. '+(entry.directory?'Select, then activate again to open.':'Select for file actions.'));
                button.onclick=event=>{
                    if(entry.directory&&(event.detail===0||selected===entry.name)){path.push(entry.name);selected=null;$('search').value='';loadDirectory().catch(showError);return}
                    document.querySelectorAll('.item.selected').forEach(node=>node.classList.remove('selected'));
                    button.classList.add('selected');selected=entry.name;setBusy(false);
                };
                items.appendChild(button);
            }
        }
        $('sidebar').onclick=()=>$('layout').classList.toggle('sidebar-open');$('back').onclick=()=>{if(path.length){path.pop();loadDirectory().catch(showError)}};$('up').onclick=$('back').onclick;$('refresh').onclick=()=>{loadRoots().catch(showError)};$('gridMode').onclick=()=>{view='grid';$('gridMode').setAttribute('aria-pressed','true');$('listMode').setAttribute('aria-pressed','false');renderVisibleItems()};$('listMode').onclick=()=>{view='list';$('gridMode').setAttribute('aria-pressed','false');$('listMode').setAttribute('aria-pressed','true');renderVisibleItems()};$('gridMode').setAttribute('aria-pressed','true');$('listMode').setAttribute('aria-pressed','false');$('search').addEventListener('input',()=>{selected=null;setBusy(busy);renderVisibleItems()});$('sort').addEventListener('change',renderVisibleItems);document.addEventListener('keydown',event=>{if(event.key==='Escape'&&selected){selected=null;setBusy(busy);renderVisibleItems()}if(event.altKey&&event.key==='ArrowUp'&&path.length){event.preventDefault();path.pop();$('search').value='';loadDirectory().catch(showError)}});
        $('newFolder').onclick=async()=>{const name=prompt('New folder name');if(name===null)return;if(!name.trim()){message('Enter a folder name.');return}setBusy(true);try{await api('/api/files/create',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({root:root.id,path:relative(),name:name})});message('Folder created.');await loadDirectory()}catch(error){showError(error);setBusy(false)}};
        $('delete').onclick=async()=>{if(!selected)return;const name=selected;if(!confirm('Delete "'+name+'"? This action cannot be undone. Folders can be deleted only when empty.'))return;setBusy(true);try{await api('/api/files/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({root:root.id,path:relative(),name:name})});message('Deleted '+name);selected=null;await loadDirectory()}catch(error){showError(error);setBusy(false)}};
        $('upload').onclick=()=>{if(!busy)$('uploadInput').click()};$('cancelUpload').onclick=()=>{if(uploadRequest)uploadRequest.abort()};$('uploadInput').onchange=()=>{const file=$('uploadInput').files[0];if(!file)return;if(file.size>8*1024*1024*1024){message('Files are limited to 8 GiB.');$('uploadInput').value='';return}const url='/api/files/upload?root='+encodeURIComponent(root.id)+'&path='+encodeURIComponent(relative())+'&name='+encodeURIComponent(file.name);setBusy(true);$('cancelUpload').hidden=false;uploadRequest=new XMLHttpRequest();uploadRequest.open('POST',url);uploadRequest.setRequestHeader('X-Pairing-Token',token||'');uploadRequest.setRequestHeader('Content-Type','application/octet-stream');$('uploadProgress').hidden=false;$('uploadProgress').value=0;uploadRequest.upload.onprogress=event=>{if(event.lengthComputable){const percent=Math.floor(event.loaded/event.total*100);footer.textContent='Uploading '+file.name+' · '+percent+'%';$('uploadProgress').value=percent}};uploadRequest.onload=()=>{const request=uploadRequest;uploadRequest=null;$('cancelUpload').hidden=true;$('uploadProgress').hidden=true;$('uploadInput').value='';if(request.status>=200&&request.status<300){message(file.name+' added.');loadDirectory().catch(showError)}else{setBusy(false);showError(new Error(request.responseText||'Upload failed'))}};uploadRequest.onerror=()=>{uploadRequest=null;$('cancelUpload').hidden=true;$('uploadProgress').hidden=true;$('uploadInput').value='';setBusy(false);showError(new Error('Upload interrupted; the incomplete file was removed.'))};uploadRequest.onabort=()=>{uploadRequest=null;$('cancelUpload').hidden=true;$('uploadProgress').hidden=true;$('uploadInput').value='';setBusy(false);message('Upload cancelled; the incomplete file was removed.');loadDirectory().catch(showError)};pair().then(()=>{if(uploadRequest)uploadRequest.setRequestHeader('X-Pairing-Token',token);uploadRequest&&uploadRequest.send(file)}).catch(error=>{uploadRequest=null;$('cancelUpload').hidden=true;$('uploadProgress').hidden=true;$('uploadInput').value='';setBusy(false);showError(error)})};
        loadRoots().catch(showError);
        </script></html>
    """.trimIndent()
}
