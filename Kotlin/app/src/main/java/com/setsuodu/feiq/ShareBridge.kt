package com.setsuodu.feiq

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.setsuodu.feiq.service.Peer

/**
 * 系统分享选人弹窗。
 * peers 必须由 MiniFeiQApp 传入 sessions（在线∪历史），不要再经旁路同步。
 */
@Composable
fun SharePeerPickerDialog(
    peers: List<Peer>,
    onSend: (Peer, Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val pending = ShareInbox.pending
    if (pending.isEmpty()) return

    val shareCount = pending.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享到飞秋") },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                Text("选择联系人发送 $shareCount 个文件：")
                Spacer(Modifier.height(8.dp))
                if (peers.isEmpty()) {
                    Text(
                        "暂无联系人，请先连接；有过聊天的人会保留在列表中",
                        color = Color.Gray
                    )
                } else {
                    LazyColumn {
                        items(
                            items = peers,
                            key = { p ->
                                val addr = p.ip.hostAddress ?: p.ip.toString()
                                addr + "/" + p.name
                            }
                        ) { p ->
                            val label = p.name.ifBlank {
                                p.ip.hostAddress ?: p.ip.toString()
                            }
                            Text(
                                text = label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val uris = pending.toList()
                                        onDismiss()
                                        uris.forEach { uri -> onSend(p, uri) }
                                    }
                                    .padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
