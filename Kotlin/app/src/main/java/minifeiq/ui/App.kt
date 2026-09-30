package minifeiq.ui
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    // ================= 导航 =================
    var screen by remember { mutableStateOf("list") }          // "list"=用户列表  "chat"=全屏聊天
    var chatPeer by remember { mutableStateOf<Peer?>(null) }

    // ================= 连接 =================
    var modeIndex by remember { mutableIntStateOf(0) }          // 0=UDP 1=WS
    var serverUrl by remember { mutableStateOf("ws://127.0.0.1:9000/ws") }
    var status by remember { mutableStateOf("未连接") }
    var statusColor by remember { mutableStateOf(Color.Gray) }

    var service by remember { mutableStateOf<IpMsgService?>(null) }
    var transport by remember { mutableStateOf<Transport?>(null) }
    var pendingOffer by remember { mutableStateOf<IncomingFileOffer?>(null) }
    var activeFilePeer by remember { mutableStateOf<Peer?>(null) }   // 当前文件传输归属的用户

    // ================= 数据 =================
    val users = remember { mutableStateListOf<Peer>() }
    // 每个用户一份独立聊天记录，key = "ip|name"
    val chatLogs = remember { mutableMapOf<String, SnapshotStateList<String>>() }
    // 每个用户未读消息数
    val unread = remember { mutableStateMapOf<String, Int>() }
    // 系统日志（首页底部可展开）
    val systemLogs = remember { mutableStateListOf<String>() }
    var showSystemLog by remember { mutableStateOf(false) }

    val downloadDir = remember { File(context.filesDir, "downloads").also { it.mkdirs() } }

    fun chatKey(p: Peer) = "${p.ip}|${p.name}"

    fun logsFor(p: Peer): SnapshotStateList<String> =
        chatLogs.getOrPut(chatKey(p)) { mutableStateListOf() }

    fun ts() = SimpleDateFormat("HH:mm:ss").format(Date())

    fun sysLog(line: String) {
        systemLogs.add("${ts()} $line")
        if (systemLogs.size > 500) systemLogs.removeAt(0)
    }

    fun peerLog(p: Peer, line: String) {
        val list = logsFor(p)
        list.add("${ts()} $line")
        if (list.size > 2000) list.removeAt(0)
        // 不在该用户的聊天页 → 计入未读
        val cur = chatPeer
        if (screen != "chat" || cur == null || chatKey(cur) != chatKey(p)) {
            unread[chatKey(p)] = (unread[chatKey(p)] ?: 0) + 1
        }
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
                    sysLog("[系统] 使用 WebSocket 中继 → $serverUrl")
                    WebSocketTransport(serverUrl, clientId = "android-${android.os.Build.MODEL}")
                } else {
                    sysLog("[系统] 使用 UDP 局域网（飞秋2013兼容）")
                    UdpTransport(2425)
                }
                transport = t

                val svc = IpMsgService(t, userName = "Android", hostName = android.os.Build.MODEL)
                svc.downloadDir = downloadDir

                svc.onPeerOnline = { p ->
                    scope.launch(Dispatchers.Main) {
                        if (users.none { it.name == p.name && it.ip == p.ip }) users.add(p)
                        sysLog("[上线] ${p.name}")
                    }
                }
                svc.onPeerOffline = { p ->
                    scope.launch(Dispatchers.Main) {
                        users.removeAll { it.name == p.name && it.ip == p.ip }
                        sysLog("[下线] ${p.name}")
                    }
                }
                svc.onMessage = { p, text ->
                    scope.launch(Dispatchers.Main) { peerLog(p, "[${p.name}] $text") }
                }
                svc.onFileOffered = { offer ->
                    scope.launch(Dispatchers.Main) {
                        val sizeStr = formatSize(offer.info.size)
                        peerLog(offer.from, "[文件] ${offer.from.name} 发来「${offer.info.fileName}」($sizeStr)")
                        pendingOffer = offer
                    }
                }
                svc.onFileProgress = { pr ->
                    scope.launch(Dispatchers.Main) {
                        val line = when {
                            pr.error != null -> "[文件] ${pr.fileName} 失败：${pr.error}"
                            pr.done -> {
                                val where = pr.savedPath?.let { " → $it" } ?: ""
                                "[文件] ${pr.fileName} 完成 ${formatSize(pr.received)}$where"
                            }
                            pr.total > 0 -> {
                                val pct = (pr.received * 100 / pr.total).toInt()
                                if (pct % 10 == 0) "[文件] ${pr.fileName} $pct%" else null
                            }
                            else -> null
                        }
                        if (line != null) {
                            val target = activeFilePeer
                            if (target != null) peerLog(target, line) else sysLog(line)
                        }
                    }
                }

                svc.start()
                service = svc

                withContext(Dispatchers.Main) {
                    status = if (modeIndex == 1) "已连接中继" else "UDP 已启动"
                    statusColor = Color(0xFF2E7D32)
                }
                sysLog("[系统] 启动成功")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "连接失败"
                    statusColor = Color.Red
                }
                sysLog("[错误] ${e.message}")
            }
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize()) {

            if (screen == "chat" && chatPeer != null) {
                // =============== 第二层：全屏聊天 ===============
                val peer = chatPeer!!
                key(chatKey(peer)) {
                    ChatScreen(
                        peer = peer,
                        logs = logsFor(peer),
                        onBack = { screen = "list"; chatPeer = null },
                        onSendText = { text ->
                            if (service == null) {
                                peerLog(peer, "[系统] 服务未启动，请先返回启动")
                            } else {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        service?.sendText(peer.ip, text)
                                        peerLog(peer, "[我] $text")
                                    } catch (e: Exception) {
                                        peerLog(peer, "[错误] ${e.message}")
                                    }
                                }
                            }
                        },
                        onSendFile = { uri ->
                            if (service == null) {
                                peerLog(peer, "[系统] 服务未启动，请先返回启动")
                            } else {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val name = queryDisplayName(context, uri) ?: "file.bin"
                                        val tmp = File(context.cacheDir, name)
                                        context.contentResolver.openInputStream(uri)?.use { input ->
                                            FileOutputStream(tmp).use { output -> input.copyTo(output) }
                                        }
                                        activeFilePeer = peer
                                        service?.sendFile(peer.ip, tmp.absolutePath)
                                        peerLog(peer, "[我] 发送文件 $name (${formatSize(tmp.length())})")
                                    } catch (e: Exception) {
                                        peerLog(peer, "[错误] 发送文件失败: ${e.message}")
                                    }
                                }
                            }
                        }
                    )
                }
            } else {
                // =============== 第一层：在线用户列表（首页） ===============
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()        // ← 避开状态栏/刘海，标题和按钮就被压下来了
                        .navigationBarsPadding()    // ← 避开底部手势条
                        .imePadding()               // ← 键盘弹出时整体上移
                        .padding(12.dp)
                ) {

                    // 标题 + 状态
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("迷你飞秋", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(8.dp))

                    // 传输模式
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
                            sysLog("[系统] 已停止")
                        }) { Text("停止") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                service?.refresh()
                                sysLog("[系统] 已刷新")
                            }
                        }) { Text("刷新") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { showSystemLog = !showSystemLog }) {
                            Text(if (showSystemLog) "收起日志" else "系统日志")
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Text("在线用户 (${users.size})", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))

                    if (users.isEmpty()) {
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "暂无在线用户\n点击「启动」开始搜索",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(users) { peer ->
                                val peerKey = chatKey(peer)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            unread.remove(peerKey)   // 进入聊天清零未读
                                            chatPeer = peer
                                            screen = "chat"
                                        }
                                        .padding(horizontal = 4.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 头像（取名字第一个字）
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            peer.name.take(1),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(peer.name, fontWeight = FontWeight.Bold)
                                        Text(
                                            "IP: ${peer.ip.hostAddress ?: peer.ip}",     // ← 这里也改
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                    // 未读角标
                                    val n = unread[peerKey]
                                    if (n != null && n > 0) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(Color(0xFFFA5151), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                if (n > 99) "99+" else n.toString(),
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        }
                    }

                    // 可展开的系统日志
                    if (showSystemLog) {
                        Spacer(Modifier.height(6.dp))
                        Text("系统日志", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(
                            modifier = Modifier
                                .height(120.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(6.dp)
                        ) {
                            items(systemLogs) { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // =============== 文件接收确认（两层都可见） ===============
            pendingOffer?.let { offer ->
                AlertDialog(
                    onDismissRequest = { pendingOffer = null },
                    title = { Text("收到文件") },
                    text = { Text("${offer.from.name} 发来「${offer.info.fileName}」(${formatSize(offer.info.size)})，是否接收？") },
                    confirmButton = {
                        TextButton(onClick = {
                            val o = offer
                            pendingOffer = null
                            activeFilePeer = o.from
                            scope.launch(Dispatchers.IO) {
                                try {
                                    service?.acceptFile(o)
                                } catch (e: Exception) {
                                    sysLog("[错误] 接收失败: ${e.message}")
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

/** 第二层：与某个用户的全屏聊天页 */
@Composable
private fun ChatScreen(
    peer: Peer,
    logs: List<String>,
    onBack: () -> Unit,
    onSendText: (String) -> Unit,
    onSendFile: (Uri) -> Unit
) {
    var input by remember { mutableStateOf("") }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) onSendFile(uri) }

    // 系统返回键 = 返回用户列表
    BackHandler { onBack() }

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()        // ← 顶栏「← 返回」不再钻进刘海
            .navigationBarsPadding()    // ← 底部输入框不再被手势条挡住
            .imePadding()               // ← 点输入框弹出键盘时，输入框自动浮在键盘上方
    ) {

        // 顶栏：返回 + 对方信息
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(4.dp))
            Column {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("IP: ${peer.ip}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))

        // 聊天记录（全屏）
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            items(logs) { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
            }
        }

        // 输入区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                if (text.isEmpty()) return@Button
                onSendText(text)
                input = ""
            }) { Text("发送") }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = { pickFileLauncher.launch("*/*") }) { Text("文件") }
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
