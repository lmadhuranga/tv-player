package dev.lmadhuranga.tvplayer

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults

/** Shared TV remote button colors: dark at rest, white while focused or pressed. */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TvButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF202020),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
            pressedContainerColor = Color.White,
            pressedContentColor = Color(0xFF111111),
            disabledContainerColor = Color(0xFF292929),
            disabledContentColor = Color(0xFF999999),
        ),
        content = content,
    )
}
