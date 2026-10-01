package minifeiq.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import minifeiq.ShareBridge
import minifeiq.shareSendFile
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

    /** 当前在线（协议实时） */
    val users = remember { mutableStateListOf<Peer>() }
    /** 会话列表：在线 ∪ 历史，离线不删除 */
    val sessions = remember { mutableStateListOf<Peer>() }
    val unread = remember { mutableStateMapOf<String, Int>() }
    val chatLogs = remember { mutableStateMapOf<String, SnapshotStateList<ChatUiMsg>>() }
    var pendingOffer by remember { mutableStateOf<IncomingFileOffer?>(null) }
    var activeFilePeer by remember { mutableStateOf<Peer?>(null) }
    /** 头像缓存更新计数，用于触发 Compose 重绘 */
    var avatarTick by remember { mutableIntStateOf(0) }

    fun peerKey(p: Peer) = ChatStore.peerKey(p)

    fun onlineKeys(): Set<String> = users.map { peerKey(it) }.toSet()

    /** 合入会话列表：已有则更新 name/ip，没有则插到最前 */
    fun upsertSession(p: Peer) {
        val key = peerKey(p)
        val idx = sessions.indexOfFirst { peerKey(it) == key }
        if (idx >= 0) sessions[idx] = p else sessions.add(0, p)
    }

    /** 从 ChatStore 加载历史会话，与当前在线合并 */
    fun loadSessionsFromStore() {
        try {
            val fromDb = chatStore.listRecentSessions()
            val ordered = mutableListOf<Peer>()
            val seen = mutableSetOf<String>()
            // 在线优先
            for (p in users) {
                val k = peerKey(p)
                if (k !in seen) {
                    ordered.add(p)
                    seen.add(k)
                }
            }
            // 历史补齐离线
            for (s in fromDb) {
                if (s.peerKey !in seen) {
                    ordered.add(ChatStore.peerFromSession(s))
                    seen.add(s.peerKey)
                }
            }
            sessions.clear()
            sessions.addAll(ordered)
        } catch (_: Exception) { }
    }

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
            upsertSession(p)
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
        // 保留会话：历史联系人仍显示
        loadSessionsFromStore()
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
                        val key = peerKey(p)
                        val i = users.indexOfFirst { peerKey(it) == key }
                        if (i >= 0) users[i] = p else users.add(p)
                        upsertSession(p)
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
                        // 只移出在线集合，会话列表保留
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
                    loadSessionsFromStore()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "连接失败: ${e.message}"
                    statusColor = Color.Red
                }
            }
        }
    }

    // 启动时先从历史恢复会话列表
    LaunchedEffect(Unit) {
        loadSessionsFromStore()
    }

    // 启动 / 模式变化 → 自动连接
    LaunchedEffect(modeIndex) {
        settings.modeIndex = modeIndex
        startService()
    }

    DisposableEffect(Unit) {
        onDispose { stopService() }
    }

    // 系统分享：选人 = 会话列表（在线∪最近）；发送时优先用在线同 key Peer
    SideEffect {
        val online = onlineKeys()
        val shareList = sessions.toList().sortedByDescending { peerKey(it) in online }
        ShareBridge.updatePeers(shareList)
        ShareBridge.sendToPeer = { peer, uri ->
            val live = users.firstOrNull { peerKey(it) == peerKey(peer) } ?: peer
            shareSendFile(
                scope = scope,
                context = context,
                service = service,
                peer = live,
                uri = uri,
                onPrepared = { name, path ->
                    activeFilePeer = live
                    ensureHistory(live)
                    chatPeer = live
                    val kind = detectMsgKind(name)
                    val prefix = when (kind) {
                        MsgKind.Image -> "[图片]"
                        MsgKind.Audio -> "[音乐]"
                        MsgKind.Video -> "[视频]"
                        else -> "[文件]"
                    }
                    val storeBody = "$prefix $name|$path"
                    val ts0 = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    val list = logsFor(live)
                    list.add(parseStoredBody("out", storeBody, ts0).copy(progress = 0f))
                    try { chatStore.add(peerKey(live), live.name, "out", storeBody) } catch (_: Exception) { }
                },
                onDone = { name ->
                    val list = logsFor(live)
                    val idx = list.indexOfLast {
                        it.kind != MsgKind.Text && it.kind != MsgKind.Url &&
                            (it.body.contains(name) || it.filePath?.endsWith(name) == true)
                    }
                    if (idx >= 0) list[idx] = list[idx].copy(progress = null)
                },
                onError = { msg -> appendLog(live, "sys", msg) }
            )
        }
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
                        sessions = sessions.toList(),
                        onlineKeys = onlineKeys(),
                        unread = unread,
                        status = status,
                        statusColor = statusColor,
                        modeIndex = modeIndex,
                        avatarTick = avatarTick,
                        onModeChange = { modeIndex = it },
                        onOpenChat = { p ->
                            // 若在线有同 key，优先用在线 Peer（真实 IP）
                            val live = users.firstOrNull { peerKey(it) == peerKey(p) } ?: p
                            ensureHistory(live)
                            unread[peerKey(live)] = 0
                            chatPeer = live
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
