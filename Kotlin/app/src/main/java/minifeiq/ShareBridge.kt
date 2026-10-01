package minifeiq

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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import minifeiq.service.Peer

/**
 * MiniFeiQApp 把在线列表写进 [peerList]；分享弹窗直接订阅。
 * Peer.ip 是 InetAddress，key 用 hostAddress，不要对 ip 做字符串 +。
 */
object ShareBridge {
    val peerList: SnapshotStateList<Peer> = mutableStateListOf()
    var sendToPeer: (Peer, Uri) -> Unit = { _, _ -> }

    fun updatePeers(list: List<Peer>) {
        peerList.clear()
        peerList.addAll(list)
    }
}

@Composable
fun SharePeerPickerDialog() {
    val pending = ShareInbox.pending
    if (pending.isEmpty()) return

    val peers = ShareBridge.peerList
    val shareCount = pending.size

    AlertDialog(
        onDismissRequest = { ShareInbox.clear() },
        title = { Text("分享到飞秋") },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                Text("选择联系人发送 $shareCount 个文件：")
                Spacer(Modifier.height(8.dp))
                if (peers.isEmpty()) {
                    Text(
                        "暂无在线联系人（连接成功后会自动出现）",
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
                                        ShareInbox.clear()
                                        uris.forEach { uri -> ShareBridge.sendToPeer(p, uri) }
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
            TextButton(onClick = { ShareInbox.clear() }) { Text("取消") }
        }
    )
}
