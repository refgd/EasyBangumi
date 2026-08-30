package com.heyanle.easybangumi4.ui.common.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalSourcePageErrorHandler = staticCompositionLocalOf<((String) -> Unit)?> { null }
val LocalSourcePageEmptyHandler = staticCompositionLocalOf<SourcePageEmptyAction?> { null }

data class SourcePageEmptyAction(
    val emptyMsg: String,
    val buttonText: String,
    val onClick: () -> Unit,
)

@Composable
fun SourcePageErrorActions(
    errorMsg: String,
    onRetry: () -> Unit,
) {
    val onAskAi = LocalSourcePageErrorHandler.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onRetry) {
            Text("重试")
        }
        if (onAskAi != null) {
            TextButton(onClick = { onAskAi(errorMsg) }) {
                Text("使用 AI 修复")
            }
        }
    }
}

@Composable
fun SourcePageEmptyActions() {
    val action = LocalSourcePageEmptyHandler.current
    if (action != null) {
        TextButton(onClick = action.onClick) {
            Text(action.buttonText)
        }
    }
}
