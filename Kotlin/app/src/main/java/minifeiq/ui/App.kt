package minifeiq.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import minifeiq.data.AppSettings
import minifeiq.data.ChatStore
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
import java.util.Locale

private val WeChatGreen = Color(0xFF07C160)
private val WeChatTopBar = Color(0xFFEDEDED)
private val ConnGray = Color(0xFFBBBBBB)
private val ConnAmber = Color(0xFFFFB300)
private val ConnGreen = Color(0xFF22C55E)

@Composable
fun MiniFeiQApp(context: Context = LocalContext.current) {
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }
    val chatStore = remember { ChatStore(context) }

    // 底栏：0=聊天列表 1=我的
    var tab by remember { mutableIntStateOf(0) }
    // 全屏聊天（盖住底栏）
    var chatPeer by remember { mutableStateOf<Peer?>(null) }

    var modeIndex by remember { mutableIntStateOf(settings.modeIndex) }
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var userName by remember { mutableStateOf(settings.userName) }
    var avatarPath by remember { mutableStateOf(settings.avatarPath) }
    var status by remember { mutableStateOf("未连接") }
    var statusColor by remember { mutableStateOf(ConnGray) }

    var service by remember { mutableStateOf<IpMsgService?>(null) }
    var transport by remember { mutableStateOf<Transport?>(null) }

    val users = remember { mutableStateListOf<Peer>() }
    val unread = remember { mutableStateMapOf<String, Int>() }
    val chatLogs = remember { mutableStateMapOf<String, SnapshotStateList<String>>() }
    var pendingOffer by remember { mutableStateOf<IncomingFileOffer?>(null) }
    var activeFilePeer by remember { mutableStateOf<Peer?>(null) }

    fun peerKey(p: Peer) = ChatStore.peerKey(p)

    fun logsFor(p: Peer): SnapshotStateList<String> =
        chatLogs.getOrPut(peerKey(p)) { mutableStateListOf() }

    fun ensureHistory(p: Peer) {
        val key = peerKey(p)
        if (chatLogs.containsKey(key) && chatLogs[key]!!.isNotEmpty()) return
        val list = chatLogs.getOrPut(key) { mutableStateListOf() }
        if (list.isEmpty()) {
            try {
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                chatStore.recent(key).forEach { row ->
                    val prefix = when (row.direction) {
                        "out" -> "[我]"
                        "sys" -> "[系统]"
                        else -> "[${row.peerName}]"
                    }
                    list.add("${fmt.format(Date(row.createdAt))} $prefix ${row.body}")
                }
            } catch (_: Exception) { }
        }
    }

    fun appendLog(p: Peer?, direction: String, body: String, alsoUi: Boolean = true) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        if (p != null) {
            val key = peerKey(p)
            try { chatStore.add(key, p.name, direction, body) } catch (_: Exception) { }
            if (alsoUi) {
                val prefix = when (direction) {
                    "out" -> "[我]"
                    "sys" -> "[系统]"
                    else -> "[${p.name}]"
                }
                logsFor(p).add("$ts $prefix $body")
            }
            if (direction == "in") {
                val cur = chatPeer
                if (cur == null || peerKey(cur) != key) {
                    unread[key] = (unread[key] ?: 0) + 1
                }
            }
        }
    }

    fun stopService() {
        service?.stop()
        service = null
        transport?.close()
        transport = null
        users.clear()
        status = "未连接"
        statusColor = ConnGray
    }

    fun startService() {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    stopService()
                    status = "连接中..."
                    statusColor = ConnAmber
                }

                val t: Transport = if (modeIndex == 1) {
                    val url = serverUrl.trim()
                    if (url.isBlank()) {
                        withContext(Dispatchers.Main) {
                            status = "请先在「我的」填写中继地址"
                            statusColor = Color.Red
                        }
                        return@launch
                    }
                    WebSocketTransport(url, clientId = "android-${android.os.Build.MODEL}")
                } else {
                    UdpTransport(2425)
                }
                transport = t

                val svc = IpMsgService(
                    t,
                    userName = userName,
                    hostName = android.os.Build.MODEL
                )
                svc.downloadDir = settings.downloadDir

                svc.onPeerOnline = { p ->
                    scope.launch(Dispatchers.Main) {
                        if (users.none { peerKey(it) == peerKey(p) }) users.add(p)
                    }
                }
                svc.onPeerOffline = { p ->
                    scope.launch(Dispatchers.Main) {
                        users.removeAll { peerKey(it) == peerKey(p) }
                    }
                }
                svc.onMessage = { p, text ->
                    scope.launch(Dispatchers.Main) {
                        appendLog(p, "in", text)
                    }
                }
                svc.onFileOffered = { offer ->
                    scope.launch(Dispatchers.Main) {
                        appendLog(offer.from, "sys", "发来文件「${offer.info.fileName}」(${formatSize(offer.info.size)})")
                        pendingOffer = offer
                    }
                }
                svc.onFileProgress = { pr ->
                    scope.launch(Dispatchers.Main) {
                        val line = when {
                            pr.error != null -> "文件 ${pr.fileName} 失败：${pr.error}"
                            pr.done -> {
                                val where = pr.savedPath?.let { " → $it" } ?: ""
                                "文件 ${pr.fileName} 完成 ${formatSize(pr.received)}$where"
                            }
                            pr.total > 0 -> {
                                val pct = (pr.received * 100 / pr.total).toInt()
                                if (pct % 10 == 0) "文件 ${pr.fileName} $pct%" else null
                            }
                            else -> null
                        }
                        if (line != null) {
                            appendLog(activeFilePeer, "sys", line)
                        }
                    }
                }

                svc.start()
                service = svc
                withContext(Dispatchers.Main) {
                    status = if (modeIndex == 1) "已连接中继" else "UDP 已启动"
                    statusColor = ConnGreen
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "连接失败: ${e.message}"
                    statusColor = Color.Red
                }
            }
        }
    }

    // 启动 / 模式变化 → 自动连接
    LaunchedEffect(modeIndex) {
        settings.modeIndex = modeIndex
        startService()
    }

    DisposableEffect(Unit) {
        onDispose { stopService() }
    }

    // ========== UI ==========
    if (chatPeer != null) {
        val peer = chatPeer!!
        ensureHistory(peer)
        unread[peerKey(peer)] = 0
        ChatScreen(
            peer = peer,
            logs = logsFor(peer),
            onBack = { chatPeer = null },
            onSendText = { text ->
                val svc = service
                if (svc == null) {
                    appendLog(peer, "sys", "未连接，请检查「我的」或网络")
                } else {
                    scope.launch(Dispatchers.IO) {
                        try {
                            svc.sendText(peer.ip, text)
                            withContext(Dispatchers.Main) { appendLog(peer, "out", text) }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                appendLog(peer, "sys", "发送失败: ${e.message}")
                            }
                        }
                    }
                }
            },
            onSendFile = { uri ->
                val svc = service ?: return@ChatScreen
                scope.launch(Dispatchers.IO) {
                    try {
                        val name = queryDisplayName(context, uri) ?: "file"
                        val tmp = File(context.cacheDir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tmp).use { output -> input.copyTo(output) }
                        }
                        activeFilePeer = peer
                        svc.sendFile(peer.ip, tmp.absolutePath)
                        withContext(Dispatchers.Main) {
                            appendLog(peer, "out", "[文件] $name")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            appendLog(peer, "sys", "发文件失败: ${e.message}")
                        }
                    }
                }
            }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.Chat, contentDescription = "聊天") },
                        label = { Text("聊天") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Person, contentDescription = "我的") },
                        label = { Text("我的") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> ChatListScreen(
                        users = users,
                        unread = unread,
                        status = status,
                        statusColor = statusColor,
                        modeIndex = modeIndex,
                        onModeChange = { modeIndex = it },
                        onOpenChat = { p ->
                            ensureHistory(p)
                            unread[peerKey(p)] = 0
                            chatPeer = p
                        },
                        onRefresh = {
                            scope.launch(Dispatchers.IO) {
                                try { service?.announceOnline() } catch (_: Exception) { }
                            }
                        }
                    )
                    else -> MeScreen(
                        userName = userName,
                        avatarPath = avatarPath,
                        serverUrl = serverUrl,
                        modeIndex = modeIndex,
                        status = status,
                        statusColor = statusColor,
                        onSave = { name, url, avatar ->
                            userName = name
                            serverUrl = url
                            avatarPath = avatar
                            settings.userName = name
                            settings.serverUrl = url
                            settings.avatarPath = avatar
                            startService()
                        },
                        onModeChange = { modeIndex = it }
                    )
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
                Text("${offer.from.name} 发来\n${offer.info.fileName}\n${formatSize(offer.info.size)}")
            },
            confirmButton = {
                TextButton(onClick = {
                    val o = offer
                    pendingOffer = null
                    activeFilePeer = o.from
                    scope.launch(Dispatchers.IO) {
                        try {
                            service?.acceptFile(o)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                appendLog(o.from, "sys", "接收失败: ${e.message}")
                            }
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

@Composable
private fun ConnDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ChatListScreen(
    users: List<Peer>,
    unread: Map<String, Int>,
    status: String,
    statusColor: Color,
    modeIndex: Int,
    onModeChange: (Int) -> Unit,
    onOpenChat: (Peer) -> Unit,
    onRefresh: () -> Unit
) {
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
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF12B7F5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                peer.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
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

@Composable
private fun MeScreen(
    userName: String,
    avatarPath: String?,
    serverUrl: String,
    modeIndex: Int,
    status: String,
    statusColor: Color,
    onSave: (name: String, url: String, avatar: String?) -> Unit,
    onModeChange: (Int) -> Unit
) {
    val context = LocalContext.current
    var name by remember(userName) { mutableStateOf(userName) }
    var url by remember(serverUrl) { mutableStateOf(serverUrl) }
    var avatar by remember(avatarPath) { mutableStateOf(avatarPath) }
    var savedTip by remember { mutableStateOf(false) }

    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val dest = File(context.filesDir, "avatar.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            avatar = dest.absolutePath
        } catch (_: Exception) { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(WeChatTopBar)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            ConnDot(statusColor)
            Spacer(Modifier.width(6.dp))
            Text(status, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }

        // 头像资料卡
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .clickable { pickAvatar.launch("image/*") }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bmp = remember(avatar) {
                avatar?.let { path ->
                    val f = File(path)
                    if (f.exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
                }
            }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF12B7F5)),
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(bmp, contentDescription = "头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(
                        name.firstOrNull()?.uppercaseChar()?.toString() ?: "F",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(name.ifBlank { "未设置昵称" }, fontWeight = FontWeight.SemiBold)
                Text("点击更换头像", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
            Text("用户名", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 32) name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)
            )

            Text("WebSocket 中继地址", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                placeholder = { Text(AppSettings.DEFAULT_URL) }
            )
            Text(
                "仅在选择「WebSocket 中继」时使用；保存后自动重连。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(Modifier.height(12.dp))
            Text("传输模式", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Row(Modifier.padding(top = 6.dp)) {
                listOf("UDP 局域网", "WebSocket 中继").forEachIndexed { i, label ->
                    FilterChip(
                        selected = modeIndex == i,
                        onClick = { onModeChange(i) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                onSave(name.trim().ifEmpty { "Android" }, url.trim(), avatar)
                savedTip = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen)
        ) {
            Text("保存并重连")
        }
        if (savedTip) {
            Text(
                "已保存",
                color = WeChatGreen,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

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

    BackHandler { onBack() }

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFEDEDED))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(WeChatTopBar)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${peer.hostName} · ${peer.ip.hostAddress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            items(logs) { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
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
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty()) return@Button
                    onSendText(text)
                    input = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen)
            ) { Text("发送") }
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
