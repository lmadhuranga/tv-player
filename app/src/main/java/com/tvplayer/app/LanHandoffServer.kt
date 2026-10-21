package com.tvplayer.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A deliberately tiny foreground-only HTTP receiver. Its HTML sends only links to the TV.
 * It never reads/proxies video or subtitle bytes, and never logs submitted URLs or PINs.
 */
class LanHandoffServer(
    private val context: Context,
    private val onSubmit: (VideoEntry) -> Unit
) {
    data class Info(val address: String, val pin: String)
    @Volatile var info: Info? = null; private set
    private var socket: ServerSocket? = null
    private var worker: Thread? = null
    private val active = AtomicBoolean(false)
    private val random = SecureRandom()
    private var failedAttempts = 0
    private var blockedUntil = 0L
    private val port = 8765

    fun start(): Boolean {
        if (active.get()) return true
        val ip = activeLanAddress() ?: return false
        return try {
            val listening = ServerSocket(port, 5, ip)
            listening.soTimeout = 1200
            val symbols = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val pin = (1..8).map { symbols[random.nextInt(symbols.length)] }.joinToString("")
            socket = listening
            failedAttempts = 0
            blockedUntil = 0
            info = Info("http://${ip.hostAddress}:$port/", pin)
            active.set(true)
            worker = Thread({ serve(listening, pin) }, "tv-player-lan").apply { isDaemon = true; start() }
            true
        } catch (_: Exception) {
            active.set(false); info = null; false
        }
    }

    fun stop() {
        active.set(false)
        runCatching { socket?.close() }
        socket = null
        info = null
        worker = null
    }

    private fun activeLanAddress(): InetAddress? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(net) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return manager.getLinkProperties(net)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }
    }

    private fun serve(server: ServerSocket, pin: String) {
        while (active.get()) {
            try {
                server.accept().use { client ->
                    client.soTimeout = 4000
                    handle(client, pin)
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Check foreground lifetime again.
            } catch (_: Exception) {
                if (!active.get()) break
            }
        }
    }

    private fun handle(client: Socket, pin: String) {
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val requestLine = readLine(input, 1024) ?: return
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[2] != "HTTP/1.1" && parts[2] != "HTTP/1.0") {
            respond(output, 400, "Bad Request", "Malformed request."); return
        }
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, 2048) ?: return
            headerBytes += line.length
            if (headerBytes > 8192) { respond(output, 431, "Request Header Fields Too Large", "Headers too large."); return }
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) { respond(output, 400, "Bad Request", "Invalid header."); return }
            val key = line.substring(0, colon).lowercase()
            if (key in headers) { respond(output, 400, "Bad Request", "Duplicate header."); return }
            headers[key] = line.substring(colon + 1).trim()
        }
        val ip = info?.address?.removeSuffix("/") ?: return
        if (parts[0] == "GET" && parts[1] == "/") {
            respond(output, 200, "OK", page(), html = true)
            return
        }
        if (parts[0] != "POST" || parts[1] != "/submit") {
            respond(output, 404, "Not Found", "Not found."); return
        }
        if (System.currentTimeMillis() < blockedUntil) {
            respond(output, 429, "Too Many Requests", "Pairing is temporarily locked. Try again shortly."); return
        }
        val origin = headers["origin"]
        if (origin != null && origin != ip) {
            respond(output, 403, "Forbidden", "Cross-origin request rejected."); return
        }
        if (headers["content-type"]?.lowercase()?.substringBefore(';') != "application/x-www-form-urlencoded") {
            respond(output, 415, "Unsupported Media Type", "Use a web form."); return
        }
        if (headers.containsKey("transfer-encoding")) {
            respond(output, 400, "Bad Request", "Chunked requests not supported."); return
        }
        val length = headers["content-length"]?.toIntOrNull()
        if (length == null || length !in 1..8192) {
            respond(output, 413, "Payload Too Large", "Request must be under 8 KB."); return
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = input.read(bytes, read, length - read)
            if (chunk == -1) { respond(output, 400, "Bad Request", "Incomplete request."); return }
            read += chunk
        }
        val params = try { HandoffForm.decode(bytes.toString(Charsets.UTF_8)) } catch (_: Exception) {
            respond(output, 400, "Bad Request", "Invalid form encoding."); return
        }
        val received = params["pairing"]?.uppercase().orEmpty().toByteArray(Charsets.US_ASCII)
        val expected = pin.toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(received, expected)) {
            failedAttempts++
            if (failedAttempts >= 5) { blockedUntil = System.currentTimeMillis() + 60_000; failedAttempts = 0 }
            respond(output, 403, "Forbidden", "Incorrect pairing code."); return
        }
        val video = params["video"].orEmpty().trim()
        val subtitle = params["subtitle"]?.trim()?.ifBlank { null }
        if (!UrlRules.validHttpUrl(video) || (subtitle != null && !UrlRules.validHttpUrl(subtitle))) {
            respond(output, 400, "Bad Request", "Enter valid HTTP(S) video and subtitle URLs."); return
        }
        onSubmit(VideoEntry(video, subtitle))
        respond(output, 200, "OK", "Link sent to the TV. You can return to your remote.")
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= limit) {
            val ch = input.read()
            if (ch == -1) return null
            if (ch == 10) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(ch)
        }
        throw IllegalArgumentException("Line too long")
    }

    private fun respond(out: OutputStream, code: Int, reason: String, text: String, html: Boolean = false) {
        val body = text.toByteArray(Charsets.UTF_8)
        val mime = if (html) "text/html" else "text/plain"
        val headers = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $mime; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
    }

    private fun page(): String = """<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TV Player · Link handoff</title><style>
body{font:16px system-ui,sans-serif;background:#081522;color:#eef7ff;max-width:590px;margin:50px auto;padding:20px}
h1{font-size:30px}p{color:#b4cbdd}label{display:block;margin:20px 0 6px;font-weight:600}
input{box-sizing:border-box;width:100%;padding:13px;border:1px solid #61798b;border-radius:9px;background:#11283c;color:white;font:inherit}
button{margin-top:24px;background:#64cdfd;color:#071827;border:0;border-radius:10px;padding:15px 26px;font-weight:700;font:inherit}
small{display:block;margin-top:25px;color:#b4cbdd}</style></head><body>
<h1>Send a link to your TV</h1><p>Use the pairing code currently shown on your TV screen. The TV fetches the video; your phone does not stream it.</p>
<form method="post" action="/submit" autocomplete="off">
<label for="pairing">TV pairing code</label><input id="pairing" name="pairing" maxlength="8" required spellcheck="false" autocapitalize="characters">
<label for="video">Direct video URL (HTTP or HTTPS)</label><input id="video" name="video" type="url" maxlength="4096" required placeholder="https://example.com/video.mp4">
<label for="subtitle">Subtitle URL (optional SRT / WebVTT)</label><input id="subtitle" name="subtitle" type="url" maxlength="4096" placeholder="https://example.com/subtitles.vtt">
<button type="submit">Play on TV</button></form><small>Works on the same local network while TV Player is open. Do not send confidential links on untrusted Wi-Fi.</small>
</body></html>"""
}
