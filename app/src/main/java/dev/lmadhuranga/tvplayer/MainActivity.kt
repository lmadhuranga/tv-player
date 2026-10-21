package dev.lmadhuranga.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TVPlayerScreen()
        }
    }
}

@Composable
private fun TVPlayerScreen() {
    val background = Color(0xFF07111F)
    Surface(modifier = Modifier.fillMaxSize(), color = background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF142A46), background, Color(0xFF0A1220)),
                    ),
                )
                .padding(horizontal = 72.dp, vertical = 48.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0xFF5BD5C6), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▶", color = Color(0xFF07111F), fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "TV PLAYER",
                        color = Color(0xFFB9C8D9),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    )
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    "Your screen.\nYour video.",
                    color = Color.White,
                    fontSize = 54.sp,
                    lineHeight = 62.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "A simple place to play videos on your TV.",
                    color = Color(0xFFB9C8D9),
                    fontSize = 21.sp,
                )
                Spacer(Modifier.height(36.dp))
                Surface(color = Color(0xFF132338), shape = RoundedCornerShape(14.dp)) {
                    Text(
                        "Player setup is coming next",
                        color = Color(0xFF5BD5C6),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                    )
                }
            }
        }
    }
}
