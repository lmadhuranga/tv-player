package dev.lmadhuranga.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

private val Ink = Color(0xFF07111F)
private val Teal = Color(0xFF5BD5C6)
private val Muted = Color(0xFFB9C8D9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TVPlayerApp()
            }
        }
    }
}

private enum class AppRoute { HOME, PLAYER }

@Composable
private fun TVPlayerApp() {
    var route by remember { mutableStateOf(AppRoute.HOME) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsWasOpen by remember { mutableStateOf(false) }
    val settingsOpenerRequester = remember { FocusRequester() }
    val settingsCloseRequester = remember { FocusRequester() }

    BackHandler(enabled = settingsOpen || route != AppRoute.HOME) {
        if (settingsOpen) settingsOpen = false else route = AppRoute.HOME
    }

    when (route) {
        AppRoute.HOME -> HomeScreen(
            settingsRequester = settingsOpenerRequester,
            onOpenPlayer = { route = AppRoute.PLAYER },
            onOpenSettings = { settingsOpen = true },
        )

        AppRoute.PLAYER -> PlayerPlaceholder(onHome = { route = AppRoute.HOME })
    }

    if (settingsOpen) {
        LaunchedEffect(Unit) { settingsCloseRequester.requestFocus() }
        SettingsPlaceholder(focusRequester = settingsCloseRequester, onDismiss = { settingsOpen = false })
    } else if (settingsWasOpen) {
        LaunchedEffect(settingsOpen) { settingsOpenerRequester.requestFocus() }
    }
    LaunchedEffect(settingsOpen) { settingsWasOpen = settingsOpen }
}

@Composable
private fun HomeScreen(
    settingsRequester: FocusRequester,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TVBackground {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Brand()
                Spacer(Modifier.height(30.dp))
                Text(
                    "Your screen.\nYour video.",
                    color = Color.White,
                    fontSize = 56.sp,
                    lineHeight = 64.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "A simple place to play videos on your TV.",
                    color = Muted,
                    fontSize = 21.sp,
                )
                Spacer(Modifier.height(34.dp))
                Button(onClick = onOpenPlayer) {
                    Text("Open player", fontSize = 18.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.focusRequester(settingsRequester),
                ) {
                    Text("Settings", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.width(56.dp))
            Surface(
                modifier = Modifier.size(width = 390.dp, height = 250.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF12243A),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("READY WHEN YOU ARE", color = Teal, fontSize = 14.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("Player setup is coming next", color = Color.White, fontSize = 23.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Enter a video link to get started.", color = Muted, fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun PlayerPlaceholder(onHome: () -> Unit) {
    TVBackground {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PLAYER", color = Teal, fontSize = 15.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(18.dp))
            Text("Nothing is playing yet", color = Color.White, fontSize = 36.sp)
            Spacer(Modifier.height(12.dp))
            Text("Your video will appear here.", color = Muted, fontSize = 20.sp)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onHome) { Text("Back to home") }
        }
    }
}

@Composable
private fun SettingsPlaceholder(focusRequester: FocusRequester, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xD907111F),
        onClick = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(72.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("SETTINGS", color = Teal, fontSize = 15.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(16.dp))
            Text("Settings will appear here.", color = Color.White, fontSize = 32.sp)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(focusRequester)) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(52.dp).background(Teal, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = Ink, fontSize = 22.sp)
        }
        Spacer(Modifier.width(16.dp))
        Text("TV PLAYER", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
    }
}

@Composable
private fun TVBackground(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color(0xFF142A46), Ink, Color(0xFF0A1220))))
                .padding(horizontal = 72.dp, vertical = 48.dp),
        ) {
            content()
        }
    }
}
