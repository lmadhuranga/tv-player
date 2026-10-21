package dev.lmadhuranga.tvplayer

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

@Composable
fun HandoffScreen(
    settingsRequester: FocusRequester,
    onVideoSubmitted: (SubmittedVideo) -> Unit,
    onBack: () -> Unit,
    onGoToPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val latestCallback by rememberUpdatedState(onVideoSubmitted)
    val server = remember { LocalLinkServer { latestCallback(it) } }
    val goToPlayerRequester = remember { FocusRequester() }
    val state by server.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(state.pairingExpiresAtMs, state.pairingAvailableAtMs) {
        while (state.isRunning) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    LaunchedEffect(Unit) {
        goToPlayerRequester.requestFocus()
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
                Text("SEND A LINK TO YOUR TV", color = Teal, fontSize = 15.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(18.dp))
                Text("Use your phone to add a video", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Text("Scan the QR code, then enter a direct video link. Your TV fetches the video itself.", color = Muted, fontSize = 18.sp)
                Spacer(Modifier.height(20.dp))
                state.address?.let { address ->
                    Text("Open on your phone", color = Muted, fontSize = 16.sp)
                    Text("http://$address:8765", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(12.dp))
                    Text("PAIRING CODE", color = Muted, fontSize = 13.sp, letterSpacing = 2.sp)
                    Text(state.pairingCode.ifBlank { "----" }, color = Teal, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                    val cooldownSeconds = ((state.pairingAvailableAtMs - now).coerceAtLeast(0L) / 1_000L)
                    if (cooldownSeconds > 0) {
                        Text("New code ready in ${cooldownSeconds}s", color = Muted, fontSize = 14.sp)
                    } else if (state.pairingCode.isNotBlank()) {
                        val remainingSeconds = ((state.pairingExpiresAtMs - now).coerceAtLeast(0L) / 1_000L)
                        Text(
                            if (remainingSeconds == 0L) "Code expired. Generate a new code."
                            else "Expires in ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}",
                            color = Muted,
                            fontSize = 14.sp,
                        )
                    } else {
                        Text("Code used. Generate a new code to pair another browser.", color = Muted, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    AppButton(onClick = server::regeneratePin, enabled = cooldownSeconds == 0L) { Text("Generate new code") }
                }
                state.message?.let { Text(it, color = Color(0xFFFFB4A9), fontSize = 17.sp) }
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppButton(onClick = onGoToPlayer, modifier = Modifier.focusRequester(goToPlayerRequester)) {
                        Text("Go to Player")
                    }
                    AppButton(onClick = onOpenSettings, modifier = Modifier.focusRequester(settingsRequester)) { Text("Settings") }
                    AppButton(onClick = onBack) { Text("Back") }
                }
            }
            Spacer(Modifier.width(40.dp))
            val qr = remember(state.pageUrl) { state.pageUrl?.let(::createQrBitmap) }
            Surface(
                modifier = Modifier.size(260.dp),
                shape = RoundedCornerShape(20.dp),
                colors = SurfaceDefaults.colors(containerColor = Color.White),
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "QR code for the TV link page",
                        modifier = Modifier.fillMaxSize().padding(18.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color.White),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(if (state.isRunning) "Preparing QR code…" else "QR code unavailable", color = Ink, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

private fun createQrBitmap(contents: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 320, 320)
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}.getOrNull()
