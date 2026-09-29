package minifeiq.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import minifeiq.service.IncomingFileOffer
import minifeiq.service.IpMsgService
import minifeiq.service.Peer
import minifeiq.transport.Transport
import minifeiq.transport.UdpTransport
import minifeiq.transport.WebSocketTransport
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun MiniFeiQApp(context: Context = LocalContext.current) {
    val scope = rememberCoroutineScope()
    var modeIndex by remember { mutableIntStateOf(0) } // 0=UDP 1=WS
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

    val downloadDir = remember {
        File(context.filesDir, "downloads").also { it.mkdirs() }
    }

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
                withContext(Dispatchers.Main) {
                    status = "连接中..."
                    statusColor = Color(0xFFFFA500)
                }

                val t: Transport = if (modeIndex == 1) {
                    if (serverUrl.isBlank()) {
                        withContext(Dispatchers.Main) {
                            status = "请填写中继地址"
                            statusColor = Color.Red
                        }
                        return@launch
                    }
                    appendLog("[系统] 使用 WebSocket 中继 → $serverUrl")
                    WebSocketTransport(serverUrl, clientId = "android-${android.os.Build.MODEL}")
                } else {
                    appendLog("[系统] 使用 UDP 局域网（飞秋2013兼容）")
                    UdpTransport(2425)
                }
                transport = t

                val svc = IpMsgService(t, userName = "Android", hostName = android.os.Build.MODEL)
                svc.downloadDir = downloadDir
                svc.onPeerOnline = { p ->
                    scope.launch(Dispatchers.Main) {
                        if (users.none { it.name == p.name && it.ip == p.ip }) users.add(p)
                        appendLog("[上线] ${p.name}")
                    }
                }
                svc.onPeerOffline = { p ->
                    scope.launch(Dispatchers.Main) {
                        users.removeAll { it.name == p.name && it.ip == p.ip }
                        appendLog("[下线] ${p.name}")
                    }
                }
                svc.onMessage = { p, text ->
                    scope.launch(Dispatchers.Main) {
                        appendLog("[${p.name}] $text")
                    }
                }
                svc.onFileOffered = { offer ->
                    scope.launch(Dispatchers.Main) {
                        val sizeStr = formatSize(offer.info.size)
                        appendLog("[文件] ${offer.from.name} 发来「${offer.info.fileName}」($sizeStr)")
                        pendingOffer = offer
                    }
                }
                svc.onFileProgress = { p ->
                    scope.launch(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    status = if (modeIndex == 1) "已连接中继" else "UDP 已启动"
                    statusColor = Color(0xFF2E7D32)
                }
                appendLog("[系统] 启动成功")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "连接失败"
                    statusColor = Color.Red
                }
                appendLog("[错误] ${e.message}")
            }
        }
    }

    // 文件选择器（发送）
    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null || service == null) return@rememberLauncherForActivityResult
        val peer = selectedPeer
        if (peer == null) {
            appendLog("[系统] 请先选择一个在线用户")
            return@rememberLauncherForActivityResult
        }
        scope.launch(Dispatchers.IO) {
            try {
                val name = queryDisplayName(context, uri) ?: "file.bin"
                val tmp = File(context.cacheDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                service?.sendFile(peer.ip, tmp.absolutePath)
                appendLog("[发送] 文件 $name → ${peer.name}")
            } catch (e: Exception) {
                appendLog("[错误] 发送文件失败: ${e.message}")
            }
        }
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            // 顶部状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("传输模式：")
                Spacer(Modifier.width(6.dp))
                var modeExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { modeExpanded = true }) {
                        Text(if (modeIndex == 0) "UDP 局域网" else "WebSocket 中继")
                    }
                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("UDP 局域网 (飞秋兼容)") },
                            onClick = { modeIndex = 0; modeExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("WebSocket 中继") },
                            onClick = { modeIndex = 1; modeExpanded = false }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(status, color = statusColor, style = MaterialTheme.typography.bodyMedium)
            }

            if (modeIndex == 1) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("中继地址 ws://...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { startService() }) { Text("启动") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    stopService()
                    status = "已停止"
                    statusColor = Color.Gray
                    appendLog("[系统] 已停止")
                }) { Text("停止") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        service?.refresh()
                        appendLog("[系统] 已刷新")
                    }
                }) { Text("刷新") }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.weight(1f)) {
                // 左侧用户列表
                Column(Modifier.width(140.dp).fillMaxHeight()) {
                    Text("在线用户", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp)
                    ) {
                        items(users) { peer ->
                            val selected = selectedPeer?.name == peer.name && selectedPeer?.ip == peer.ip
                            Text(
                                text = peer.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedPeer = peer }
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .padding(6.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
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
                            val text = input.trim()
                            val peer = selectedPeer
                            if (text.isEmpty() || peer == null || service == null) return@Button
                            scope.launch(Dispatchers.IO) {
                                try {
                                    service?.sendText(peer.ip, text)
                                    appendLog("[我 → ${peer.name}] $text")
                                    input = ""
                                } catch (e: Exception) {
                                    appendLog("[错误] ${e.message}")
                                }
                            }
                        }) { Text("发送") }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = { pickFileLauncher.launch("*/*") }) {
                            Text("文件")
                        }
                    }
                }
            }

            // 文件接收确认
            pendingOffer?.let { offer ->
                AlertDialog(
                    onDismissRequest = { pendingOffer = null },
                    title = { Text("收到文件") },
                    text = {
                        Text("${offer.from.name} 发来「${offer.info.fileName}」(${formatSize(offer.info.size)})，是否接收？")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val o = offer
                            pendingOffer = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    service?.acceptFile(o)
                                } catch (e: Exception) {
                                    appendLog("[错误] 接收失败: ${e.message}")
                                }
                            }
                        }) { Text("接收") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingOffer = null }) { Text("拒绝") }
                    }
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0)
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024))
    return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
    }
    return uri.lastPathSegment
}
