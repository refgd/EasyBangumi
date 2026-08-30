package com.heyanle.easybangumi4.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun AiModelSelectionDialog(
    models: List<AiModelConfig>,
    onDismiss: () -> Unit,
    onSelected: (AiModelConfig) -> Unit,
) {
    var selectedId by remember(models) { mutableStateOf(models.firstOrNull()?.id.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择修复模型") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (models.isEmpty()) {
                    item {
                        Text("没有可用模型，请先在模型管理中完成端点、凭据、登录或代理配置。")
                    }
                }
                items(models, key = { it.id }) { model ->
                    ListItem(
                        modifier = Modifier.clickable { selectedId = model.id },
                        headlineContent = { Text(model.name) },
                        supportingContent = {
                            Text(model.model, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            Icon(
                                if (selectedId == model.id) Icons.Filled.Check else Icons.Filled.Memory,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedId.isNotBlank(),
                onClick = {
                    models.firstOrNull { it.id == selectedId }?.let(onSelected)
                },
            ) {
                Text("开始修复")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
