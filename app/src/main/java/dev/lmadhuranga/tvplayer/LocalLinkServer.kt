package dev.lmadhuranga.tvplayer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    val pairingCode: String = "",
    val isRunning: Boolean = false,
    val message: String? = null,
    val pairingExpiresAtMs: Long = 0L,
    val pairingAvailableAtMs: Long = 0L,
) {
    val pageUrl: String? get() = address?.let { "http://$it:$HANDOFF_PORT/#pin=$pairingCode" }
}

private const val HANDOFF_PORT = 8765
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 12 * 1024
private const val MAX_VIDEO_UPLOAD_BYTES = 8L * 1024 * 1024 * 1024
private const val MAX_SUBTITLE_UPLOAD_BYTES = 8L * 1024 * 1024
private const val CACHE_RESERVE_BYTES = 512L * 1024 * 1024
private const val UPLOAD_READ_TIMEOUT_MS = 120_000
private const val PIN_LIFETIME_MS = 5 * 60 * 1000L
private const val PIN_COOLDOWN_MS = 30 * 1000L
private const val MAX_PIN_FAILURES = 5

/** Foreground-only paired handoff. Link requests stay small; optional files stream to private cache. */
class LocalLinkServer(
    private val cacheDirectory: File,
    private val onVideoSubmitted: (SubmittedVideo) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(HandoffState())
    val state: StateFlow<HandoffState> = mutableState.asStateFlow()
    private val stopped = AtomicBoolean(true)
    private val generation = AtomicLong(0L)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var pairingCode: String = ""
    @Volatile private var sessionToken: String = ""
    @Volatile private var pairingExpiresAtMs: Long = 0L
    @Volatile private var pairingAvailableAtMs: Long = 0L
    private var failedPinAttempts = 0
    private val uploadDirectory = File(cacheDirectory, "handoff-uploads").apply { mkdirs() }
    private val playbackUploadDirectory = File(cacheDirectory, "playback-uploads").apply { mkdirs() }
    private val uploadLock = Any()
    private val stagedUploads = mutableMapOf<UploadKind, StagedUpload>()
    private val inFlightUploads = mutableSetOf<File>()

    private enum class UploadKind { VIDEO, SUBTITLE }
    private data class StagedUpload(val file: File, val name: String, val token: String)

    @Synchronized
    fun start() {
        if (!stopped.compareAndSet(true, false)) return
        clearUploads()
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
            val listener = ServerSocket().apply {
                // Permit a quick same-process restart after accepted connections close.
                // This does not allow two live listeners to own the port.
                reuseAddress = true
                soTimeout = 0
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), HANDOFF_PORT), 4)
            }
            socket = listener
            server = listener
            rotatePin(lanAddress)
            Thread({ acceptLoop(listener, runId) }, "tv-player-link-server").apply { isDaemon = true; start() }
        } catch (_: BindException) {
            failStart(socket, "Port $HANDOFF_PORT is already in use by another app. Close that app and retry link handoff.")
        } catch (error: Exception) {
            val reason = error.localizedMessage
                ?.filterNot(Char::isISOControl)
                ?.take(160)
                ?.ifBlank { "Unknown startup error" }
                ?: "Unknown startup error"
            failStart(socket, "The link handoff could not start: $reason. Check the TV network connection and retry.")
        }
    }

    private fun failStart(socket: ServerSocket?, message: String) {
        try { socket?.close() } catch (_: Exception) { }
        server = null
        stopped.set(true)
        generation.incrementAndGet()
        pairingCode = ""
        sessionToken = ""
        pairingExpiresAtMs = 0L
        pairingAvailableAtMs = 0L
        mutableState.value = HandoffState(message = message)
    }

    @Synchronized
    fun stop() {
        if (stopped.getAndSet(true)) return
        generation.incrementAndGet()
        pairingCode = ""
        sessionToken = ""
        pairingExpiresAtMs = 0L
        pairingAvailableAtMs = 0L
        try { activeSocket?.close() } catch (_: Exception) { }
        activeSocket = null
        val current = server
        server = null
        try { current?.close() } catch (_: Exception) { }
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
            activeSocket = client
            try {
                client.use { socket ->
                    socket.soTimeout = 4_000
                    if (!isCurrent(listener, runId)) return
                    if (isAllowedPeer(socket.inetAddress)) handleRequest(socket, listener, runId)
                    else respond(socket.getOutputStream(), 403, "Forbidden", "Local network only")
                }
            } catch (_: SocketTimeoutException) {
                // Drop partial requests without affecting the next local client.
            } catch (_: Exception) {
                // The peer may close early. Continue accepting only while this server generation is current.
            } finally {
                if (activeSocket === client) activeSocket = null
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
        activeSocket = null
        pairingCode = ""
        sessionToken = ""
        try { listener.close() } catch (_: Exception) { }
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
        val path = request[1].substringBefore('?')
        if (method == "GET" && path == "/") return respond(socket.getOutputStream(), 200, "OK", LINK_ENTRY_PAGE, "text/html; charset=utf-8")
        if (method == "POST" && path == "/api/pair") {
            if (!isSameOrigin(headers["origin"], socket.localAddress)) return respond(socket.getOutputStream(), 403, "Forbidden", "Open the link page on this TV's local network")
            val pin = headers["x-pairing-pin"].orEmpty()
            return exchangePin(socket, listener, runId, pin)
        }
        if (method == "GET" && path == "/api/limits") {
            // Same-origin GET fetches commonly omit Origin. The paired token still
            // authenticates this read-only limit lookup; reject any supplied mismatch.
            if (headers["origin"]?.let { !isSameOrigin(it, socket.localAddress) } == true) {
                return respond(socket.getOutputStream(), 403, "Forbidden", "Open the link page on this TV's local network")
            }
            if (!isAuthorized(headers["x-pairing-token"].orEmpty())) return respond(socket.getOutputStream(), 401, "Unauthorized", "Pair this browser with the code shown on the TV")
            return respond(socket.getOutputStream(), 200, "OK", JSONObject(mapOf(
                "videoBytes" to effectiveUploadLimit(UploadKind.VIDEO),
                "subtitleBytes" to effectiveUploadLimit(UploadKind.SUBTITLE),
            )).toString(), "application/json; charset=utf-8")
        }
        if (method != "POST" || path !in setOf("/api/play", "/api/upload/video", "/api/upload/subtitle", "/api/cancel-upload")) return respond(socket.getOutputStream(), 404, "Not Found", "Not found")
        if (!isSameOrigin(headers["origin"], socket.localAddress)) return respond(socket.getOutputStream(), 403, "Forbidden", "Open the link page on this TV's local network")
        val requestToken = headers["x-pairing-token"].orEmpty()
        if (!isAuthorized(requestToken)) {
            return respond(socket.getOutputStream(), 401, "Unauthorized", "Pair this browser with the code shown on the TV")
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

    @Synchronized
    private fun exchangePin(socket: Socket, listener: ServerSocket, runId: Long, pin: String) {
        val now = SystemClock.elapsedRealtime()
        val output = socket.getOutputStream()
        if (!isCurrent(listener, runId)) return respond(output, 410, "Gone", "The TV pairing session has ended")
        if (now < pairingAvailableAtMs) return respond(output, 429, "Too Many Requests", "Wait briefly before trying a new code")
        if (pairingCode.isEmpty()) {
            return respond(output, 409, "Conflict", "This code was already used. Generate a new code on the TV to pair another browser")
        }
        if (now >= pairingExpiresAtMs) {
            rotatePin(mutableState.value.address)
            return respond(output, 410, "Gone", "This code expired. Use the new code shown on the TV")
        }
        if (pin.length != 4 || pin.any { !it.isDigit() } || !MessageDigest.isEqual(
                pairingCode.toByteArray(StandardCharsets.US_ASCII), pin.toByteArray(StandardCharsets.US_ASCII)
            )) {
            failedPinAttempts++
            if (failedPinAttempts >= MAX_PIN_FAILURES) {
                pairingAvailableAtMs = now + PIN_COOLDOWN_MS
                rotatePin(mutableState.value.address, preserveCooldown = true)
            }
            return respond(output, 401, "Unauthorized", "That code is not correct")
        }
        pairingCode = "" // The short PIN is one-use; subsequent submissions require the exchanged token.
        failedPinAttempts = 0
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        sessionToken = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        publishPairingState()
        respond(output, 200, "OK", JSONObject(mapOf("token" to sessionToken)).toString(), "application/json; charset=utf-8")
    }

    @Synchronized
    fun regeneratePin() {
        if (stopped.get()) return
        rotatePin(mutableState.value.address)
    }

    private fun rotatePin(address: String?, preserveCooldown: Boolean = false) {
        pairingCode = SecureRandom().nextInt(10_000).toString().padStart(4, '0')
        pairingExpiresAtMs = SystemClock.elapsedRealtime() + PIN_LIFETIME_MS
        sessionToken = ""
        failedPinAttempts = 0
        if (!preserveCooldown) pairingAvailableAtMs = 0L
        publishPairingState(address)
    }

    private fun publishPairingState(address: String? = mutableState.value.address) {
        mutableState.value = HandoffState(
            address = address,
            pairingCode = pairingCode,
            isRunning = !stopped.get(),
            pairingExpiresAtMs = pairingExpiresAtMs,
            pairingAvailableAtMs = pairingAvailableAtMs,
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
            "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private companion object {
        val LINK_ENTRY_PAGE = """
            <!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta charset="utf-8"><title>TV Player</title>
            <style>body{font:18px system-ui;max-width:680px;margin:2rem auto;padding:0 1rem;background:#07111f;color:#fff}label{display:block;margin:1rem 0 .35rem}input{box-sizing:border-box;width:100%;padding:.85rem;border-radius:.6rem;border:1px solid #6b829d;font:inherit}input[type=file]{padding:.5rem}.mode{border:0;padding:0;margin:.5rem 0}.mode legend{font-size:.9rem;color:#b9c8d9}.mode label{display:inline-flex;align-items:center;gap:.4rem;margin:.3rem 1rem .3rem 0}.mode input{width:auto;padding:0}button{margin-top:1.2rem;padding:.8rem 1.2rem;border:0;border-radius:.6rem;background:#fff;color:#111;font:inherit;font-weight:700}.history-list{display:grid;gap:.4rem;margin-top:.5rem}.history-list button{width:100%;margin:0;text-align:left;overflow-wrap:anywhere}small{color:#b9c8d9}.choice{margin:.35rem 0 1rem;padding:.75rem;border:1px solid #34465c;border-radius:.6rem}#status{min-height:2rem;white-space:pre-wrap}progress{width:100%;height:1rem}</style>
            <h1>Send a video to your TV</h1><p>Choose a direct video URL or upload a video file. The TV downloads URL media directly; uploaded files are sent to temporary TV storage and removed after playback. Successful URLs are kept only in this browser for 24 hours; signed links may expire sooner. Clear history to remove them earlier.</p>
            <label for="code">4-digit TV pairing code</label><input id="code" inputmode="numeric" pattern="[0-9]{4}" maxlength="4" autocomplete="one-time-code" required>
            <section class="choice"><strong>Video</strong><fieldset class="mode"><legend>Send video by</legend><label><input name="videoMode" type="radio" value="url" checked> URL</label><label><input name="videoMode" type="radio" value="file"> File upload</label></fieldset><div id="videoUrlMode"><label for="video">Video URL</label><input id="video" type="url" autocomplete="url" maxlength="4096" placeholder="https://example.com/video.mp4"><div><strong>Recent video URLs (24 hours)</strong><div id="videoHistory" class="history-list" aria-live="polite"></div></div></div><div id="videoFileMode" hidden><label for="videoFile">Video file</label><input id="videoFile" type="file" accept=".mp4,.m4v,.mov,.3gp,.mkv,.webm,.ts,.m2ts,.mpg,.mpeg" disabled></div><small id="videoLimit">Pair with the TV to check its current upload limit.</small></section>
            <section class="choice"><strong>Subtitle (optional)</strong><fieldset class="mode"><legend>Send subtitle by</legend><label><input name="subtitleMode" type="radio" value="url" checked> URL</label><label><input name="subtitleMode" type="radio" value="file"> File upload</label></fieldset><div id="subtitleUrlMode"><label for="subtitle">Subtitle URL (SRT or WebVTT)</label><input id="subtitle" type="url" maxlength="4096" placeholder="https://example.com/subtitles.srt"><div><strong>Recent subtitle URLs (24 hours)</strong><div id="subtitleHistory" class="history-list" aria-live="polite"></div></div></div><div id="subtitleFileMode" hidden><label for="subtitleFile">Subtitle file</label><input id="subtitleFile" type="file" accept=".srt,.vtt" disabled></div><small>Subtitle files are limited to 8 MiB and must be SRT or WebVTT.</small></section>
            <button id="clearUrlHistory" type="button">Clear recent URL history</button><br>
            <button id="send">Send to TV</button> <button id="cancel" type="button">Cancel upload</button><progress id="progress" max="100" value="0"></progress><p id="status" role="status"></p>
            <script>
            const params=new URLSearchParams(location.hash.slice(1));const pin=params.get('pin');history.replaceState(null,'',location.pathname);
            if(pin&&/^[0-9]{4}$/.test(pin))document.querySelector('#code').value=pin;
            let pairingToken=null;let activeRequest=null;let limitsLoaded=false;let limits={videoBytes:0,subtitleBytes:8*1024*1024};
            let formBusy=false;
            const status=document.querySelector('#status');
            const urlHistoryKey='tv-player.phone-url-history.v1';const urlHistoryTtl=24*60*60*1000;const urlHistoryLimit=20;
            function loadUrlHistory(){let raw=[];try{raw=JSON.parse(localStorage.getItem(urlHistoryKey)||'[]')}catch(_){return []}if(!Array.isArray(raw))return [];const now=Date.now(),seen=new Set(),valid=[];for(const entry of raw){if(!entry||!['video','subtitle'].includes(entry.kind)||typeof entry.url!=='string'||entry.url.length>4096||!Number.isFinite(entry.savedAt))continue;const age=now-entry.savedAt;if(age<0||age>=urlHistoryTtl)continue;let parsed;try{parsed=new URL(entry.url)}catch(_){continue}if(!['http:','https:'].includes(parsed.protocol))continue;const key=entry.kind+'\n'+entry.url;if(seen.has(key))continue;seen.add(key);valid.push({kind:entry.kind,url:entry.url,savedAt:entry.savedAt})}valid.sort((a,b)=>b.savedAt-a.savedAt);const bounded=valid.slice(0,urlHistoryLimit);saveUrlHistory(bounded);return bounded}
            function saveUrlHistory(entries){try{localStorage.setItem(urlHistoryKey,JSON.stringify(entries))}catch(_){}}
            function urlHistoryLabel(raw){try{const parsed=new URL(raw),parts=parsed.pathname.split('/').filter(Boolean);let file=parts.length?parts[parts.length-1]:'';try{file=decodeURIComponent(file)}catch(_){}file=file.replace(/[\u0000-\u001f\u007f]/g,'');const label=file?parsed.hostname+' / '+file:parsed.hostname;return label.length>110?label.slice(0,107)+'…':label}catch(_){return 'Saved link'}}
            function renderUrlHistory(){const entries=loadUrlHistory();for(const kind of ['video','subtitle']){const list=document.querySelector('#'+kind+'History');list.replaceChildren();const matching=entries.filter(entry=>entry.kind===kind);if(!matching.length){const empty=document.createElement('small');empty.textContent='No recent links on this browser.';list.appendChild(empty);continue}for(const entry of matching){const button=document.createElement('button');button.type='button';button.className='history-link';button.disabled=formBusy;button.textContent=urlHistoryLabel(entry.url);button.setAttribute('aria-label','Use recent '+kind+' URL: '+button.textContent);button.addEventListener('click',()=>{if(formBusy)return;const latest=loadUrlHistory().find(item=>item.kind===entry.kind&&item.url===entry.url&&item.savedAt===entry.savedAt);if(!latest){renderUrlHistory();status.textContent='That recent URL expired or was cleared. Send it again to add it to history.';return}document.querySelector('input[name="'+kind+'Mode"][value="url"]').checked=true;setMode(kind,'url',true);document.querySelector('#'+kind).value=latest.url;document.querySelector('#'+kind).focus();status.textContent=kind==='video'?'Video URL filled. Press Send to TV when ready.':'Subtitle URL filled. Press Send to TV when ready.'});list.appendChild(button)}}}
            function rememberUrl(kind,url){if(!url)return;const entries=loadUrlHistory().filter(entry=>!(entry.kind===kind&&entry.url===url));entries.unshift({kind:kind,url:url,savedAt:Date.now()});saveUrlHistory(entries.slice(0,urlHistoryLimit));renderUrlHistory()}
            function selectedMode(name){return document.querySelector('input[name="'+name+'"]:checked').value}
            function applyMode(kind,mode){const urlField=document.querySelector('#'+kind),fileField=document.querySelector('#'+kind+'File'),urlWrap=document.querySelector('#'+kind+'UrlMode'),fileWrap=document.querySelector('#'+kind+'FileMode'),urlActive=mode==='url';urlWrap.hidden=!urlActive;fileWrap.hidden=urlActive;urlField.disabled=!urlActive;fileField.disabled=urlActive}
            function setMode(kind,mode,clearInactive){const urlField=document.querySelector('#'+kind),fileField=document.querySelector('#'+kind+'File'),urlActive=mode==='url';applyMode(kind,mode);if(clearInactive){if(urlActive){fileField.value=''}else{urlField.value=''}document.querySelector('#progress').value=0;cancelStaged()}}
            function setFormBusy(busy){formBusy=busy;document.querySelector('#send').disabled=busy;document.querySelector('#clearUrlHistory').disabled=busy;document.querySelectorAll('.history-link').forEach(button=>button.disabled=busy);document.querySelectorAll('input[name="videoMode"],input[name="subtitleMode"]').forEach(input=>input.disabled=busy);applyMode('video',selectedMode('videoMode'));applyMode('subtitle',selectedMode('subtitleMode'));if(busy){document.querySelectorAll('#video,#videoFile,#subtitle,#subtitleFile').forEach(input=>input.disabled=true)}}
            document.querySelectorAll('input[name="videoMode"]').forEach(input=>input.addEventListener('change',()=>{if(input.checked)setMode('video',input.value,true)}));
            document.querySelectorAll('input[name="subtitleMode"]').forEach(input=>input.addEventListener('change',()=>{if(input.checked)setMode('subtitle',input.value,true)}));
            setMode('video','url',false);setMode('subtitle','url',false);
            renderUrlHistory();
            document.querySelector('#clearUrlHistory').addEventListener('click',()=>{saveUrlHistory([]);renderUrlHistory();status.textContent='Recent URL history cleared from this browser.'});
            function fmt(n){return n>=1024**3?(n/1024**3).toFixed(1)+' GiB':Math.floor(n/1024**2)+' MiB'}
            async function pair(){if(!pairingToken){const res=await fetch('/api/pair',{method:'POST',cache:'no-store',headers:{'X-Pairing-Pin':document.querySelector('#code').value}});if(!res.ok)throw Error(await res.text());pairingToken=(await res.json()).token}if(!limitsLoaded){const lr=await fetch('/api/limits',{headers:{'X-Pairing-Token':pairingToken},cache:'no-store'});if(!lr.ok)throw Error(await lr.text());limits=await lr.json();limitsLoaded=true;document.querySelector('#videoLimit').textContent='Current TV limit: '+fmt(limits.videoBytes)+' (up to 8 GiB, keeping 512 MiB free). The limit can change with TV storage.'}}
            function postFile(file,path,limit){return new Promise((resolve,reject)=>{if(file.size>limit){reject(Error('File is larger than the current TV limit ('+fmt(limit)+').'));return}const xhr=new XMLHttpRequest();activeRequest=xhr;xhr.open('POST',path);xhr.setRequestHeader('X-Pairing-Token',pairingToken);xhr.setRequestHeader('Content-Type','application/octet-stream');xhr.setRequestHeader('X-Upload-Filename',encodeURIComponent(file.name));xhr.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.floor(e.loaded/e.total*100);document.querySelector('#progress').value=p;status.textContent='Uploading '+file.name+' · '+p+'%'}};xhr.onload=()=>{activeRequest=null;if(xhr.status>=200&&xhr.status<300)resolve();else reject(Error(xhr.responseText||'Upload failed'))};xhr.onerror=()=>{activeRequest=null;reject(Error('Upload interrupted. Check the network and try again.'))};xhr.onabort=()=>{activeRequest=null;reject(Error('Upload cancelled.'))};xhr.send(file)})}
            async function cancelStaged(){if(pairingToken)await fetch('/api/cancel-upload',{method:'POST',headers:{'X-Pairing-Token':pairingToken},cache:'no-store'}).catch(()=>{})}
            document.querySelector('#send').onclick=async()=>{const videoMode=selectedMode('videoMode'),subtitleMode=selectedMode('subtitleMode'),videoUrl=videoMode==='url'?document.querySelector('#video').value.trim():'',videoFile=videoMode==='file'?document.querySelector('#videoFile').files[0]:null,subtitleUrl=subtitleMode==='url'?document.querySelector('#subtitle').value.trim():'',subtitleFile=subtitleMode==='file'?document.querySelector('#subtitleFile').files[0]:null;if(videoMode==='url'&&!videoUrl){status.textContent='Enter a video URL.';return}if(videoMode==='file'&&!videoFile){status.textContent='Choose a video file.';return}if(subtitleMode==='file'&&!subtitleFile){status.textContent='Choose a subtitle file or switch to URL mode to omit the subtitle.';return}if(subtitleFile&&subtitleFile.size>limits.subtitleBytes){status.textContent='Subtitle files are limited to 8 MiB.';return}setFormBusy(true);document.querySelector('#progress').value=0;try{await pair();if(videoFile)await postFile(videoFile,'/api/upload/video',limits.videoBytes);if(subtitleFile)await postFile(subtitleFile,'/api/upload/subtitle',limits.subtitleBytes);const res=await fetch('/api/play',{method:'POST',headers:{'Content-Type':'application/json','X-Pairing-Token':pairingToken},body:JSON.stringify({videoUrl:videoUrl,subtitleUrl:subtitleUrl,uploadedVideo:Boolean(videoFile),uploadedSubtitle:Boolean(subtitleFile)})});if(res.status===401){pairingToken=null;limitsLoaded=false;document.querySelector('#code').value='';throw Error('TV session ended. Enter the new 4-digit code shown on the TV, then send again.')}if(!res.ok)throw Error(await res.text());rememberUrl('video',videoUrl);rememberUrl('subtitle',subtitleUrl);status.textContent=await res.text();document.querySelector('#videoFile').value='';document.querySelector('#subtitleFile').value=''}catch(e){await cancelStaged();status.textContent=e.message||'Could not reach the TV. Check that both devices are on the same network.'}finally{setFormBusy(false);activeRequest=null}};
            document.querySelector('#cancel').onclick=async()=>{if(activeRequest)activeRequest.abort();await cancelStaged();document.querySelector('#videoFile').value='';document.querySelector('#subtitleFile').value='';status.textContent='Temporary uploads cancelled and cleared.'};
            </script></html>
        """.trimIndent()
    }
}
