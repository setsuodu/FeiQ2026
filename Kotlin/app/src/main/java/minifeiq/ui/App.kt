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
import minifeiq.data.AvatarCache
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
    val chatLogs = remember { mutableStateMapOf<String, SnapshotStateList<ChatUiMsg>>() }
    var pendingOffer by remember { mutableStateOf<IncomingFileOffer?>(null) }
    var activeFilePeer by remember { mutableStateOf<Peer?>(null) }
    /** 头像缓存更新计数，用于触发 Compose 重绘 */
    var avatarTick by remember { mutableIntStateOf(0) }

    fun peerKey(p: Peer) = ChatStore.peerKey(p)

    fun logsFor(p: Peer): SnapshotStateList<ChatUiMsg> =
        chatLogs.getOrPut(peerKey(p)) { mutableStateListOf() }

    fun ensureHistory(p: Peer) {
        val key = peerKey(p)
        val list = chatLogs.getOrPut(key) { mutableStateListOf() }
        if (list.isNotEmpty()) return
        try {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            chatStore.recent(key).forEach { row ->
                list.add(parseStoredBody(row.direction, row.body, fmt.format(Date(row.createdAt))))
            }
        } catch (_: Exception) { }
    }

    fun appendLog(p: Peer?, direction: String, body: String, alsoUi: Boolean = true) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        if (p != null) {
            val key = peerKey(p)
            try { chatStore.add(key, p.name, direction, body) } catch (_: Exception) { }
            if (alsoUi) {
                logsFor(p).add(parseStoredBody(direction, body, ts))
            }
            if (direction == "in") {
                val cur = chatPeer
                if (cur == null || peerKey(cur) != key) {
                    unread[key] = (unread[key] ?: 0) + 1
                }
            }
        }
    }

    /** 在气泡上更新/创建传输进度（不刷屏 sys 文字） */
    /** 进度叠在同一条富媒体气泡上（progress!=null 显示条；done 后 progress=null） */
    fun upsertProgress(p: Peer?, fileName: String, received: Long, total: Long, done: Boolean, savedPath: String?, error: String?) {
        if (p == null) return
        val list = logsFor(p)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        fun matchIdx(): Int = list.indexOfLast {
            it.kind != MsgKind.Text && it.kind != MsgKind.Url &&
                (it.body.contains(fileName) || it.filePath?.endsWith(fileName) == true)
        }

        if (error != null) {
            val idx = matchIdx()
            val msg = ChatUiMsg("sys", "文件 $fileName 失败：$error", ts)
            if (idx >= 0) list[idx] = msg else list.add(msg)
            return
        }

        val pct = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
        val idx = matchIdx()

        if (done) {
            if (savedPath != null) {
                val kind = detectMsgKind(fileName)
                val media = isAutoReceiveMedia(fileName)
                val storeBody = when (kind) {
                    MsgKind.Image -> "[图片] $fileName"
                    MsgKind.Audio -> "[音乐] $fileName"
                    MsgKind.Video -> "[视频] $fileName"
                    else -> "[文件] $fileName"
                } + "|$savedPath"
                val finalMsg = parseStoredBody("in", storeBody, ts)
                if (idx >= 0) list[idx] = finalMsg else list.add(finalMsg)
                try { chatStore.add(peerKey(p), p.name, "in", storeBody) } catch (_: Exception) { }
                if (!media) list.add(ChatUiMsg("sys", "文件已保存：$savedPath", ts))
            } else if (idx >= 0) {
                // 发送完成：去掉进度条，保留富媒体
                list[idx] = list[idx].copy(progress = null)
            }
            return
        }

        // 传输中：只更新已有气泡的 progress，绝不新开 Progress 气泡
        if (idx >= 0) {
            list[idx] = list[idx].copy(progress = pct, time = ts)
        } else {
            val kind = detectMsgKind(fileName)
            list.add(
                ChatUiMsg(
                    direction = "out",
                    body = "${kindIcon(kind)} $fileName",
                    time = ts,
                    kind = kind,
                    progress = pct
                )
            )
        }
    }

    fun clearChat(p: Peer) {
        val key = peerKey(p)
        try { chatStore.clear(key) } catch (_: Exception) { }
        chatLogs[key]?.clear()
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
                    // 向对方推送自己的头像（FeiQ2026）
                    scope.launch(Dispatchers.IO) {
                        val msg = AvatarCache.buildSyncMessage(settings.avatarPath) ?: return@launch
                        try {
                            svc.sendText(p.ip, msg, requireAck = false)
                        } catch (_: Exception) { }
                    }
                }
                svc.onPeerOffline = { p ->
                    scope.launch(Dispatchers.Main) {
                        users.removeAll { peerKey(it) == peerKey(p) }
                    }
                }
                svc.onMessage = { p, text ->
                    scope.launch(Dispatchers.Main) {
                        val jpeg = AvatarCache.tryParse(text)
                        if (jpeg != null) {
                            AvatarCache.save(context, peerKey(p), jpeg)
                            avatarTick++ // 触发列表/气泡重绘
                            return@launch
                        }
                        appendLog(p, "in", text)
                    }
                }
                svc.onFileOffered = { offer ->
                    scope.launch(Dispatchers.Main) {
                        val name = offer.info.fileName
                        if (isAutoReceiveMedia(name)) {
                            // 媒体：静默自动接收，不弹框、不刷 sys
                            activeFilePeer = offer.from
                            // 先放一条进度气泡
                            upsertProgress(offer.from, name, 0, offer.info.size, false, null, null)
                            scope.launch(Dispatchers.IO) {
                                try {
                                    service?.acceptFile(offer)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        upsertProgress(offer.from, name, 0, 0, true, null, e.message)
                                    }
                                }
                            }
                        } else {
                            pendingOffer = offer
                        }
                    }
                }
                svc.onFileProgress = { pr ->
                    scope.launch(Dispatchers.Main) {
                        val peer = activeFilePeer
                        upsertProgress(
                            peer,
                            pr.fileName,
                            pr.received,
                            pr.total,
                            pr.done,
                            pr.savedPath,
                            pr.error
                        )
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
            onClearHistory = { clearChat(peer) },
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
                        val kind = detectMsgKind(name)
                        val prefix = when (kind) {
                            MsgKind.Image -> "[图片]"
                            MsgKind.Audio -> "[音乐]"
                            MsgKind.Video -> "[视频]"
                            else -> "[文件]"
                        }
                        val storeBody = "$prefix $name|${tmp.absolutePath}"
                        val ts0 = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        withContext(Dispatchers.Main) {
                            // 只插一条富媒体气泡，progress=0 叠在上面
                            val list = logsFor(peer)
                            val bubble = parseStoredBody("out", storeBody, ts0).copy(progress = 0f)
                            list.add(bubble)
                            try { chatStore.add(peerKey(peer), peer.name, "out", storeBody) } catch (_: Exception) { }
                        }
                        svc.sendFile(peer.ip, tmp.absolutePath)
                        withContext(Dispatchers.Main) {
                            // 发送结束：同一条气泡去掉进度条
                            val list = logsFor(peer)
                            val idx = list.indexOfLast {
                                it.kind != MsgKind.Text && it.kind != MsgKind.Url &&
                                    (it.body.contains(name) || it.filePath?.endsWith(name) == true)
                            }
                            if (idx >= 0) list[idx] = list[idx].copy(progress = null)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            appendLog(peer, "sys", "发文件失败: ${e.message}")
                        }
                    }
                }
            },
            selfAvatarPath = avatarPath
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
                        avatarTick = avatarTick,
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
                            // 头像变更后推给所有在线 FeiQ2026
                            val msg = AvatarCache.buildSyncMessage(avatar)
                            if (msg != null) {
                                val svc = service
                                val snapshot = users.toList()
                                if (svc != null) {
                                    scope.launch(Dispatchers.IO) {
                                        for (p in snapshot) {
                                            try { svc.sendText(p.ip, msg, requireAck = false) } catch (_: Exception) { }
                                        }
                                    }
                                }
                            }
                            startService()
                        },
                        onModeChange = { modeIndex = it }
                    )
                }
            }
        }
    }

    // 普通文件接收确认（媒体已自动接收）
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
                    upsertProgress(o.from, o.info.fileName, 0, o.info.size, false, null, null)
                    scope.launch(Dispatchers.IO) {
                        try {
                            service?.acceptFile(o)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                upsertProgress(o.from, o.info.fileName, 0, 0, true, null, e.message)
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
    logs: List<ChatUiMsg>,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
    onSendText: (String) -> Unit,
    onSendFile: (Uri) -> Unit,
    selfAvatarPath: String? = null
) {
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

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
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("···") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("清空聊天记录") },
                        onClick = {
                            menuOpen = false
                            confirmClear = true
                        }
                    )
                }
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
            items(logs) { msg ->
                val ctx = LocalContext.current
                ChatBubbleRow(
                    peerName = peer.name,
                    msg = msg,
                    selfAvatarPath = selfAvatarPath,
                    peerAvatarPath = AvatarCache.getPath(ctx, ChatStore.peerKey(peer)),
                    context = ctx
                )
                Spacer(Modifier.height(8.dp))
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空聊天记录") },
            text = { Text("确定清空与 ${peer.name} 的本地聊天记录？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ChatBubbleRow(
    peerName: String,
    msg: ChatUiMsg,
    selfAvatarPath: String? = null,
    peerAvatarPath: String? = null,
    context: Context
) {
    when {
        msg.direction == "sys" -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    msg.body,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888),
                    modifier = Modifier
                        .background(Color(0x22000000), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        msg.direction == "out" -> {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f, fill = false)) {
                    Surface(
                        color = Color(0xFF95EC69),
                        shape = MaterialTheme.shapes.medium,
                        shadowElevation = 0.dp
                    ) {
                        BubbleContent(msg, context)
                    }
                    Text(msg.time, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Spacer(Modifier.width(8.dp))
                AvatarCircle(
                    letter = "我",
                    bg = WeChatGreen,
                    imagePath = selfAvatarPath
                )
            }
        }
        else -> { // in
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                AvatarCircle(
                    letter = peerName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    bg = Color(0xFF12B7F5),
                    imagePath = peerAvatarPath
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f, fill = false)) {
                    Surface(
                        color = Color.White,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        BubbleContent(msg, context)
                    }
                    Text(msg.time, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun BubbleContent(msg: ChatUiMsg, context: Context) {
    val openFile: () -> Unit = openFile@{
        val path = msg.filePath
        if (msg.fileMissing || path.isNullOrEmpty()) return@openFile
        if (msg.kind == MsgKind.Url) {
            try {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(path))
                )
            } catch (_: Exception) { }
            return@openFile
        }
        // 图片：应用内全屏查看，不走系统图库
        if (msg.kind == MsgKind.Image) {
            try {
                context.startActivity(
                    android.content.Intent(context, ImageViewerActivity::class.java).apply {
                        putExtra(ImageViewerActivity.EXTRA_PATH, path)
                    }
                )
            } catch (_: Exception) { }
            return@openFile
        }
        try {
            val file = File(path)
            if (!file.exists()) return@openFile
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", file
                )
            } catch (_: Exception) {
                Uri.fromFile(file)
            }
            val mime = when (msg.kind) {
                MsgKind.Audio -> "audio/*"
                MsgKind.Video -> "video/*"
                else -> "*/*"
            }
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        // 富媒体本体
        when {
            msg.kind == MsgKind.Image && !msg.fileMissing && !msg.filePath.isNullOrEmpty() && File(msg.filePath).exists() -> {
                val bmp = remember(msg.filePath) {
                    try {
                        BitmapFactory.decodeFile(msg.filePath)?.asImageBitmap()
                    } catch (_: Exception) { null }
                }
                if (bmp != null) {
                    Box {
                        Image(
                            bitmap = bmp,
                            contentDescription = "图片",
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                                .heightIn(max = 180.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { openFile() },
                            contentScale = ContentScale.Fit
                        )
                        // 进度叠在图片底部
                        if (msg.progress != null) {
                            LinearProgressIndicator(
                                progress = { msg.progress },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(4.dp),
                            )
                        }
                    }
                } else {
                    FileCard(msg, onClick = openFile)
                }
            }
            msg.kind == MsgKind.Image || msg.kind == MsgKind.Audio || msg.kind == MsgKind.Video || msg.kind == MsgKind.File || msg.kind == MsgKind.Progress -> {
                FileCard(msg, onClick = openFile)
            }
            msg.kind == MsgKind.Url -> {
                Text(
                    msg.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF12B7F5),
                    modifier = Modifier.clickable { openFile() }
                )
            }
            else -> {
                Text(msg.body, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 非图片富媒体：进度条叠在卡片下方（同一气泡内）
        if (msg.progress != null && msg.kind != MsgKind.Image) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { msg.progress },
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 200.dp)
                    .height(4.dp),
            )
        }
    }
}

@Composable
private fun FileCard(msg: ChatUiMsg, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable { onClick() }
            .background(Color(0xFFF8F8F8), MaterialTheme.shapes.small)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (msg.fileMissing) "⚠️" else kindIcon(msg.kind),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                msg.body.removePrefix("⚠️ ").removePrefix(kindIcon(msg.kind)).trim()
                    .ifEmpty { msg.filePath?.let { File(it).name } ?: "文件" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2
            )
            Text(
                if (msg.fileMissing) "文件已失效" else kindLabel(msg.kind),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun AvatarCircle(letter: String, bg: Color, imagePath: String? = null) {
    val bmp = remember(imagePath) {
        if (imagePath.isNullOrEmpty()) null
        else try {
            BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
        } catch (_: Exception) { null }
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = "头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(letter.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

enum class MsgKind { Text, Image, Audio, Video, File, Url, Progress }

data class ChatUiMsg(
    val direction: String, // in / out / sys
    val body: String,
    val time: String,
    val kind: MsgKind = MsgKind.Text,
    val filePath: String? = null,
    /** null=不在传输；0f..1f=进度中 */
    val progress: Float? = null,
    val fileMissing: Boolean = false
)

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "tiff", "tif")
private val AUDIO_EXTS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "ape", "opus")
private val VIDEO_EXTS = setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "ts", "mpeg", "mpg")

private fun detectMsgKind(fileName: String): MsgKind {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return when {
        ext in IMAGE_EXTS -> MsgKind.Image
        ext in AUDIO_EXTS -> MsgKind.Audio
        ext in VIDEO_EXTS -> MsgKind.Video
        else -> MsgKind.File
    }
}

private fun isAutoReceiveMedia(fileName: String): Boolean {
    val k = detectMsgKind(fileName)
    return k == MsgKind.Image || k == MsgKind.Audio || k == MsgKind.Video
}

private fun kindIcon(kind: MsgKind): String = when (kind) {
    MsgKind.Image -> "🖼️"
    MsgKind.Audio -> "🎵"
    MsgKind.Video -> "🎬"
    MsgKind.File -> "📄"
    MsgKind.Url -> "🔗"
    MsgKind.Progress -> "⏳"
    else -> ""
}

private fun kindLabel(kind: MsgKind): String = when (kind) {
    MsgKind.Image -> "图片"
    MsgKind.Audio -> "音乐"
    MsgKind.Video -> "视频"
    MsgKind.File -> "文件"
    else -> ""
}

/** 从存库文本解析富媒体： [图片] name|/path 或纯文本/URL */
private fun parseStoredBody(direction: String, body: String, time: String): ChatUiMsg {
    if (direction == "sys") {
        return ChatUiMsg(direction, body, time, MsgKind.Text)
    }
    // [类型] name|path
    if (body.startsWith("[") && body.contains('|')) {
        val pipe = body.lastIndexOf('|')
        val head = body.substring(0, pipe)
        val path = body.substring(pipe + 1)
        val name = File(path).name.ifEmpty { head.substringAfter(']').trim() }
        val kind = when {
            head.startsWith("[图片]") -> MsgKind.Image
            head.startsWith("[音乐]") -> MsgKind.Audio
            head.startsWith("[视频]") -> MsgKind.Video
            head.startsWith("[文件]") -> MsgKind.File
            else -> detectMsgKind(name)
        }
        val missing = path.isNotEmpty() && !File(path).exists()
        return ChatUiMsg(
            direction = direction,
            body = if (missing) "⚠️ $name（已失效）" else "${kindIcon(kind)} $name",
            time = time,
            kind = kind,
            filePath = path,
            fileMissing = missing
        )
    }
    // 旧格式 [文件] name
    if (body.startsWith("[文件]") || body.startsWith("[图片]") || body.startsWith("[音乐]") || body.startsWith("[视频]")) {
        val name = body.substringAfter(']').trim()
        val kind = detectMsgKind(name)
        return ChatUiMsg(direction, "${kindIcon(kind)} $name", time, kind)
    }
    // URL
    val trimmed = body.trim()
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return ChatUiMsg(direction, "🔗 $trimmed", time, MsgKind.Url, filePath = trimmed)
    }
    return ChatUiMsg(direction, body, time, MsgKind.Text)
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
