package dev.lmadhuranga.tvplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.BindException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SubmittedVideo(
    val videoUrl: String,
    val subtitleUrl: String?,
    val uploadedVideo: File? = null,
    val uploadedSubtitle: File? = null,
    val uploadedVideoName: String? = null,
)

data class HandoffState(
    val address: String? = null,
    val isRunning: Boolean = false,
    val message: String? = null,
    val pendingApprovalId: String? = null,
    val requesterDevice: String? = null,
    val requesterAddress: String? = null,
) {
    val pageUrl: String? get() = address?.let { "http://$it:$HANDOFF_PORT/" }
}

private const val HANDOFF_PORT = 8765
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 12 * 1024
private const val MAX_VIDEO_UPLOAD_BYTES = 8L * 1024 * 1024 * 1024
private const val MAX_SUBTITLE_UPLOAD_BYTES = 8L * 1024 * 1024
private const val CACHE_RESERVE_BYTES = 512L * 1024 * 1024
private const val UPLOAD_READ_TIMEOUT_MS = 120_000
private const val APPROVAL_TIMEOUT_SECONDS = 60L
private const val APPROVAL_RETRY_COOLDOWN_MS = 3_000L

/** Foreground-only paired handoff. Link requests stay small; optional files stream to private cache. */
class LocalLinkServer(
    private val appContext: Context,
    private val cacheDirectory: File,
    private val onVideoSubmitted: (SubmittedVideo) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(HandoffState())
    val state: StateFlow<HandoffState> = mutableState.asStateFlow()
    private val stopped = AtomicBoolean(true)
    private val generation = AtomicLong(0L)
    @Volatile private var server: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val clientWorkers = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8), { task -> Thread(task, "tv-player-http-client").apply { isDaemon = true } })
    @Volatile private var approvalSocket: Socket? = null
    @Volatile private var sessionToken: String = ""
    private var pendingApproval: PendingApproval? = null
    private var lastApprovalRequestAtMs = 0L
    private val uploadDirectory = File(cacheDirectory, "handoff-uploads").apply { mkdirs() }
    private val playbackUploadDirectory = File(cacheDirectory, "playback-uploads").apply { mkdirs() }
    private val uploadLock = Any()
    private val stagedUploads = mutableMapOf<UploadKind, StagedUpload>()
    private val inFlightUploads = mutableSetOf<File>()
    private val fileManager = NetworkFileManager(appContext)
    private data class PreviewGrant(val root: String, val path: String, val name: String, val peer: String, val expiresAt: Long)
    private val previewGrants = ConcurrentHashMap<String, PreviewGrant>()

    private enum class UploadKind { VIDEO, SUBTITLE }
    private data class StagedUpload(val file: File, val name: String, val token: String)
    private enum class ApprovalDecision { PENDING, ALLOWED, DENIED, STOPPED }
    private class PendingApproval(
        val id: String,
        val requesterDevice: String,
        val requesterAddress: String,
        @Volatile var decision: ApprovalDecision = ApprovalDecision.PENDING,
    ) {
        val completed = CountDownLatch(1)
    }

    @Synchronized
    fun start() {
        if (!stopped.compareAndSet(true, false)) return
        clearUploads()
        previewGrants.clear()
        uploadDirectory.listFiles()?.forEach { it.delete() }
        val runId = generation.incrementAndGet()
        val lanAddress = findLanAddress()
        if (lanAddress == null) {
            stopped.set(true)
            mutableState.value = HandoffState(message = "Connect the TV to Wi-Fi or Ethernet to use link handoff.")
            return
        }
        var socket: ServerSocket? = null
        try {
            var listener: ServerSocket? = null
            for (attempt in 0..1) {
                try {
                    listener = ServerSocket().apply {
                        // Permit a quick same-process restart after accepted connections close.
                        reuseAddress = true
                        soTimeout = 0
                        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), HANDOFF_PORT), 4)
                    }
                    break
                } catch (error: BindException) {
                    if (attempt != 0 || !closeStaleAppOwnedListener()) throw error
                }
            }
            val boundListener = listener ?: throw BindException("Could not bind port $HANDOFF_PORT")
            socket = boundListener
            server = boundListener
            synchronized(listenerOwnershipLock) { appOwnedListener = boundListener }
            publishPairingState(address = lanAddress)
            Thread({ acceptLoop(boundListener, runId) }, "tv-player-link-server").apply { isDaemon = true; start() }
        } catch (_: BindException) {
            failStart(socket, "Port $HANDOFF_PORT is unavailable. Close the app using it, then choose Retry.")
        } catch (error: Exception) {
            val reason = error.localizedMessage
                ?.filterNot(Char::isISOControl)
                ?.take(160)
                ?.ifBlank { "Unknown startup error" }
                ?: "Unknown startup error"
            failStart(socket, "The link handoff could not start: $reason. Check the TV network connection and retry.")
        }
    }

    /** Reclaims a listener only when this process previously registered the exact socket. */
    private fun closeStaleAppOwnedListener(): Boolean = synchronized(listenerOwnershipLock) {
        val owned = appOwnedListener ?: return@synchronized false
        if (owned === server) return@synchronized false
        appOwnedListener = null
        try { owned.close() } catch (_: Exception) { }
        true
    }

    private fun clearListenerOwnership(listener: ServerSocket?) {
        if (listener == null) return
        synchronized(listenerOwnershipLock) {
            if (appOwnedListener === listener) appOwnedListener = null
        }
    }

    @Synchronized
    fun retry() {
        stop()
        start()
    }

    private fun failStart(socket: ServerSocket?, message: String) {
        try { socket?.close() } catch (_: Exception) { }
        clearListenerOwnership(socket)
        server = null
        stopped.set(true)
        generation.incrementAndGet()
        approvalSocket = null
        sessionToken = ""
        releasePendingApproval()
        mutableState.value = HandoffState(message = message)
    }

    @Synchronized
    fun stop() {
        if (stopped.getAndSet(true)) return
        generation.incrementAndGet()
        sessionToken = ""
        val awaitingSocket = approvalSocket
        releasePendingApproval()
        clientSockets.forEach { client -> if (client !== awaitingSocket) runCatching { client.close() } }
        previewGrants.clear()
        val current = server
        server = null
        try { current?.close() } catch (_: Exception) { }
        clearListenerOwnership(current)
        clearUploads()
        mutableState.value = HandoffState()
    }

    private fun acceptLoop(listener: ServerSocket, runId: Long) {
        while (isCurrent(listener, runId)) {
            val client = try {
                listener.accept()
            } catch (_: SocketException) {
                stopAfterListenerFailure(listener, runId)
                return
            } catch (_: Exception) {
                stopAfterListenerFailure(listener, runId)
                return
            }
            clientSockets.add(client)
            try {
                clientWorkers.execute {
                    try {
                        client.use { socket ->
                            socket.soTimeout = 4_000
                            if (isCurrent(listener, runId)) {
                                if (isAllowedPeer(socket.inetAddress)) handleRequest(socket, listener, runId)
                                else respond(socket.getOutputStream(), 403, "Forbidden", "Local network only")
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Close stalled HTTP requests without blocking another preview or upload.
                    } catch (_: Exception) {
                        // A peer may disconnect during a long upload or range response.
                    } finally {
                        clientSockets.remove(client)
                    }
                }
            } catch (_: RejectedExecutionException) {
                clientSockets.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun isCurrent(listener: ServerSocket, runId: Long): Boolean =
        !stopped.get() && generation.get() == runId && server === listener

    @Synchronized
    private fun stopAfterListenerFailure(listener: ServerSocket, runId: Long) {
        if (!isCurrent(listener, runId)) return
        stopped.set(true)
        val failedGeneration = generation.incrementAndGet()
        server = null
        clientSockets.forEach { client -> runCatching { client.close() } }
        previewGrants.clear()
        approvalSocket = null
        sessionToken = ""
        releasePendingApproval()
        try { listener.close() } catch (_: Exception) { }
        clearListenerOwnership(listener)
        mainHandler.post {
            if (stopped.get() && generation.get() == failedGeneration) {
                mutableState.value = HandoffState(message = "The link handoff stopped. Reopen this screen to start a new session.")
            }
        }
    }

    private fun handleRequest(socket: Socket, listener: ServerSocket, runId: Long) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readBoundedLine(input, MAX_HEADER_BYTES) ?: return
        val request = requestLine.split(' ', limit = 3)
        if (request.size < 2) return respond(socket.getOutputStream(), 400, "Bad Request", "Invalid request")
        val headers = LinkedHashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readBoundedLine(input, MAX_HEADER_BYTES - headerBytes)
                ?: return respond(socket.getOutputStream(), 400, "Bad Request", "Invalid headers")
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) return respond(socket.getOutputStream(), 413, "Payload Too Large", "Headers too large")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) return respond(socket.getOutputStream(), 400, "Bad Request", "Invalid headers")
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            if (headers.put(name, line.substring(colon + 1).trim()) != null) {
                return respond(socket.getOutputStream(), 400, "Bad Request", "Duplicate request header")
            }
        }

        val method = request[0]
        val target = request[1]
        val path = target.substringBefore('?')
        if (method == "GET" && path == "/") return respond(socket.getOutputStream(), 200, "OK", LINK_ENTRY_PAGE, "text/html; charset=utf-8")
        if (method == "GET" && path == "/files") return respond(socket.getOutputStream(), 200, "OK", FileManagerPage.html, "text/html; charset=utf-8")
        if (path.startsWith("/api/files/")) return handleFileManagerRequest(socket, input, method, path, target, headers, listener, runId)
        if (method == "POST" && path == "/api/pair") {
            if (!isSameOrigin(headers["origin"], socket.localAddress)) return respond(socket.getOutputStream(), 403, "Forbidden", "Open the link page on this TV's local network")
            val requesterAddress = socket.inetAddress.hostAddress ?: "Unknown address"
            val requesterDevice = describeRequester(headers["user-agent"])
            return requestPairApproval(socket, listener, runId, requesterDevice, requesterAddress)
        }
        if (method == "GET" && path == "/api/limits") {
            // Same-origin GET fetches commonly omit Origin. The paired token still
            // authenticates this read-only limit lookup; reject any supplied mismatch.
            if (headers["origin"]?.let { !isSameOrigin(it, socket.localAddress) } == true) {
                return respond(socket.getOutputStream(), 403, "Forbidden", "Open the link page on this TV's local network")
            }
            if (!isAuthorized(headers["x-pairing-token"].orEmpty())) return respond(socket.getOutputStream(), 401, "Unauthorized", "Request approval on the TV before sending media")
            return respond(socket.getOutputStream(), 200, "OK", JSONObject(mapOf(
                "videoBytes" to effectiveUploadLimit(UploadKind.VIDEO),
                "subtitleBytes" to effectiveUploadLimit(UploadKind.SUBTITLE),
            )).toString(), "application/json; charset=utf-8")
        }
        if (method != "POST" || path !in setOf("/api/play", "/api/upload/video", "/api/upload/subtitle", "/api/cancel-upload")) return respond(socket.getOutputStream(), 404, "Not Found", "Not found")
        if (!isSameOrigin(headers["origin"], socket.localAddress)) return respond(socket.getOutputStream(), 403, "Forbidden", "Open the link page on this TV's local network")
        val requestToken = headers["x-pairing-token"].orEmpty()
        if (!isAuthorized(requestToken)) {
            return respond(socket.getOutputStream(), 401, "Unauthorized", "Request approval on the TV before sending media")
        }
        if (path == "/api/cancel-upload") {
            discardStagedUploads(requestToken)
            return respond(socket.getOutputStream(), 200, "OK", "Temporary uploads cleared")
        }
        if (path.startsWith("/api/upload/")) {
            return receiveUpload(socket, input, headers, path, requestToken, listener, runId)
        }
        if (!headers["content-type"].orEmpty().lowercase(Locale.ROOT).startsWith("application/json")) {
            return respond(socket.getOutputStream(), 415, "Unsupported Media Type", "Send the links as JSON")
        }
        if (headers["transfer-encoding"] != null) return respond(socket.getOutputStream(), 400, "Bad Request", "Chunked requests are not supported")
        val bodyLength = headers["content-length"]?.toIntOrNull()
            ?: return respond(socket.getOutputStream(), 411, "Length Required", "Request size required")
        if (bodyLength !in 1..MAX_BODY_BYTES) return respond(socket.getOutputStream(), 413, "Payload Too Large", "Request is too large")
        val body = ByteArray(bodyLength)
        var offset = 0
        while (offset < bodyLength) {
            val count = input.read(body, offset, bodyLength - offset)
            if (count < 0) return respond(socket.getOutputStream(), 400, "Bad Request", "Incomplete request")
            offset += count
        }
        val payload = try { JSONObject(String(body, StandardCharsets.UTF_8)) }
        catch (_: Exception) { return respond(socket.getOutputStream(), 400, "Bad Request", "Invalid link request") }
        val uploadedVideo = payload.optBoolean("uploadedVideo")
        val videoValue = payload.optString("videoUrl").takeIf { it.isNotBlank() }
        if (uploadedVideo == (videoValue != null)) return respond(socket.getOutputStream(), 422, "Unprocessable Entity", "Choose one video URL or upload")
        val videoUrl = if (uploadedVideo) "" else when (val result = validateVideoUrl(videoValue.orEmpty())) {
            is VideoUrlValidation.Valid -> result.normalizedUrl
            is VideoUrlValidation.Invalid -> return respond(socket.getOutputStream(), 422, "Unprocessable Entity", result.message)
        }
        val uploadedSubtitle = payload.optBoolean("uploadedSubtitle")
        val subtitleValue = payload.optString("subtitleUrl").takeIf { it.isNotBlank() }
        if (uploadedSubtitle && subtitleValue != null) return respond(socket.getOutputStream(), 422, "Unprocessable Entity", "Choose a subtitle URL or upload, not both")
        val subtitleUrl = if (subtitleValue == null) null else when (val result = validateSubtitleUrl(subtitleValue)) {
            is VideoUrlValidation.Valid -> result.normalizedUrl
            is VideoUrlValidation.Invalid -> return respond(socket.getOutputStream(), 422, "Unprocessable Entity", result.message)
        }
        val submission = synchronized(uploadLock) {
            val video = if (uploadedVideo) stagedUploads[UploadKind.VIDEO]?.takeIf { it.token == requestToken } else null
            val subtitle = if (uploadedSubtitle) stagedUploads[UploadKind.SUBTITLE]?.takeIf { it.token == requestToken } else null
            if (uploadedVideo && video == null) return respond(socket.getOutputStream(), 409, "Conflict", "Upload the video file again before sending")
            if (uploadedSubtitle && subtitle == null) return respond(socket.getOutputStream(), 409, "Conflict", "Upload the subtitle file again before sending")
            val videoFile = video?.let { transferToPlayback(it.file) }
            if (video != null && videoFile == null) return respond(socket.getOutputStream(), 507, "Insufficient Storage", "The uploaded video could not be prepared. Try sending it again.")
            val subtitleFile = subtitle?.let { transferToPlayback(it.file) }
            if (subtitle != null && subtitleFile == null) {
                videoFile?.delete()
                return respond(socket.getOutputStream(), 507, "Insufficient Storage", "The uploaded subtitle could not be prepared. Try sending it again.")
            }
            video?.let { stagedUploads.remove(UploadKind.VIDEO) }
            subtitle?.let { stagedUploads.remove(UploadKind.SUBTITLE) }
            SubmittedVideo(videoUrl, subtitleUrl, videoFile, subtitleFile, video?.name)
        }
        respond(socket.getOutputStream(), 202, "Accepted", "The TV is opening your video.")
        mainHandler.post {
            if (isCurrent(listener, runId)) onVideoSubmitted(submission)
            else {
                submission.uploadedVideo?.delete()
                submission.uploadedSubtitle?.delete()
            }
        }
    }

    private fun isAuthorized(token: String): Boolean {
        val expected = sessionToken.toByteArray(StandardCharsets.UTF_8)
        return expected.isNotEmpty() && MessageDigest.isEqual(expected, token.toByteArray(StandardCharsets.UTF_8))
    }

    private fun handleFileManagerRequest(
        socket: Socket,
        input: BufferedInputStream,
        method: String,
        path: String,
        target: String,
        headers: Map<String, String>,
        listener: ServerSocket,
        runId: Long,
    ) {
        val output = socket.getOutputStream()
        val token = headers["x-pairing-token"].orEmpty()
        if (path == "/api/files/media" && method in setOf("GET", "HEAD")) {
            if (headers["origin"]?.let { !isSameOrigin(it, socket.localAddress) } == true) return respond(output, 403, "Forbidden", "This media preview must open from the TV file manager")
            return streamPreview(socket, method, target, headers["range"], listener, runId)
        }
        if (method == "GET") {
            if (headers["origin"]?.let { !isSameOrigin(it, socket.localAddress) } == true) return respond(output, 403, "Forbidden", "Open the file manager from this TV's local network")
            if (!isAuthorized(token)) return respond(output, 401, "Unauthorized", "Request approval on the TV before managing files")
            val query = try { parseQuery(target.substringAfter('?', "")) } catch (_: Exception) { return respond(output, 400, "Bad Request", "Invalid folder path") }
            val body = try {
                when (path) {
                    "/api/files/roots" -> fileManager.rootsJson()
                    "/api/files/list" -> fileManager.listJson(query["root"].orEmpty(), query["path"].orEmpty())
                    "/api/files/preview-ticket" -> {
                        val root = query["root"].orEmpty()
                        val relative = query["path"].orEmpty()
                        val name = query["name"].orEmpty()
                        fileManager.previewFile(root, relative, name)
                        val ticket = issuePreviewTicket(root, relative, name, socket.inetAddress.hostAddress.orEmpty())
                        JSONObject(mapOf("url" to "/api/files/media?ticket=$ticket")).toString()
                    }
                    else -> return respond(output, 404, "Not Found", "Not found")
                }
            } catch (error: Exception) {
                return respond(output, 400, "Bad Request", error.message ?: "Could not read this location")
            }
            return respond(output, 200, "OK", body, "application/json; charset=utf-8")
        }
        if (method != "POST") return respond(output, 405, "Method Not Allowed", "Use GET or POST")
        if (!isSameOrigin(headers["origin"], socket.localAddress)) return respond(output, 403, "Forbidden", "Open the file manager from this TV's local network")
        if (!isAuthorized(token)) return respond(output, 401, "Unauthorized", "Request approval on the TV before managing files")
        if (headers["transfer-encoding"] != null) return respond(output, 400, "Bad Request", "Chunked requests are not supported")
        if (path == "/api/files/upload") {
            val length = headers["content-length"]?.toLongOrNull() ?: return respond(output, 411, "Length Required", "File size is required")
            if (length !in 1..NetworkFileManager.MAX_FILE_BYTES) return respond(output, 413, "Payload Too Large", "Files must be between 1 byte and 8 GiB")
            if (!headers["content-type"].orEmpty().lowercase(Locale.ROOT).startsWith("application/octet-stream")) return respond(output, 415, "Unsupported Media Type", "Upload as a binary file")
            val query = try { parseQuery(target.substringAfter('?', "")) } catch (_: Exception) { return respond(output, 400, "Bad Request", "Invalid destination") }
            return try {
                fileManager.upload(query["root"].orEmpty(), query["path"].orEmpty(), query["name"].orEmpty(), length, input) {
                    isCurrent(listener, runId) && isAuthorized(token)
                }
                respond(output, 201, "Created", "File added")
            } catch (error: Exception) {
                val status = when (error) {
                    is NetworkFileManager.FileExistsException -> 409 to "Conflict"
                    is NetworkFileManager.InsufficientSpaceException -> 507 to "Insufficient Storage"
                    else -> 400 to "Bad Request"
                }
                respond(output, status.first, status.second, error.message ?: "Upload failed")
            }
        }
        val bodyLength = headers["content-length"]?.toIntOrNull() ?: return respond(output, 411, "Length Required", "Request size required")
        if (bodyLength !in 1..4_096) return respond(output, 413, "Payload Too Large", "Request is too large")
        val bytes = ByteArray(bodyLength)
        var offset = 0
        while (offset < bodyLength) {
            val count = input.read(bytes, offset, bodyLength - offset)
            if (count < 0) return respond(output, 400, "Bad Request", "Incomplete request")
            offset += count
        }
        val json = try { JSONObject(String(bytes, StandardCharsets.UTF_8)) }
        catch (_: Exception) { return respond(output, 400, "Bad Request", "Invalid request") }
        return try {
            when (path) {
                "/api/files/create" -> fileManager.createFolder(json.optString("root"), json.optString("path"), json.optString("name"))
                "/api/files/delete" -> fileManager.delete(json.optString("root"), json.optString("path"), json.optString("name"))
                else -> return respond(output, 404, "Not Found", "Not found")
            }
            respond(output, 200, "OK", "Done")
        } catch (error: Exception) {
            val status = if (error is NetworkFileManager.FileExistsException) 409 to "Conflict" else 400 to "Bad Request"
            respond(output, status.first, status.second, error.message ?: "Could not update this location")
        }
    }

    /** A scoped, short-lived URL allows native browser video/image tags without sharing the pairing token. */
    private fun issuePreviewTicket(root: String, path: String, name: String, peer: String): String {
        val now = System.currentTimeMillis()
        previewGrants.entries.removeIf { it.value.expiresAt < now }
        if (previewGrants.size >= 32) previewGrants.clear()
        val ticket = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        previewGrants[ticket] = PreviewGrant(root, path, name, peer, now + 4L * 60L * 60L * 1000L)
        return ticket
    }

    private fun streamPreview(
        socket: Socket,
        method: String,
        target: String,
        rangeHeader: String?,
        listener: ServerSocket,
        runId: Long,
    ) {
        val output = socket.getOutputStream()
        val ticket = try { parseQuery(target.substringAfter('?', ""))["ticket"].orEmpty() }
            catch (_: Exception) { return respond(output, 400, "Bad Request", "Invalid preview link") }
        val grant = previewGrants[ticket]
        val now = System.currentTimeMillis()
        if (grant == null || grant.expiresAt < now || grant.peer != socket.inetAddress.hostAddress) {
            return respond(output, 401, "Unauthorized", "Preview expired. Reopen this file in the file manager.")
        }
        val media = try { fileManager.previewFile(grant.root, grant.path, grant.name) }
            catch (_: Exception) { return respond(output, 404, "Not Found", "The preview file is no longer available.") }
        try {
            RandomAccessFile(media.file, "r").use { file ->
                val fileSize = file.length()
                val window = PreviewByteRanges.parse(rangeHeader, fileSize)
                if (window == null) {
                    val response = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$fileSize\r\nContent-Length: 0\r\n" +
                        "Accept-Ranges: bytes\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
                    output.write(response.toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    return
                }
                val partial = rangeHeader != null
                val response = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n" +
                    "Content-Type: ${media.mimeType}\r\n" +
                    "Content-Length: ${window.length}\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    (if (partial) "Content-Range: bytes ${window.first}-${window.last}/$fileSize\r\n" else "") +
                    "Connection: close\r\nCache-Control: no-store\r\n" +
                    "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n\r\n"
                output.write(response.toByteArray(StandardCharsets.US_ASCII))
                if (method != "HEAD") {
                    file.seek(window.first)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = window.length
                    while (remaining > 0L && isCurrent(listener, runId)) {
                        val count = file.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                output.flush()
            }
        } catch (_: IOException) {
            // Browsers frequently cancel or replace range requests while seeking.
        }
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').filter(String::isNotEmpty).associate { item ->
        java.net.URLDecoder.decode(item.substringBefore('='), "UTF-8") to
            java.net.URLDecoder.decode(item.substringAfter('=', ""), "UTF-8")
    }

    private fun effectiveUploadLimit(kind: UploadKind): Long {
        val available = (uploadDirectory.usableSpace - CACHE_RESERVE_BYTES).coerceAtLeast(0L)
        val kindLimit = if (kind == UploadKind.VIDEO) MAX_VIDEO_UPLOAD_BYTES else MAX_SUBTITLE_UPLOAD_BYTES
        return minOf(kindLimit, available)
    }

    private fun receiveUpload(
        socket: Socket,
        input: BufferedInputStream,
        headers: Map<String, String>,
        path: String,
        token: String,
        listener: ServerSocket,
        runId: Long,
    ) {
        val kind = when (path) {
            "/api/upload/video" -> UploadKind.VIDEO
            "/api/upload/subtitle" -> UploadKind.SUBTITLE
            else -> return respond(socket.getOutputStream(), 404, "Not Found", "Not found")
        }
        if (!headers["content-type"].orEmpty().lowercase(Locale.ROOT).startsWith("application/octet-stream")) {
            return respond(socket.getOutputStream(), 415, "Unsupported Media Type", "Upload as a binary file")
        }
        if (headers["transfer-encoding"] != null) return respond(socket.getOutputStream(), 400, "Bad Request", "Chunked uploads are not supported")
        val length = headers["content-length"]?.toLongOrNull()
            ?: return respond(socket.getOutputStream(), 411, "Length Required", "File size is required")
        val limit = effectiveUploadLimit(kind)
        if (length <= 0L || length > limit) {
            val limitMessage = if (kind == UploadKind.VIDEO) "The video exceeds the current TV upload limit of ${formatBytes(limit)}." else "The subtitle exceeds the 8 MiB limit or available TV storage."
            return respond(socket.getOutputStream(), 413, "Payload Too Large", limitMessage)
        }
        val displayName = sanitizeUploadName(headers["x-upload-filename"], kind)
            ?: return respond(socket.getOutputStream(), 415, "Unsupported Media Type", if (kind == UploadKind.VIDEO) "Choose a supported video file." else "Choose an SRT or WebVTT subtitle file.")
        // usableSpace reflects already-staged files; subtracting the reserve here also
        // keeps their storage cost in the next request's effective limit.
        if (length > effectiveUploadLimit(kind)) return respond(socket.getOutputStream(), 413, "Payload Too Large", "There is not enough temporary storage while keeping the TV reserve.")
        val suffix = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val staged = File(uploadDirectory, "upload-${UUID.randomUUID()}.$suffix")
        synchronized(uploadLock) { inFlightUploads += staged }
        try {
            socket.soTimeout = UPLOAD_READ_TIMEOUT_MS
            FileOutputStream(staged).buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                var first = ByteArray(0)
                while (remaining > 0L) {
                    if (!isCurrent(listener, runId) || !isAuthorized(token)) throw IOException("Upload session ended")
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Upload ended early")
                    if (first.size < 16) first += buffer.copyOfRange(0, minOf(read, 16 - first.size))
                    output.write(buffer, 0, read)
                    remaining -= read
                    if (uploadDirectory.usableSpace < CACHE_RESERVE_BYTES) throw UploadStorageException()
                }
                if (!validUploadContent(kind, suffix, first)) {
                    throw InvalidUploadException(if (kind == UploadKind.SUBTITLE) "The subtitle file is empty or does not match its SRT/WebVTT type." else "The video file does not match a supported video container.")
                }
            }
            if (!isCurrent(listener, runId) || !isAuthorized(token)) throw IOException("Upload session ended")
            synchronized(uploadLock) {
                if (!isCurrent(listener, runId) || !isAuthorized(token)) throw IOException("Upload session ended")
                val previous = stagedUploads.put(kind, StagedUpload(staged, displayName, token))
                previous?.file?.delete()
            }
            respond(socket.getOutputStream(), 201, "Created", "${if (kind == UploadKind.VIDEO) "Video" else "Subtitle"} uploaded temporarily.")
        } catch (error: InvalidUploadException) {
            staged.delete()
            respond(socket.getOutputStream(), 415, "Unsupported Media Type", error.message.orEmpty())
        } catch (_: UploadStorageException) {
            staged.delete()
            runCatching { respond(socket.getOutputStream(), 507, "Insufficient Storage", "TV storage is low. The incomplete upload was removed; free space and try again.") }
        } catch (_: Exception) {
            staged.delete()
            runCatching { respond(socket.getOutputStream(), 400, "Bad Request", "The upload was interrupted. Try again.") }
        } finally {
            synchronized(uploadLock) { inFlightUploads.remove(staged) }
        }
    }

    private class InvalidUploadException(message: String) : IOException(message)
    private class UploadStorageException : IOException()

    private fun sanitizeUploadName(header: String?, kind: UploadKind): String? {
        if (header.isNullOrBlank() || header.length > 1_024) return null
        val decoded = runCatching { java.net.URLDecoder.decode(header.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        val name = decoded.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
            .take(120)
        if (name.isBlank()) return null
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val allowed = if (kind == UploadKind.SUBTITLE) setOf("srt", "vtt") else setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm", "ts", "m2ts", "mpg", "mpeg")
        return name.takeIf { extension in allowed }
    }

    private fun validUploadContent(kind: UploadKind, extension: String, first: ByteArray): Boolean {
        if (first.isEmpty()) return false
        if (kind == UploadKind.SUBTITLE) return true // SubtitleRepository performs strict SRT/VTT parsing before playback.
        fun starts(vararg bytes: Int) = first.size >= bytes.size && bytes.indices.all { (first[it].toInt() and 0xff) == bytes[it] }
        val mp4 = first.size >= 8 && String(first.copyOfRange(4, 8), StandardCharsets.US_ASCII) == "ftyp"
        return when (extension) {
            "mp4", "m4v", "mov", "3gp" -> mp4
            "mkv", "webm" -> starts(0x1a, 0x45, 0xdf, 0xa3)
            "ts", "m2ts" -> first.size > 0 && (first[0].toInt() and 0xff) == 0x47
            "mpg", "mpeg" -> starts(0x00, 0x00, 0x01) && first.size > 3 && (first[3].toInt() and 0xff) in setOf(0xba, 0xb3)
            else -> false
        }
    }

    private fun discardStagedUploads(token: String) {
        synchronized(uploadLock) {
            val matching = stagedUploads.filterValues { it.token == token }.keys.toList()
            matching.forEach { stagedUploads.remove(it)?.file?.delete() }
        }
    }

    private fun clearUploads() {
        synchronized(uploadLock) {
            stagedUploads.values.forEach { it.file.delete() }
            stagedUploads.clear()
            inFlightUploads.forEach { it.delete() }
        }
    }

    private fun transferToPlayback(file: File): File? {
        val target = File(playbackUploadDirectory, "media-${UUID.randomUUID()}.${file.extension}")
        return target.takeIf { file.renameTo(it) }
    }

    private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "${bytes / (1024L * 1024 * 1024)} GiB" else "${bytes / (1024L * 1024)} MiB"

    private fun requestPairApproval(
        socket: Socket,
        listener: ServerSocket,
        runId: Long,
        requesterDevice: String,
        requesterAddress: String,
    ) {
        val output = socket.getOutputStream()
        val pending = synchronized(this) {
            if (!isCurrent(listener, runId)) {
                respond(output, 410, "Gone", "The TV handoff session has ended")
                return
            }
            if (pendingApproval != null) {
                respond(output, 409, "Conflict", "A phone is already waiting for approval on the TV")
                return
            }
            val now = System.nanoTime() / 1_000_000L
            if (now - lastApprovalRequestAtMs < APPROVAL_RETRY_COOLDOWN_MS) {
                respond(output, 429, "Too Many Requests", "Wait briefly before requesting TV approval again")
                return
            }
            lastApprovalRequestAtMs = now
            PendingApproval(UUID.randomUUID().toString(), requesterDevice, requesterAddress).also {
                pendingApproval = it
                approvalSocket = socket
                publishPairingState(message = null)
            }
        }

        val completedInTime = try {
            pending.completed.await(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        val response = synchronized(this) {
            // Start the retry window when this request completes as well, so
            // requests already queued in the socket backlog cannot raise a second prompt.
            lastApprovalRequestAtMs = System.nanoTime() / 1_000_000L
            when {
                pending.decision == ApprovalDecision.ALLOWED && isCurrent(listener, runId) -> {
                    if (pendingApproval === pending) pendingApproval = null
                    val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
                    sessionToken = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    publishPairingState(message = null)
                    Triple(200, "OK", JSONObject(mapOf("token" to sessionToken)).toString())
                }
                pending.decision == ApprovalDecision.DENIED -> {
                    Triple(403, "Forbidden", "Pairing was denied on the TV")
                }
                pending.decision == ApprovalDecision.STOPPED || !isCurrent(listener, runId) -> {
                    Triple(410, "Gone", "The TV handoff stopped before approval was completed")
                }
                !completedInTime -> {
                    if (pendingApproval === pending) {
                        pendingApproval = null
                        publishPairingState(message = "Phone approval timed out. The phone can request again.")
                    }
                    Triple(408, "Request Timeout", "TV approval timed out. Ask the TV owner to try again")
                }
                else -> Triple(410, "Gone", "The TV handoff stopped before approval was completed")
            }
        }
        try {
            respond(output, response.first, response.second, response.third,
                if (response.first == 200) "application/json; charset=utf-8" else "text/plain; charset=utf-8")
        } finally {
            synchronized(this) {
                if (approvalSocket === socket) approvalSocket = null
            }
        }
    }

    @Synchronized
    fun approvePairingRequest(id: String) {
        val pending = pendingApproval?.takeIf { it.id == id && it.decision == ApprovalDecision.PENDING } ?: return
        pending.decision = ApprovalDecision.ALLOWED
        pendingApproval = null
        publishPairingState(message = null)
        pending.completed.countDown()
    }

    @Synchronized
    fun denyPairingRequest(id: String) {
        val pending = pendingApproval?.takeIf { it.id == id && it.decision == ApprovalDecision.PENDING } ?: return
        pending.decision = ApprovalDecision.DENIED
        pendingApproval = null
        publishPairingState(message = "Phone connection was denied on the TV.")
        pending.completed.countDown()
    }

    private fun releasePendingApproval() {
        pendingApproval?.let { pending ->
            pendingApproval = null
            pending.decision = ApprovalDecision.STOPPED
            pending.completed.countDown()
        }
    }

    private fun describeRequester(userAgent: String?): String {
        val agent = userAgent.orEmpty().lowercase(Locale.ROOT)
        return when {
            "edg/" in agent -> "Microsoft Edge"
            "firefox/" in agent -> "Firefox"
            "chrome/" in agent && "edg/" !in agent -> "Chrome"
            "safari/" in agent && "chrome/" !in agent -> "Safari"
            "android" in agent -> "Android browser"
            else -> "Phone or computer browser"
        }
    }

    private fun publishPairingState(address: String? = mutableState.value.address, message: String? = mutableState.value.message) {
        val pending = pendingApproval
        mutableState.value = HandoffState(
            address = address,
            isRunning = !stopped.get(),
            message = message,
            pendingApprovalId = pending?.id,
            requesterDevice = pending?.requesterDevice,
            requesterAddress = pending?.requesterAddress,
        )
    }

    private fun isSameOrigin(origin: String?, localAddress: InetAddress): Boolean {
        if (origin == null) return false
        val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host == localAddress.hostAddress && uri.port == HANDOFF_PORT
    }

    private fun isAllowedPeer(peer: InetAddress): Boolean = peer is Inet4Address && peer.isSiteLocalAddress && sharesSubnet(peer)

    private fun sharesSubnet(peer: InetAddress): Boolean {
        val peerBytes = peer.address
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        for (network in interfaces) for (interfaceAddress in network.interfaceAddresses) {
            val local = interfaceAddress.address as? Inet4Address ?: continue
            val prefix = interfaceAddress.networkPrefixLength.toInt()
            if (!network.isUp || network.isLoopback || prefix !in 1..32) continue
            val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
            val localInt = java.nio.ByteBuffer.wrap(local.address).int
            val peerInt = java.nio.ByteBuffer.wrap(peerBytes).int
            if ((localInt and mask) == (peerInt and mask)) return true
        }
        return false
    }

    private fun findLanAddress(): String? {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        val candidates = runCatching {
            interfaces.filter { it.isUp && !it.isLoopback }
                .flatMap { network -> network.interfaceAddresses.mapNotNull { it.address as? Inet4Address } }
                .filter { it.isSiteLocalAddress }
        }.getOrDefault(emptyList())
        return candidates.firstOrNull { address ->
            val host = address.hostAddress ?: return@firstOrNull false
            host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")
        }?.hostAddress
    }

    private fun readBoundedLine(input: BufferedInputStream, remaining: Int): String? {
        if (remaining <= 0) return null
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= remaining) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
            if (value == '\n'.code) {
                val line = bytes.toByteArray().let { if (it.lastOrNull() == '\r'.code.toByte()) it.copyOf(it.size - 1) else it }
                return String(line, StandardCharsets.ISO_8859_1)
            }
            bytes.write(value)
        }
        return null
    }

    private fun respond(output: OutputStream, status: Int, reason: String, body: String, contentType: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self'; media-src 'self'; form-action 'self'; base-uri 'none'\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private companion object {
        /** Tracks only listeners created by this process, so recovery never kills an unknown owner. */
        val listenerOwnershipLock = Any()
        @Volatile var appOwnedListener: ServerSocket? = null

        val LINK_ENTRY_PAGE = """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>TV Player</title>
            <style>
            :root{color-scheme:dark;font:16px/1.45 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#07111f;color:#fff}
            *{box-sizing:border-box}
            body{min-height:100vh;min-height:100svh;margin:0;padding:clamp(12px,3vw,28px);padding-top:max(clamp(12px,3vw,28px),env(safe-area-inset-top));padding-bottom:max(clamp(12px,3vw,28px),env(safe-area-inset-bottom));overflow-x:hidden}
            main{width:min(100%,620px);margin:0 auto;padding:clamp(16px,4vw,28px);border:1px solid #25354a;border-radius:18px;background:#0d192a;box-shadow:0 16px 48px #0005}
            h1{margin:0 0 18px;font-size:clamp(1.45rem,5vw,1.9rem);line-height:1.2;letter-spacing:-.02em}
            strong{font-size:1rem}
            label{display:block;margin:12px 0 6px;font-size:.95rem;font-weight:650}
            input{width:100%;min-width:0;padding:12px;border:1px solid #53647a;border-radius:10px;background:#101c2b;color:#fff;font:inherit}
            input::placeholder{color:#9eacbd;opacity:1}
            input:focus-visible,button:focus-visible{outline:3px solid #67c8ff;outline-offset:3px}
            .choice{margin:0 0 12px;padding:14px;border:1px solid #293b51;border-radius:12px;background:#101c2b}
            .mode{margin:8px 0;border:0;padding:0;min-width:0}
            .mode legend{margin-bottom:5px;color:#bdc9d8;font-size:.82rem}
            .mode-options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;max-width:300px}
            button{min-height:44px;padding:10px 14px;border:1px solid #40516a;border-radius:10px;background:#1b293b;color:#fff;font:inherit;font-weight:650;cursor:pointer;touch-action:manipulation}
            button:disabled{opacity:.55;cursor:default}
            .mode-button{margin:0}
            .mode-button[aria-pressed="true"]{border-color:#3b7886;background:#17434f;color:#fff}
            .url-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:6px;align-items:center}
            .url-row button{min-height:42px;padding:8px 10px;font-size:.84rem}
            .file-input{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;clip-path:inset(50%);border:0}
            .dropzone{display:grid;width:100%;min-height:94px;place-content:center;gap:4px;margin:0;padding:14px;border:1px dashed #60748c;border-radius:12px;background:#101c2b;color:#fff;text-align:center}
            .dropzone strong{font-size:.92rem}.dropzone small{margin:0;font-weight:400}
            .dropzone.dragging{border-color:#67c8ff;background:#123344;box-shadow:inset 0 0 0 1px #67c8ff}
            .file-actions{display:flex;align-items:center;gap:10px;margin-top:7px}
            .file-actions small{flex:1;min-width:0;margin:0;overflow-wrap:anywhere}
            .file-clear{min-height:38px;padding:6px 12px;font-size:.84rem}
            .history-heading{display:block;margin-top:14px;font-size:.88rem}
            .history-toggle{margin-top:10px;background:#1b293b;font-size:.9rem}
            .history-list{display:grid;gap:6px;margin-top:6px}
            .history-list button{width:100%;min-width:0;text-align:left;overflow-wrap:anywhere;font-size:.9rem}
            small{display:block;margin-top:10px;color:#bdc9d8;font-size:.8rem}
            #videoLimit:empty{display:none}
            #subtitleToggle{width:100%;margin:0 0 12px;background:#1b293b}
            #clearUrlHistory{width:100%;min-height:40px;margin:0 0 12px;border:0;background:transparent;color:#c7d2e0;font-size:.88rem}
            #send{width:100%;min-height:48px;border-color:#286777;background:#123947;color:#fff;font-size:1.02rem}
            #send:hover:not(:disabled){background:#174857}
            #cancel{width:100%;margin-top:10px}
            #progress{display:block;width:100%;height:10px;margin:10px 0 0;accent-color:#d4dbe4}
            #status{min-height:0;margin:10px 0 0;color:#e3e9f1;font-size:.9rem;white-space:pre-wrap;overflow-wrap:anywhere}
            [hidden]{display:none!important}
            @media(max-width:380px){body{padding-left:10px;padding-right:10px}main{padding:14px;border-radius:14px}.choice{padding:12px}.mode-options{max-width:none}.url-row{grid-template-columns:minmax(0,1fr) auto}.url-row input{grid-column:1/-1;grid-row:1}.url-row button{width:100%}}
            @media(min-width:800px){body{display:grid;align-items:center}main{padding:30px}}
            </style>
            <main><h1>Send to your TV</h1><a class="file-manager-link" style="display:block;margin:-8px 0 16px;color:#b9c8d9" href="/files">Open File Manager</a>
            <section class="choice"><strong>Video</strong><fieldset class="mode"><legend>Video source</legend><div class="mode-options" role="group" aria-label="Video input mode" data-mode-group="videoMode"><button class="mode-button" type="button" data-mode="url" aria-pressed="true">URL</button><button class="mode-button" type="button" data-mode="file" aria-pressed="false">File</button></div></fieldset><div id="videoUrlMode"><label for="video">Video URL</label><div class="url-row"><input id="video" type="url" autocomplete="url" maxlength="4096" placeholder="https://example.com/video.mp4"><button type="button" data-clear-url="video">Clear</button><button type="button" data-paste-url="video">Paste</button></div><button id="videoHistoryToggle" class="history-toggle" type="button" aria-expanded="false" aria-controls="videoHistory">Recent links</button><div id="videoHistory" class="history-list" aria-live="polite" hidden></div></div><div id="videoFileMode" hidden><label for="videoFile">Video file</label><input id="videoFile" class="file-input" type="file" accept=".mp4,.m4v,.mov,.3gp,.mkv,.webm,.ts,.m2ts,.mpg,.mpeg" disabled><button class="dropzone" type="button" data-dropzone="video" aria-controls="videoFile"><strong>Tap to choose a video</strong><small>or drop an MP4, MOV, MKV, WebM, TS, MPG, or MPEG file</small></button><div class="file-actions"><small id="videoFileName">No video selected</small><button class="file-clear" type="button" data-clear-file="video">Clear</button></div></div><small id="videoLimit"></small></section>
            <button id="subtitleToggle" type="button" aria-expanded="false" aria-controls="subtitleSection">Add subtitle</button>
            <section id="subtitleSection" class="choice" hidden aria-labelledby="subtitleHeading"><strong id="subtitleHeading">Subtitle</strong><fieldset class="mode"><legend>Subtitle source</legend><div class="mode-options" role="group" aria-label="Subtitle input mode" data-mode-group="subtitleMode"><button class="mode-button" type="button" data-mode="url" aria-pressed="true" disabled>URL</button><button class="mode-button" type="button" data-mode="file" aria-pressed="false" disabled>File</button></div></fieldset><div id="subtitleUrlMode"><label for="subtitle">Subtitle URL</label><div class="url-row"><input id="subtitle" type="url" maxlength="4096" placeholder="https://example.com/subtitles.srt" disabled><button type="button" data-clear-url="subtitle" disabled>Clear</button><button type="button" data-paste-url="subtitle" disabled>Paste</button></div><strong class="history-heading">Recent subtitle links</strong><div id="subtitleHistory" class="history-list" aria-live="polite"></div></div><div id="subtitleFileMode" hidden><label for="subtitleFile">Subtitle file</label><input id="subtitleFile" class="file-input" type="file" accept=".srt,.vtt" disabled><button class="dropzone" type="button" data-dropzone="subtitle" aria-controls="subtitleFile"><strong>Tap to choose a subtitle</strong><small>or drop an SRT or WebVTT file</small></button><div class="file-actions"><small id="subtitleFileName">No subtitle selected</small><button class="file-clear" type="button" data-clear-file="subtitle">Clear</button></div></div><small>SRT or WebVTT · Max 8 MiB</small></section>
            <button id="clearUrlHistory" type="button">Clear recent links</button>
            <button id="send" disabled>Send to TV</button><button id="cancel" type="button" hidden>Cancel upload</button><progress id="progress" max="100" value="0" hidden></progress><p id="status" role="status" aria-live="polite"></p></main>
            <script>
            history.replaceState(null,'',location.pathname);
            let pairingToken=null;let activeRequest=null;let limitsLoaded=false;let limits={videoBytes:0,subtitleBytes:8*1024*1024};
            let formBusy=false;
            const status=document.querySelector('#status');
            const urlHistoryKey='tv-player.phone-url-history.v1';const urlHistoryTtl=24*60*60*1000;const urlHistoryLimit=20;
            function loadUrlHistory(){let raw=[];try{raw=JSON.parse(localStorage.getItem(urlHistoryKey)||'[]')}catch(_){return []}if(!Array.isArray(raw))return [];const now=Date.now(),seen=new Set(),valid=[];for(const entry of raw){if(!entry||!['video','subtitle'].includes(entry.kind)||typeof entry.url!=='string'||entry.url.length>4096||!Number.isFinite(entry.savedAt))continue;const age=now-entry.savedAt;if(age<0||age>=urlHistoryTtl)continue;let parsed;try{parsed=new URL(entry.url)}catch(_){continue}if(!['http:','https:'].includes(parsed.protocol))continue;const key=entry.kind+'\n'+entry.url;if(seen.has(key))continue;seen.add(key);valid.push({kind:entry.kind,url:entry.url,savedAt:entry.savedAt})}valid.sort((a,b)=>b.savedAt-a.savedAt);const bounded=valid.slice(0,urlHistoryLimit);saveUrlHistory(bounded);return bounded}
            function saveUrlHistory(entries){try{localStorage.setItem(urlHistoryKey,JSON.stringify(entries))}catch(_){}}
            function urlHistoryLabel(raw){try{const parsed=new URL(raw),parts=parsed.pathname.split('/').filter(Boolean);let file=parts.length?parts[parts.length-1]:'';try{file=decodeURIComponent(file)}catch(_){}file=file.replace(/[\u0000-\u001f\u007f]/g,'');const label=file?parsed.hostname+' / '+file:parsed.hostname;return label.length>110?label.slice(0,107)+'…':label}catch(_){return 'Saved link'}}
            function updateHistoryControls(entries){const videoExpanded=document.querySelector('#videoHistoryToggle').getAttribute('aria-expanded')==='true',subtitleVisible=!document.querySelector('#subtitleSection').hidden&&selectedMode('subtitleMode')==='url',hasVideo=entries.some(entry=>entry.kind==='video'),hasSubtitle=entries.some(entry=>entry.kind==='subtitle');document.querySelector('#clearUrlHistory').hidden=!(videoExpanded&&hasVideo||subtitleVisible&&hasSubtitle)}
            function renderUrlHistory(){const entries=loadUrlHistory();for(const kind of ['video','subtitle']){const list=document.querySelector('#'+kind+'History');list.replaceChildren();const matching=entries.filter(entry=>entry.kind===kind);if(!matching.length){const empty=document.createElement('small');empty.textContent='No links yet.';list.appendChild(empty);continue}for(const entry of matching){const button=document.createElement('button');button.type='button';button.className='history-link';button.dataset.historyKind=kind;button.disabled=formBusy||(kind==='subtitle'&&document.querySelector('#subtitleSection').hidden);button.textContent=urlHistoryLabel(entry.url);button.setAttribute('aria-label','Use recent '+kind+' URL: '+button.textContent);button.addEventListener('click',()=>{if(formBusy||(kind==='subtitle'&&document.querySelector('#subtitleSection').hidden))return;const latest=loadUrlHistory().find(item=>item.kind===entry.kind&&item.url===entry.url&&item.savedAt===entry.savedAt);if(!latest){renderUrlHistory();status.textContent='That recent URL expired or was cleared. Send it again to add it to history.';return}if(kind==='subtitle')setSubtitleOpen(true);setMode(kind,'url',true);document.querySelector('#'+kind).value=latest.url;if(kind==='video')setVideoHistoryOpen(false);updateSendAvailability();if(kind==='subtitle'&&!hasReadyVideoSource()){document.querySelector('#video').focus();status.textContent='Subtitle URL filled. Choose a valid video URL or video file, then press Send to TV when ready.';return}document.querySelector('#send').focus();status.textContent=kind==='video'?'Recent video selected. Add a subtitle if needed, then press Send to TV when ready.':'Recent subtitle selected. Press Send to TV when ready.'});list.appendChild(button)}}updateHistoryControls(entries)}
            function rememberUrl(kind,url){if(!url)return;const entries=loadUrlHistory().filter(entry=>!(entry.kind===kind&&entry.url===url));entries.unshift({kind:kind,url:url,savedAt:Date.now()});saveUrlHistory(entries.slice(0,urlHistoryLimit));renderUrlHistory()}
            function selectedMode(name){return document.querySelector('[data-mode-group="'+name+'"] .mode-button[aria-pressed="true"]').dataset.mode}
            function applyMode(kind,mode){const urlField=document.querySelector('#'+kind),fileField=document.querySelector('#'+kind+'File'),urlWrap=document.querySelector('#'+kind+'UrlMode'),fileWrap=document.querySelector('#'+kind+'FileMode'),urlActive=mode==='url',enabled=kind!=='subtitle'||!document.querySelector('#subtitleSection').hidden;urlWrap.hidden=!urlActive;fileWrap.hidden=urlActive;urlField.disabled=!urlActive||!enabled;fileField.disabled=urlActive||!enabled;document.querySelectorAll('[data-clear-url="'+kind+'"],[data-paste-url="'+kind+'"],[data-clear-file="'+kind+'"],[data-dropzone="'+kind+'"]').forEach(button=>button.disabled=formBusy||!enabled||button.matches('[data-clear-url],[data-paste-url]')&&!urlActive||button.matches('[data-clear-file],[data-dropzone]')&&urlActive)}
            function setVideoHistoryOpen(open){const toggle=document.querySelector('#videoHistoryToggle'),list=document.querySelector('#videoHistory');list.hidden=!open;toggle.setAttribute('aria-expanded',String(open));updateHistoryControls(loadUrlHistory())}
            function updateSendAvailability(){const hasSource=selectedMode('videoMode')==='url'?document.querySelector('#video').value.trim().length>0:document.querySelector('#videoFile').files.length>0;document.querySelector('#send').disabled=formBusy||!hasSource}
            function hasReadyVideoSource(){if(selectedMode('videoMode')==='file')return document.querySelector('#videoFile').files.length>0;const value=document.querySelector('#video').value.trim();if(!value)return false;try{const parsed=new URL(value);return parsed.protocol==='http:'||parsed.protocol==='https:'}catch(_){return false}}
            function setMode(kind,mode,clearInactive){const urlField=document.querySelector('#'+kind),fileField=document.querySelector('#'+kind+'File'),urlActive=mode==='url',subtitleHidden=kind==='subtitle'&&document.querySelector('#subtitleSection').hidden;document.querySelectorAll('[data-mode-group="'+kind+'Mode"] .mode-button').forEach(button=>{button.setAttribute('aria-pressed',String(button.dataset.mode===mode));button.disabled=formBusy||subtitleHidden});applyMode(kind,mode);if(clearInactive){if(urlActive){fileField.value='';updateFileName(kind)}else{urlField.value=''}document.querySelector('#progress').value=0;cancelStaged()}if(kind==='video'){if(!urlActive||urlField.value.trim())setVideoHistoryOpen(false);updateSendAvailability()}else updateHistoryControls(loadUrlHistory())}
            function setSubtitleOpen(open){if(formBusy)return;const section=document.querySelector('#subtitleSection'),toggle=document.querySelector('#subtitleToggle');section.hidden=!open;toggle.setAttribute('aria-expanded',String(open));toggle.textContent=open?'Remove subtitle':'Add subtitle';toggle.disabled=formBusy;document.querySelectorAll('#subtitleHistory .history-link').forEach(button=>button.disabled=formBusy||!open);if(open){setMode('subtitle',selectedMode('subtitleMode'),false)}else{document.querySelector('#subtitle').value='';document.querySelector('#subtitleFile').value='';updateFileName('subtitle');setMode('subtitle','url',false);cancelStaged()}updateHistoryControls(loadUrlHistory())}
            function setFormBusy(busy){formBusy=busy;document.querySelector('#clearUrlHistory').disabled=busy;document.querySelector('#videoHistoryToggle').disabled=busy;document.querySelector('#subtitleToggle').disabled=busy;document.querySelectorAll('.history-link').forEach(button=>button.disabled=busy||(button.dataset.historyKind==='subtitle'&&document.querySelector('#subtitleSection').hidden));document.querySelectorAll('.mode-button').forEach(button=>button.disabled=busy||(button.closest('[data-mode-group="subtitleMode"]')&&document.querySelector('#subtitleSection').hidden));applyMode('video',selectedMode('videoMode'));applyMode('subtitle',selectedMode('subtitleMode'));if(busy){document.querySelectorAll('#video,#videoFile,#subtitle,#subtitleFile').forEach(input=>input.disabled=true);document.querySelectorAll('[data-clear-url],[data-paste-url],[data-clear-file],[data-dropzone]').forEach(button=>button.disabled=true)}updateSendAvailability()}
            document.querySelectorAll('[data-mode-group="videoMode"] .mode-button').forEach(button=>button.addEventListener('click',()=>{if(!formBusy)setMode('video',button.dataset.mode,true)}));
            document.querySelectorAll('[data-mode-group="subtitleMode"] .mode-button').forEach(button=>button.addEventListener('click',()=>{if(!formBusy)setMode('subtitle',button.dataset.mode,true)}));
            setMode('video','url',false);setMode('subtitle','url',false);
            document.querySelector('#subtitleToggle').addEventListener('click',()=>setSubtitleOpen(document.querySelector('#subtitleSection').hidden));
            document.querySelector('#videoHistoryToggle').addEventListener('click',()=>setVideoHistoryOpen(document.querySelector('#videoHistory').hidden));
            document.querySelector('#video').addEventListener('input',()=>{if(document.querySelector('#video').value.trim())setVideoHistoryOpen(false);updateSendAvailability()});
            document.querySelector('#videoFile').addEventListener('change',()=>{if(document.querySelector('#videoFile').files.length)setVideoHistoryOpen(false);updateSendAvailability()});
            function updateFileName(kind){const file=document.querySelector('#'+kind+'File').files[0];document.querySelector('#'+kind+'FileName').textContent=file?file.name+' · '+fmt(file.size):kind==='video'?'No video selected':'No subtitle selected'}
            function allowedFile(kind,file){const extension=file.name.toLowerCase().split('.').pop(),allowed=kind==='video'?['mp4','m4v','mov','3gp','mkv','webm','ts','m2ts','mpg','mpeg']:['srt','vtt'];return allowed.includes(extension)}
            function setDroppedFile(kind,file){if(formBusy||kind==='subtitle'&&document.querySelector('#subtitleSection').hidden||selectedMode(kind+'Mode')!=='file')return;if(!allowedFile(kind,file)){status.textContent=kind==='video'?'Choose a supported video file (MP4, M4V, MOV, 3GP, MKV, WebM, TS, M2TS, MPG, or MPEG).':'Choose an SRT or WebVTT subtitle file.';return}if(kind==='subtitle'&&file.size>limits.subtitleBytes){status.textContent='Subtitle files are limited to 8 MiB.';return}if(kind==='video'&&limitsLoaded&&file.size>limits.videoBytes){status.textContent='Video files are limited to '+fmt(limits.videoBytes)+'.';return}try{const transfer=new DataTransfer();transfer.items.add(file);document.querySelector('#'+kind+'File').files=transfer.files;document.querySelector('#'+kind+'File').dispatchEvent(new Event('change',{bubbles:true}));updateFileName(kind);status.textContent=file.name+' selected. Press Send to TV when ready.'}catch(_){status.textContent='This browser cannot use the dropped file. Tap the file area to choose it instead.'}}
            document.querySelectorAll('[data-dropzone]').forEach(zone=>{const kind=zone.dataset.dropzone,input=document.querySelector('#'+kind+'File');zone.addEventListener('click',()=>{if(!zone.disabled)input.click()});zone.addEventListener('keydown',event=>{if(!zone.disabled&&(event.key==='Enter'||event.key===' ')){event.preventDefault();input.click()}});zone.addEventListener('dragover',event=>{event.preventDefault();if(!zone.disabled)zone.classList.add('dragging')});zone.addEventListener('dragleave',()=>zone.classList.remove('dragging'));zone.addEventListener('drop',event=>{event.preventDefault();zone.classList.remove('dragging');if(!zone.disabled&&event.dataTransfer.files.length)setDroppedFile(kind,event.dataTransfer.files[0])});input.addEventListener('change',()=>{let file=input.files[0];if(file&&!allowedFile(kind,file)){input.value='';file=null;status.textContent=kind==='video'?'Choose a supported video file.':'Choose an SRT or WebVTT subtitle file.'}if(file&&kind==='subtitle'&&file.size>limits.subtitleBytes){input.value='';file=null;status.textContent='Subtitle files are limited to 8 MiB.'}if(file&&kind==='video'&&limitsLoaded&&file.size>limits.videoBytes){input.value='';file=null;status.textContent='Video files are limited to '+fmt(limits.videoBytes)+'.'}updateFileName(kind);if(file){if(kind==='video')setVideoHistoryOpen(false);status.textContent=file.name+' selected. Press Send to TV when ready.'}updateSendAvailability()})});
            document.querySelectorAll('[data-clear-url]').forEach(button=>button.addEventListener('click',()=>{const kind=button.dataset.clearUrl,field=document.querySelector('#'+kind);field.value='';field.focus();if(kind==='video')updateSendAvailability();status.textContent=(kind==='video'?'Video':'Subtitle')+' URL cleared.'}));
            document.querySelectorAll('[data-paste-url]').forEach(button=>button.addEventListener('click',async()=>{const kind=button.dataset.pasteUrl,field=document.querySelector('#'+kind);field.focus();if(!navigator.clipboard||typeof navigator.clipboard.readText!=='function'){status.textContent='Clipboard access is unavailable on this HTTP link. Tap the URL field and paste manually.';return}try{const value=await navigator.clipboard.readText();field.value=value;if(kind==='video'){if(value.trim())setVideoHistoryOpen(false);updateSendAvailability()}field.dispatchEvent(new Event('input',{bubbles:true}));status.textContent=value?'Clipboard text pasted.':'Clipboard is empty.'}catch(_){status.textContent='Clipboard access was denied or blocked on this HTTP link. Tap the URL field and paste manually.'}}));
            document.querySelectorAll('[data-clear-file]').forEach(button=>button.addEventListener('click',()=>{const kind=button.dataset.clearFile,input=document.querySelector('#'+kind+'File');input.value='';updateFileName(kind);updateSendAvailability();status.textContent=(kind==='video'?'Video':'Subtitle')+' file cleared.'}));
            setSubtitleOpen(false);
            renderUrlHistory();
            document.querySelector('#clearUrlHistory').addEventListener('click',()=>{saveUrlHistory([]);renderUrlHistory();status.textContent='Recent URL history cleared from this browser.'});
            function fmt(n){return n>=1024**3?(n/1024**3).toFixed(1)+' GiB':Math.floor(n/1024**2)+' MiB'}
            function setTransferVisible(visible){document.querySelector('#cancel').hidden=!visible;document.querySelector('#progress').hidden=!visible}
            async function pair(){if(!pairingToken){status.textContent='Waiting for approval on the TV…';const res=await fetch('/api/pair',{method:'POST',cache:'no-store'});if(!res.ok)throw Error(await res.text());pairingToken=(await res.json()).token}if(!limitsLoaded){const lr=await fetch('/api/limits',{headers:{'X-Pairing-Token':pairingToken},cache:'no-store'});if(!lr.ok)throw Error(await lr.text());limits=await lr.json();limitsLoaded=true;document.querySelector('#videoLimit').textContent='TV upload limit: '+fmt(limits.videoBytes);document.querySelector('#videoLimit').hidden=false}}
            function postFile(file,path,limit){return new Promise((resolve,reject)=>{if(file.size>limit){reject(Error('File is larger than the current TV limit ('+fmt(limit)+').'));return}setTransferVisible(true);const xhr=new XMLHttpRequest();activeRequest=xhr;xhr.open('POST',path);xhr.setRequestHeader('X-Pairing-Token',pairingToken);xhr.setRequestHeader('Content-Type','application/octet-stream');xhr.setRequestHeader('X-Upload-Filename',encodeURIComponent(file.name));xhr.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.floor(e.loaded/e.total*100);document.querySelector('#progress').value=p;status.textContent='Uploading '+file.name+' · '+p+'%'}};xhr.onload=()=>{activeRequest=null;if(xhr.status>=200&&xhr.status<300)resolve();else reject(Error(xhr.responseText||'Upload failed'))};xhr.onerror=()=>{activeRequest=null;reject(Error('Upload interrupted. Check the network and try again.'))};xhr.onabort=()=>{activeRequest=null;reject(Error('Upload cancelled.'))};xhr.send(file)})}
            async function cancelStaged(){if(pairingToken)await fetch('/api/cancel-upload',{method:'POST',headers:{'X-Pairing-Token':pairingToken},cache:'no-store'}).catch(()=>{})}
            async function submitSend(){if(formBusy)return;const videoMode=selectedMode('videoMode'),subtitleMode=selectedMode('subtitleMode'),videoUrl=videoMode==='url'?document.querySelector('#video').value.trim():'',videoFile=videoMode==='file'?document.querySelector('#videoFile').files[0]:null,subtitleUrl=subtitleMode==='url'?document.querySelector('#subtitle').value.trim():'',subtitleFile=subtitleMode==='file'?document.querySelector('#subtitleFile').files[0]:null;if(videoMode==='url'&&!videoUrl){status.textContent='Enter a video URL.';return}if(videoMode==='file'&&!videoFile){status.textContent='Choose a video file.';return}if(subtitleMode==='file'&&!subtitleFile){status.textContent='Choose a subtitle file or switch to URL mode to omit the subtitle.';return}if(subtitleFile&&subtitleFile.size>limits.subtitleBytes){status.textContent='Subtitle files are limited to 8 MiB.';return}setFormBusy(true);document.querySelector('#progress').value=0;try{await pair();if(videoFile)await postFile(videoFile,'/api/upload/video',limits.videoBytes);if(subtitleFile)await postFile(subtitleFile,'/api/upload/subtitle',limits.subtitleBytes);const res=await fetch('/api/play',{method:'POST',headers:{'Content-Type':'application/json','X-Pairing-Token':pairingToken},body:JSON.stringify({videoUrl:videoUrl,subtitleUrl:subtitleUrl,uploadedVideo:Boolean(videoFile),uploadedSubtitle:Boolean(subtitleFile)})});if(res.status===401){pairingToken=null;limitsLoaded=false;throw Error('TV session ended. Send again and approve the new connection on the TV.')}if(!res.ok)throw Error(await res.text());rememberUrl('video',videoUrl);rememberUrl('subtitle',subtitleUrl);status.textContent=await res.text();document.querySelector('#videoFile').value='';document.querySelector('#subtitleFile').value=''}catch(e){await cancelStaged();status.textContent=e.message||'Could not reach the TV. Check that both devices are on the same network.'}finally{setFormBusy(false);activeRequest=null;setTransferVisible(false)}}
            document.querySelector('#send').onclick=submitSend;
            document.querySelector('#cancel').onclick=async()=>{if(activeRequest)activeRequest.abort();await cancelStaged();document.querySelector('#videoFile').value='';document.querySelector('#subtitleFile').value='';setTransferVisible(false);status.textContent='Temporary uploads cancelled and cleared.'};
            </script></html>
        """.trimIndent()
    }
}
