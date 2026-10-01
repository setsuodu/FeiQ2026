package minifeiq

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import minifeiq.service.Peer

/**
 * MiniFeiQApp 启动后把「在线列表 / 发文件」挂到这里，
 * MainActivity 侧的分享弹窗即可复用。
 */
object ShareBridge {
    var peers: () -> List<Peer> = { emptyList() }
    var sendToPeer: (Peer, Uri) -> Unit = { _, _ -> }
    var revision by mutableIntStateOf(0)
        private set
    fun notifyChanged() { revision++ }
}

@Composable
fun SharePeerPickerDialog() {
    val rev = ShareBridge.revision
    val pending = ShareInbox.pending
    if (pending.isEmpty()) return

    val peers = remember(rev, pending.size) { ShareBridge.peers() }
    val shareCount = pending.size

    AlertDialog(
        onDismissRequest = { ShareInbox.clear() },
        title = { Text("分享到飞秋") },
        text = {
            Column {
                Text("选择联系人发送 $shareCount 个文件：")
                Spacer(Modifier.height(8.dp))
                if (peers.isEmpty()) {
                    Text("暂无在线联系人，请先连接或刷新列表", color = Color.Gray)
                } else {
                    peers.forEach { p ->
                        Text(
                            p.name.ifBlank { p.ip },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val uris = pending.toList()
                                    ShareInbox.clear()
                                    uris.forEach { uri -> ShareBridge.sendToPeer(p, uri) }
                                }
                                .padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { ShareInbox.clear() }) { Text("取消") }
        }
    )
}
