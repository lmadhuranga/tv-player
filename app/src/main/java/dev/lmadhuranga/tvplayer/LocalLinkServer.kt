package dev.lmadhuranga.tvplayer

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SubmittedVideo(val videoUrl: String, val subtitleUrl: String?, val title: String? = null)

data class HandoffState(
    val address: String? = null,
    val pairingCode: String = "",
    val isRunning: Boolean = false,
    val message: String? = null,
) {
    val pageUrl: String? get() = address?.let { "http://$it:$HANDOFF_PORT/#pairing=$pairingCode" }
}

private const val HANDOFF_PORT = 8765
private const val MAX_HEADER_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 12 * 1024

/** Small foreground-only URL receiver. It accepts links, never media bytes. */
class LocalLinkServer(private val onVideoSubmitted: (SubmittedVideo) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(HandoffState())
    val state: StateFlow<HandoffState> = mutableState.asStateFlow()
    private val stopped = AtomicBoolean(true)
    private val generation = AtomicLong(0L)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var pairingCode: String = ""

    @Synchronized
    fun start() {
        if (!stopped.compareAndSet(true, false)) return
        val runId = generation.incrementAndGet()
        val lanAddress = findLanAddress()
        if (lanAddress == null) {
            stopped.set(true)
            mutableState.value = HandoffState(message = "Connect the TV to Wi-Fi or Ethernet to use link handoff.")
            return
        }
        val tokenBytes = ByteArray(32)
        SecureRandom().nextBytes(tokenBytes)
        pairingCode = Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        try {
            val socket = ServerSocket().apply {
                reuseAddress = false
                soTimeout = 0
                bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), HANDOFF_PORT), 4)
            }
            server = socket
            mutableState.value = HandoffState(address = lanAddress, pairingCode = pairingCode, isRunning = true)
            Thread({ acceptLoop(socket, runId) }, "tv-player-link-server").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            stopped.set(true)
            generation.incrementAndGet()
            pairingCode = ""
            mutableState.value = HandoffState(message = "The link handoff page could not start. Close another app using port $HANDOFF_PORT and try again.")
        }
    }

    @Synchronized
    fun stop() {
        if (stopped.getAndSet(true)) return
        generation.incrementAndGet()
        pairingCode = ""
        try { activeSocket?.close() } catch (_: Exception) { }
        activeSocket = null
        val current = server
        server = null
        try { current?.close() } catch (_: Exception) { }
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
            headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }

        val method = request[0]
        val path = request[1].substringBefore('?')
        if (method == "GET" && path == "/") return respond(socket.getOutputStream(), 200, "OK", LINK_ENTRY_PAGE, "text/html; charset=utf-8")
        if (method != "POST" || path != "/api/play") return respond(socket.getOutputStream(), 404, "Not Found", "Not found")
        if (!isSameOrigin(headers["origin"], socket.localAddress)) return respond(socket.getOutputStream(), 403, "Forbidden", "Open the link page on this TV's local network")
        val receivedToken = headers["x-pairing-token"].orEmpty().toByteArray(StandardCharsets.UTF_8)
        val expectedToken = pairingCode.toByteArray(StandardCharsets.UTF_8)
        if (expectedToken.isEmpty() || !MessageDigest.isEqual(expectedToken, receivedToken)) {
            return respond(socket.getOutputStream(), 401, "Unauthorized", "Pair this browser with the code shown on the TV")
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
        val videoUrl = when (val result = validateVideoUrl(payload.optString("videoUrl"))) {
            is VideoUrlValidation.Valid -> result.normalizedUrl
            is VideoUrlValidation.Invalid -> return respond(socket.getOutputStream(), 422, "Unprocessable Entity", result.message)
        }
        val subtitleValue = payload.optString("subtitleUrl").takeIf { it.isNotBlank() }
        val subtitleUrl = if (subtitleValue == null) null else when (val result = validateSubtitleUrl(subtitleValue)) {
            is VideoUrlValidation.Valid -> result.normalizedUrl
            is VideoUrlValidation.Invalid -> return respond(socket.getOutputStream(), 422, "Unprocessable Entity", result.message)
        }
        val title = payload.optString("title").filterNot(Char::isISOControl).trim().take(100).takeIf(String::isNotBlank)
        respond(socket.getOutputStream(), 202, "Accepted", "The TV is opening your video.")
        mainHandler.post {
            if (isCurrent(listener, runId)) onVideoSubmitted(SubmittedVideo(videoUrl, subtitleUrl, title))
        }
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
            address.hostAddress.startsWith("192.168.") || address.hostAddress.startsWith("10.") || address.hostAddress.startsWith("172.")
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
            <style>body{font:18px system-ui;max-width:680px;margin:2rem auto;padding:0 1rem;background:#07111f;color:#fff}label{display:block;margin:1rem 0 .35rem}input{box-sizing:border-box;width:100%;padding:.85rem;border-radius:.6rem;border:1px solid #6b829d;font:inherit}button{margin-top:1.2rem;padding:.8rem 1.2rem;border:0;border-radius:.6rem;background:#5bd5c6;color:#07111f;font:inherit;font-weight:700}small{color:#b9c8d9}#status{min-height:2rem}</style>
            <h1>Send a video to your TV</h1><p>Enter direct links. The TV fetches and plays the media; this page only sends the links.</p>
            <label for="code">TV pairing code</label><input id="code" autocomplete="off" required>
            <label for="video">Video URL</label><input id="video" type="url" autocomplete="url" maxlength="4096" required placeholder="https://example.com/video.mp4">
            <label for="title">Video title (optional)</label><input id="title" maxlength="100" placeholder="For example, Movie night">
            <label for="subtitle">Subtitle URL (optional SRT or WebVTT)</label><input id="subtitle" type="url" maxlength="4096" placeholder="https://example.com/subtitles.srt">
            <button id="send">Send to TV</button><p id="status" role="status"></p>
            <script>
            const params=new URLSearchParams(location.hash.slice(1));const pairing=params.get('pairing');history.replaceState(null,'',location.pathname);
            if(pairing)document.querySelector('#code').value=pairing;
            document.querySelector('#send').onclick=async()=>{const status=document.querySelector('#status');status.textContent='Sending…';try{const response=await fetch('/api/play',{method:'POST',headers:{'Content-Type':'application/json','X-Pairing-Token':document.querySelector('#code').value},body:JSON.stringify({videoUrl:document.querySelector('#video').value,title:document.querySelector('#title').value,subtitleUrl:document.querySelector('#subtitle').value})});status.textContent=await response.text()}catch(e){status.textContent='Could not reach the TV. Check that both devices are on the same network.'}};
            </script></html>
        """.trimIndent()
    }
}
