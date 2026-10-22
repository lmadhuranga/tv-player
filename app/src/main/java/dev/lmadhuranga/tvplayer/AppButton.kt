package dev.lmadhuranga.tvplayer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale

/**
 * Remote-friendly action. Every action has a generous hit target and a visible
 * teal focus outline so the active control is identifiable from the sofa.
 */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    TvButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = 2.dp,
                color = if (focused && enabled) Teal else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            ),
        enabled = enabled,
        scale = ButtonScale.None,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF1A2C43),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFFECFFFC),
            focusedContentColor = Ink,
            pressedContainerColor = Color.White,
            pressedContentColor = Ink,
            disabledContainerColor = Color(0xFF293747),
            disabledContentColor = Color(0xFF99A9BA),
        ),
        content = content,
    )
}
