package com.setsuodu.feiq.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.setsuodu.feiq.ShareBridge
import com.setsuodu.feiq.shareSendFile
import com.setsuodu.feiq.data.AppSettings
import com.setsuodu.feiq.data.AvatarCache
import com.setsuodu.feiq.data.ChatStore
import com.setsuodu.feiq.data.OutboxStore
import com.setsuodu.feiq.service.FeiqKeepAliveService
import java.net.InetAddress
import com.setsuodu.feiq.service.IncomingFileOffer
import com.setsuodu.feiq.service.IpMsgService
import com.setsuodu.feiq.service.Peer
import com.setsuodu.feiq.transport.Transport
import com.setsuodu.feiq.transport.UdpTransport
import com.setsuodu.feiq.transport.WebSocketTransport
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
    val outbox = remember { OutboxStore(context) }

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


    /** 中继连上后：冲刷本机待发文本 */
    fun flushOutbox(svc: IpMsgService) {
        scope.launch(Dispatchers.IO) {
            val rows = try { outbox.listAll() } catch (_: Exception) { emptyList() }
            if (rows.isEmpty()) return@launch
            for (row in rows) {
                try {
                    val ip = InetAddress.getByName("0.0.0.0")
                    svc.sendText(ip, row.body, requireAck = false)
                    outbox.delete(row.id)
                    withContext(Dispatchers.Main) {
                        val peer = users.firstOrNull { peerKey(it) == row.peerKey }
                            ?: Peer(row.peerName, row.hostName, ip)
                        appendLog(peer, "out", row.body)
                        appendLog(peer, "sys", "（离线队列已补发）")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        status = "待发补发失败: ${e.message}"
                        statusColor = Color.Red
                    }
                    break
                }
            }
        }
    }


    fun ensureHistory(p: Peer) {
        val key = peerKey(p)
        val list = chatLogs.getOrPut(key) { mutableStateListOf() }
        try {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val fromDb = chatStore.recent(key)
            // DB 是权威来源：离线消息已由 appendLog 写入。
            // 若先收离线再进会话，旧逻辑会因 list 非空而跳过加载，导致历史被“覆盖”。
            list.clear()
            fromDb.forEach { row ->
                list.add(parseStoredBody(row.direction, row.body, fmt.format(Date(row.createdAt))))
            }
        } catch (_: Exception) { }
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
        try { FeiqKeepAliveService.stop(context) } catch (_: Exception) { }
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
                    // 稳定 clientId → 服务端离线队列按此命中
                    WebSocketTransport(url, clientId = settings.relayClientId)
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

                // WS：断线重连后重新上线广播 + 冲刷本机发件箱
                t.onConnectionChanged = { connected ->
                    scope.launch(Dispatchers.Main) {
                        if (connected) {
                            status = if (modeIndex == 1) "已连接中继" else "UDP 已启动"
                            statusColor = ConnGreen
                        } else if (modeIndex == 1 && service != null) {
                            status = "中继断开，重连中…"
                            statusColor = ConnAmber
                        }
                    }
                    if (connected && modeIndex == 1) {
                        scope.launch(Dispatchers.IO) {
                            try { svc.announceOnline() } catch (_: Exception) { }
                            flushOutbox(svc)
                        }
                    }
                }

                svc.onPeerOnline = { p ->
                    scope.launch(Dispatchers.Main) {
                        val key = peerKey(p)
                        val i = users.indexOfFirst { peerKey(it) == key }
                        if (i >= 0) users[i] = p else users.add(p)
                        upsertSession(p)
                    }
                    // 向对方推送自己的头像（FeiQ2026）；本地缺对方头像则请求一次
                    scope.launch(Dispatchers.IO) {
                        val msg = AvatarCache.buildSyncMessage(settings.avatarPath)
                        if (msg != null) {
                            try { svc.sendText(p.ip, msg, requireAck = false) } catch (_: Exception) { }
                        }
                        val key = peerKey(p)
                        if (AvatarCache.getPath(context, key) == null) {
                            try { svc.sendText(p.ip, AvatarCache.MAGIC_REQ, requireAck = false) } catch (_: Exception) { }
                        }
                    }
                }
                svc.onPeerOffline = { p ->
                    scope.launch(Dispatchers.Main) {
                        // 只移出在线集合，会话列表保留
                        users.removeAll { peerKey(it) == peerKey(p) }
                    }
                }
                svc.onMessage = { p, text ->
                    when {
                        // 对方请求我的头像：回推一次
                        AvatarCache.isRequest(text) -> {
                            scope.launch(Dispatchers.IO) {
                                val msg = AvatarCache.buildSyncMessage(settings.avatarPath) ?: return@launch
                                try { svc.sendText(p.ip, msg, requireAck = false) } catch (_: Exception) { }
                            }
                        }
                        else -> {
                            scope.launch(Dispatchers.Main) {
                                val jpeg = AvatarCache.tryParse(text)
                                if (jpeg != null) {
                                    AvatarCache.save(context, peerKey(p), jpeg)
                                    avatarTick++ // 触发列表/气泡重绘
                                } else {
                                    appendLog(p, "in", text)
                                }
                            }
                        }
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
                // 前台保活：UDP 防息屏丢包；WS 保持进程以便收服务端离线补发
                try {
                    FeiqKeepAliveService.start(
                        context,
                        if (modeIndex == 1) "ws" else "udp"
                    )
                } catch (_: Exception) { }
                withContext(Dispatchers.Main) {
                    status = if (modeIndex == 1) "已连接中继" else "UDP 已启动"
                    statusColor = ConnGreen
                    loadSessionsFromStore()
                }
                // 首次连接也冲一次发件箱
                if (modeIndex == 1) flushOutbox(svc)
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
                    val ts0 = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date())
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
    // 联系人列表 ↔ 聊天：从右滑入 / 向右滑出
    AnimatedContent(
        targetState = chatPeer,
        transitionSpec = {
            if (targetState != null) {
                // 进入聊天：从右往左插入
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(280)
                ) + fadeIn(animationSpec = tween(200))) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth / 4 },
                        animationSpec = tween(280)
                    ) + fadeOut(animationSpec = tween(200)))
            } else {
                // 返回列表：聊天向右滑出
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 4 },
                    animationSpec = tween(280)
                ) + fadeIn(animationSpec = tween(200))) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(280)
                    ) + fadeOut(animationSpec = tween(200)))
            }.using(SizeTransform(clip = false))
        },
        label = "chatNav"
    ) { peerState ->
        if (peerState != null) {
            val peer = peerState
            ensureHistory(peer)
            unread[peerKey(peer)] = 0
            ChatScreen(
                peer = peer,
                logs = logsFor(peer),
                onBack = { chatPeer = null },
                onClearHistory = { clearChat(peer) },
                onSendText = { text ->
                    val svc = service
                    val t = transport
                    val connected = t?.isConnected == true && svc != null
                    if (svc == null) {
                        // 完全未启动：中继模式可进本机发件箱，等连上再发
                        if (modeIndex == 1) {
                            try {
                                outbox.enqueue(peerKey(peer), peer.name, peer.hostName, text)
                                appendLog(peer, "out", text)
                                appendLog(peer, "sys", "未连接中继，已加入待发队列")
                            } catch (e: Exception) {
                                appendLog(peer, "sys", "无法入队: ${e.message}")
                            }
                        } else {
                            appendLog(peer, "sys", "未连接，请检查「我的」或网络")
                        }
                    } else if (!connected && modeIndex == 1) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                outbox.enqueue(peerKey(peer), peer.name, peer.hostName, text)
                                withContext(Dispatchers.Main) {
                                    appendLog(peer, "out", text)
                                    appendLog(peer, "sys", "中继断开，已加入待发队列")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    appendLog(peer, "sys", "入队失败: ${e.message}")
                                }
                            }
                        }
                    } else {
                        scope.launch(Dispatchers.IO) {
                            try {
                                svc.sendText(peer.ip, text)
                                withContext(Dispatchers.Main) { appendLog(peer, "out", text) }
                            } catch (e: Exception) {
                                // WS 发送失败：改入本机队列
                                if (modeIndex == 1) {
                                    try {
                                        outbox.enqueue(peerKey(peer), peer.name, peer.hostName, text)
                                        withContext(Dispatchers.Main) {
                                            appendLog(peer, "out", text)
                                            appendLog(peer, "sys", "发送失败已入队，连上后自动补发")
                                        }
                                    } catch (e2: Exception) {
                                        withContext(Dispatchers.Main) {
                                            appendLog(peer, "sys", "发送失败: ${e.message}")
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        appendLog(peer, "sys", "发送失败: ${e.message}")
                                    }
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
                                // 本地无对方头像时请求一次（低频）
                                val key = peerKey(live)
                                if (AvatarCache.getPath(context, key) == null) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            service?.sendText(live.ip, AvatarCache.MAGIC_REQ, requireAck = false)
                                        } catch (_: Exception) { }
                                    }
                                }
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
