package dev.lmadhuranga.tvplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

@Composable
fun VideoUrlEntryScreen(onStart: (url: String, subtitleUrl: String?, title: String?, sourceLabel: String) -> Unit, onBack: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var subtitleUrl by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val urlRequester = remember { FocusRequester() }
    val subtitleRequester = remember { FocusRequester() }
    val titleRequester = remember { FocusRequester() }
    val submit = {
        when (val result = validateVideoUrl(url)) {
            is VideoUrlValidation.Valid -> when (val subtitle = subtitleUrl.takeIf(String::isNotBlank)?.let(::validateSubtitleUrl)) {
                null -> onStart(result.normalizedUrl, null, title.normalized().takeIf(String::isNotBlank), result.sourceLabel)
                is VideoUrlValidation.Valid -> onStart(result.normalizedUrl, subtitle.normalizedUrl, title.normalized().takeIf(String::isNotBlank), result.sourceLabel)
                is VideoUrlValidation.Invalid -> error = subtitle.message
            }
            is VideoUrlValidation.Invalid -> error = result.message
        }
    }
    LaunchedEffect(Unit) { urlRequester.requestFocus() }

    TVBackground {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("ADD VIDEO", color = Teal, fontSize = 15.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(16.dp))
            Text("Enter a direct video link", color = androidx.compose.ui.graphics.Color.White, fontSize = 38.sp)
            Spacer(Modifier.height(12.dp))
            Text("The TV opens the link directly. Use an HTTP or HTTPS video URL.", color = Muted, fontSize = 19.sp)
            Spacer(Modifier.height(26.dp))
            BasicTextField(
                value = url,
                onValueChange = {
                    url = it.take(4_097)
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .background(androidx.compose.ui.graphics.Color(0xFF101E30), RoundedCornerShape(14.dp))
                    .border(2.dp, Teal, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .focusRequester(urlRequester),
                singleLine = true,
                textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(Teal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { titleRequester.requestFocus() }),
                decorationBox = { innerTextField ->
                    Row {
                        if (url.isEmpty()) Text("https://example.com/video.mp4", color = Muted, fontSize = 20.sp)
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Text("VIDEO TITLE (OPTIONAL)", color = Muted, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = title,
                onValueChange = { title = it.take(100) },
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .background(androidx.compose.ui.graphics.Color(0xFF101E30), RoundedCornerShape(14.dp))
                    .border(2.dp, Teal, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .focusRequester(titleRequester),
                singleLine = true,
                textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 19.sp),
                cursorBrush = SolidColor(Teal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { subtitleRequester.requestFocus() }),
                decorationBox = { innerTextField ->
                    Row {
                        if (title.isEmpty()) Text("For example, Movie night", color = Muted, fontSize = 19.sp)
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Text("EXTERNAL SUBTITLES (OPTIONAL)", color = Muted, fontSize = 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = subtitleUrl,
                onValueChange = {
                    subtitleUrl = it.take(4_097)
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .background(androidx.compose.ui.graphics.Color(0xFF101E30), RoundedCornerShape(14.dp))
                    .border(2.dp, Teal, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .focusRequester(subtitleRequester),
                singleLine = true,
                textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 19.sp),
                cursorBrush = SolidColor(Teal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                decorationBox = { innerTextField ->
                    Row {
                        if (subtitleUrl.isEmpty()) Text("https://example.com/subtitles.srt", color = Muted, fontSize = 19.sp)
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            if (error != null) {
                Text(error!!, color = androidx.compose.ui.graphics.Color(0xFFFF9E9E), fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = submit) { Text("Play video") }
                Button(onClick = onBack) { Text("Cancel") }
            }
        }
    }
}

private fun String.normalized(): String = filterNot(Char::isISOControl).trim().take(100).ifBlank { "" }
