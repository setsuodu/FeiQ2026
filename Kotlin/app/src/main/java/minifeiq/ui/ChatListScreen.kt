package minifeiq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import minifeiq.data.AvatarCache
import minifeiq.data.ChatStore
import minifeiq.service.Peer

@Composable
internal fun ChatListScreen(
    users: List<Peer>,
    unread: Map<String, Int>,
    status: String,
    statusColor: Color,
    modeIndex: Int,
    avatarTick: Int = 0,
    onModeChange: (Int) -> Unit,
    onOpenChat: (Peer) -> Unit,
    onRefresh: () -> Unit
) {
    // avatarTick 仅用于收到头像后触发重组
    @Suppress("UNUSED_EXPRESSION")
    avatarTick
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(WeChatTopBar)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "FeiQ 2026",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            ConnDot(statusColor)
            Spacer(Modifier.width(6.dp))
            Text(status, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }

        // 模式
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("UDP 局域网", "WebSocket 中继").forEachIndexed { i, label ->
                FilterChip(
                    selected = modeIndex == i,
                    onClick = { onModeChange(i) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("刷新") }
        }

        HorizontalDivider()

        if (users.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无在线好友", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
                items(users, key = { ChatStore.peerKey(it) }) { peer ->
                    val key = ChatStore.peerKey(peer)
                    val n = unread[key] ?: 0
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(peer) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ctx = LocalContext.current
                        val peerAv = AvatarCache.getPath(ctx, ChatStore.peerKey(peer))
                        AvatarCircle(
                            letter = peer.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            bg = Color(0xFF12B7F5),
                            imagePath = peerAv
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${peer.hostName} · ${peer.ip.hostAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        if (n > 0) {
                            Badge { Text(if (n > 99) "99+" else n.toString()) }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 76.dp))
                }
            }
        }
    }
}
