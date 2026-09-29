package minifeiq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import minifeiq.service.IncomingFileOffer
import minifeiq.service.IpMsgService
import minifeiq.service.Peer
import minifeiq.transport.Transport
import minifeiq.transport.UdpTransport
import minifeiq.transport.WebSocketTransport
import java.awt.FileDialog
import java.awt.Frame
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun MiniFeiQApp() {
    val scope = rememberCoroutineScope()
    var modeIndex by remember { mutableStateOf(0) } // 0=UDP 1=WS
    var serverUrl by remember { mutableStateOf("ws://127.0.0.1:9000/ws") }
    var status by remember { mutableStateOf("未连接") }
    var statusColor by remember { mutableStateOf(Color.Gray) }
    var input by remember { mutableStateOf("") }
    val logs = remember { mutableStateListOf<String>() }
    val users = remember { mutableStateListOf<Peer>() }
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }

    var service by remember { mutableStateOf<IpMsgService?>(null) }
    var transport by remember { mutableStateOf<Transport?>(null) }

    var pendingOffer by remember { mutableStateOf<IncomingFileOffer?>(null) }
    fun appendLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss").format(Date())
        logs.add("$ts $line")
        if (logs.size > 2000) logs.removeAt(0)
    }

    fun stopService() {
        service?.stop()
        service = null
        transport?.close()
        transport = null
        users.clear()
    }

    fun startService() {
        scope.launch(Dispatchers.IO) {
            try {
                stopService()
                status = "连接中..."
                statusColor = Color(0xFFFFA500)

                val t: Transport = if (modeIndex == 1) {
                    if (serverUrl.isBlank()) {
                        status = "请填写中继地址"
                        statusColor = Color.Red
                        return@launch
                    }
                    appendLog("[系统] 使用 WebSocket 中继 → $serverUrl")
                    WebSocketTransport(serverUrl)
                } else {
                    appendLog("[系统] 使用 UDP 局域网（飞秋2013兼容）")
                    UdpTransport(2425)
                }
                transport = t

                val svc = IpMsgService(t)
                svc.onPeerOnline = { p ->
                    scope.launch(Dispatchers.Swing) {
                        if (users.none { it.name == p.name && it.ip == p.ip }) users.add(p)
                        appendLog("[上线] ${p.name}")
                    }
                }
                svc.onPeerOffline = { p ->
                    scope.launch(Dispatchers.Swing) {
                        users.removeAll { it.name == p.name && it.ip == p.ip }
                        appendLog("[下线] ${p.name}")
                    }
                }
                svc.onMessage = { p, text ->
                    scope.launch(Dispatchers.Swing) {
                        appendLog("[${p.name}] $text")
                    }
                }
                svc.onFileOffered = { offer ->
                    scope.launch(Dispatchers.Swing) {
                        val sizeStr = formatSize(offer.info.size)
                        appendLog("[文件] ${offer.from.name} 发来「${offer.info.fileName}」($sizeStr)")
                        pendingOffer = offer
                    }
                }
                svc.onFileProgress = { p ->
                    scope.launch(Dispatchers.Swing) {
                        when {
                            p.error != null -> appendLog("[文件] ${p.fileName} 失败：${p.error}")
                            p.done -> {
                                val where = p.savedPath?.let { " → $it" } ?: ""
                                appendLog("[文件] ${p.fileName} 完成 ${formatSize(p.received)}$where")
                            }
                            p.total > 0 -> {
                                val pct = (p.received * 100 / p.total).toInt()
                                if (pct % 10 == 0) appendLog("[文件] ${p.fileName} $pct%")
                            }
                        }
                    }
                }

                svc.start()
                service = svc
                status = if (modeIndex == 1) "已连接中继" else "UDP 已启动"
                statusColor = Color(0xFF2E7D32)
                appendLog("[系统] 启动成功")
            } catch (e: Exception) {
                status = "连接失败"
                statusColor = Color.Red
                appendLog("[错误] ${e.message}")
            }
        }
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // 顶部工具栏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("传输模式：")
                Spacer(Modifier.width(6.dp))
                var modeExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { modeExpanded = true }) {
                        Text(if (modeIndex == 0) "UDP 局域网" else "WebSocket 中继")
                    }
                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        DropdownMenuItem(text = { Text("UDP 局域网 (飞秋兼容)") }, onClick = {
                            modeIndex = 0; modeExpanded = false
                        })
                        DropdownMenuItem(text = { Text("WebSocket 中继") }, onClick = {
                            modeIndex = 1; modeExpanded = false
                        })
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text("中继：")
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    modifier = Modifier.width(260.dp).height(52.dp),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { startService() }) { Text("连接/重连") }
                Spacer(Modifier.width(10.dp))
                Text(status, color = statusColor)
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxSize()) {
                // 左侧用户列表
                Column(Modifier.width(200.dp).fillMaxHeight()) {
                    Text("在线用户", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp)
                    ) {
                        items(users) { peer ->
                            val selected = selectedPeer == peer
                            TextButton(
                                onClick = { selectedPeer = peer },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(peer.toString(), maxLines = 2)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                users.clear()
                                service?.refresh()
                                appendLog("[系统] 已刷新")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text("刷新") }
                }

                Spacer(Modifier.width(10.dp))

                // 右侧聊天
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text("聊天记录", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    val listState = rememberLazyListState()
                    LaunchedEffect(logs.size) {
                        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    ) {
                        items(logs) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("输入消息...") }
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            val dlg = FileDialog(null as Frame?, "选择要发送的文件", FileDialog.LOAD)
                            dlg.isMultipleMode = false
                            dlg.isVisible = true
                            val dir = dlg.directory
                            val file = dlg.file
                            if (dir == null || file == null || service == null) return@Button
                            val path = dir + file
                            val name = file
                            scope.launch(Dispatchers.IO) {
                                try {
                                    if (modeIndex == 1) {
                                        service!!.sendFile(InetAddress.getLoopbackAddress(), path)
                                        appendLog("[我：] 发送文件 $name")
                                    } else {
                                        val peer = selectedPeer
                                        if (peer == null) {
                                            appendLog("[错误] 请先选中接收方")
                                            return@launch
                                        }
                                        service!!.sendFile(peer.ip, path)
                                        appendLog("[我 → ${peer.name}] 发送文件 $name")
                                    }
                                } catch (e: Exception) {
                                    appendLog("[错误] 发文件失败：${e.message}")
                                }
                            }
                        }) { Text("发文件") }
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            val text = input.trim()
                            if (text.isEmpty() || service == null) return@Button
                            scope.launch(Dispatchers.IO) {
                                try {
                                    if (modeIndex == 1) {
                                        service!!.sendText(InetAddress.getLoopbackAddress(), text)
                                        appendLog("[我：] $text")
                                    } else {
                                        val peer = selectedPeer
                                        if (peer == null) {
                                            appendLog("[错误] 请先选中接收方")
                                            return@launch
                                        }
                                        service!!.sendText(peer.ip, text)
                                        appendLog("[我 → ${peer.name}] $text")
                                    }
                                    input = ""
                                } catch (e: Exception) {
                                    appendLog("[错误] ${e.message}")
                                }
                            }
                        }) { Text("发送") }
                    }
                }
            }
        }
    }


    // 收文件确认
    pendingOffer?.let { offer ->
        val sizeStr = formatSize(offer.info.size)
        AlertDialog(
            onDismissRequest = { pendingOffer = null },
            title = { Text("文件传输") },
            text = {
                Text("收到来自 ${offer.from.name} 的文件：\n\n${offer.info.fileName}\n大小：$sizeStr\n\n是否接收？")
            },
            confirmButton = {
                TextButton(onClick = {
                    val o = offer
                    pendingOffer = null
                    scope.launch(Dispatchers.IO) {
                        appendLog("[文件] 开始接收 ${o.info.fileName} ...")
                        service?.acceptFile(o)
                    }
                }) { Text("接收") }
            },
            dismissButton = {
                TextButton(onClick = {
                    appendLog("[文件] 已拒绝 ${offer.info.fileName}")
                    pendingOffer = null
                }) { Text("拒绝") }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { stopService() }
    }
}


private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
