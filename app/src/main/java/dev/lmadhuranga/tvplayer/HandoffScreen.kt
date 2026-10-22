package dev.lmadhuranga.tvplayer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private val QrLightBackground = Color(0xFFD6D6D6)
private val QrLightBackgroundArgb = android.graphics.Color.rgb(214, 214, 214)

@Composable
fun HandoffScreen(
    cacheDirectory: java.io.File,
    hasActiveMedia: Boolean,
    recentEntries: List<HistoryEntry>,
    settingsRequester: FocusRequester,
    onVideoSubmitted: (SubmittedVideo) -> Unit,
    onBack: () -> Unit,
    onGoToPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onApprovalPending: (Boolean) -> Unit,
    onReplay: (HistoryEntry) -> Unit,
) {
    val latestCallback by rememberUpdatedState(onVideoSubmitted)
    val server = remember { LocalLinkServer(cacheDirectory) { latestCallback(it) } }
    val state by server.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val denyRequester = remember { FocusRequester() }

    androidx.compose.runtime.LaunchedEffect(state.pendingApprovalId) {
        onApprovalPending(state.pendingApprovalId != null)
    }

    DisposableEffect(server, lifecycleOwner) {
        server.start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // An in-app Settings dialog can briefly pause the activity. Keep the
                // handoff listener alive until the app actually leaves the foreground.
                Lifecycle.Event.ON_START -> server.start()
                Lifecycle.Event.ON_STOP -> server.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            server.stop()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = Ink),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("SEND TO TV", color = Teal, fontSize = 15.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(14.dp))
                Text("Scan to open the send page.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(16.dp))
                state.address?.let { address ->
                    Text("Or open:", color = Muted, fontSize = 16.sp)
                    Text("http://$address:8765", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                }
                state.message?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 17.sp) }
                Spacer(Modifier.height(18.dp))
                Text("RECENT", color = Teal, fontSize = 14.sp, letterSpacing = 2.sp)
                val handoffEntries = remember(recentEntries) {
                    recentEntries.distinctBy { entry ->
                        entry.mediaUrl?.let { HistoryRepository.mediaIdentity(it) }
                            ?: "legacy:${entry.title.lowercase()}@${entry.sourceHost.lowercase()}"
                    }.take(3)
                }
                if (handoffEntries.isEmpty()) {
                    Text("No videos yet.", color = Muted, fontSize = 16.sp)
                } else {
                    handoffEntries.forEachIndexed { index, entry ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        val title = entry.title.ifBlank { entry.sourceHost.ifBlank { "Video" } }
                        val label = "$title · ${entry.sourceHost.ifBlank { "Unknown source" }} · ${formatHandoffTime(entry.positionMs)}"
                        if (entry.mediaUrl != null) {
                            AppButton(
                                onClick = { onReplay(entry) },
                                modifier = Modifier.widthIn(max = 620.dp),
                                scale = ButtonDefaults.scale(focusedScale = 1f),
                            ) {
                                Text(label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text(
                                label,
                                color = Muted,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 620.dp).padding(vertical = 8.dp).focusable(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if ((!state.isRunning && state.message != null) || hasActiveMedia) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!state.isRunning && state.message != null) {
                            AppButton(onClick = { server.retry() }) { Text("Retry") }
                        }
                        if (hasActiveMedia) {
                            AppButton(onClick = onGoToPlayer) {
                                Text("Go to Player")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(40.dp))
            Column(
                modifier = Modifier.width(300.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val qr = remember(state.pageUrl) { state.pageUrl?.let(::createQrBitmap) }
                Surface(
                    modifier = Modifier.size(260.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = SurfaceDefaults.colors(containerColor = QrLightBackground),
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "QR code for the TV link page",
                            modifier = Modifier.fillMaxSize().padding(18.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().background(QrLightBackground),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(if (state.isRunning) "Preparing QR code…" else "QR code unavailable", color = Ink, fontSize = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(onClick = onOpenSettings, modifier = Modifier.focusRequester(settingsRequester)) { Text("Settings") }
                    AppButton(onClick = onBack) { Text("Back") }
                }
                Spacer(Modifier.height(8.dp))
                Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
            }
        }
    }

    state.pendingApprovalId?.let { approvalId ->
        Dialog(onDismissRequest = { server.denyPairingRequest(approvalId) }) {
            androidx.compose.runtime.LaunchedEffect(approvalId) { denyRequester.requestFocus() }
            Surface(
                modifier = Modifier.width(700.dp),
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = Color(0xFF18263A)),
            ) {
                Column(
                    modifier = Modifier.padding(36.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("APPROVE PHONE CONNECTION?", color = Teal, fontSize = 15.sp, letterSpacing = 2.sp)
                    Text(
                        "${state.requesterDevice ?: "A browser"} at ${state.requesterAddress ?: "an unknown address"} wants to connect and send media.",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text("Only allow devices you recognize on your local network.", color = Muted, fontSize = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppButton(
                            onClick = { server.denyPairingRequest(approvalId) },
                            modifier = Modifier.focusRequester(denyRequester),
                        ) { Text("Deny") }
                        AppButton(onClick = { server.approvePairingRequest(approvalId) }) { Text("Allow") }
                    }
                }
            }
        }
    }
}

private fun formatHandoffTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun createQrBitmap(contents: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 320, 320)
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else QrLightBackgroundArgb)
        }
    }
}.getOrNull()
