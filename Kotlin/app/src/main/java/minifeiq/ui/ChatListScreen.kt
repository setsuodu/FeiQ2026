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

/**
 * @param sessions 展示用会话列表（在线 ∪ 最近联系人，离线也保留）
 * @param onlineKeys 当前在线 peerKey 集合，用于绿点/灰字
 */
@Composable
internal fun ChatListScreen(
    sessions: List<Peer>,
    onlineKeys: Set<String>,
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

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无会话，连接后或聊天后会出现在这里", color = Color.Gray)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
                items(sessions, key = { ChatStore.peerKey(it) }) { peer ->
                    val key = ChatStore.peerKey(peer)
                    val n = unread[key] ?: 0
                    val online = key in onlineKeys
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(peer) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val ctx = LocalContext.current
                        val peerAv = AvatarCache.getPath(ctx, key)
                        Box {
                            AvatarCircle(
                                letter = peer.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                bg = if (online) Color(0xFF12B7F5) else Color(0xFF9E9E9E),
                                imagePath = peerAv
                            )
                            // 右下角在线点
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(12.dp)
                                    .padding(1.dp)
                            ) {
                                ConnDot(if (online) ConnGreen else ConnGray)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                peer.name,
                                fontWeight = FontWeight.SemiBold,
                                color = if (online) Color.Unspecified else Color(0xFF757575)
                            )
                            val sub = buildString {
                                append(peer.hostName.ifBlank { "—" })
                                val ip = peer.ip.hostAddress
                                if (!ip.isNullOrBlank() && ip != "0.0.0.0") {
                                    append(" · ")
                                    append(ip)
                                }
                                if (!online) append(" · 离线")
                            }
                            Text(
                                sub,
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
