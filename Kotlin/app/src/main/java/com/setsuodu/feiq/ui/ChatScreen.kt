package com.setsuodu.feiq.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.setsuodu.feiq.data.AvatarCache
import com.setsuodu.feiq.data.ChatStore
import com.setsuodu.feiq.service.Peer
import java.io.File

@Composable
internal fun ChatScreen(
    peer: Peer,
    logs: List<ChatUiMsg>,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
    onSendText: (String) -> Unit,
    onSendFile: (Uri) -> Unit,
    /** 长按语音录制完成后的本地文件（.m4a），由上层走与发文件相同通道 */
    onSendVoiceFile: (File) -> Unit = { f -> onSendFile(Uri.fromFile(f)) },
    selfAvatarPath: String? = null
) {
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var emojiOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    /** true = 按住说话模式 */
    var voiceMode by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val voiceRecorder = remember { VoiceRecorder(context) }

    val requestMicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceMode = true
            emojiOpen = false
            attachOpen = false
        } else {
            Toast.makeText(context, "需要麦克风权限才能发语音", Toast.LENGTH_SHORT).show()
        }
    }

    fun ensureMicThenVoiceMode() {
        val ok = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (ok) {
            voiceMode = true
            emojiOpen = false
            attachOpen = false
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 录音计时
    LaunchedEffect(recording) {
        if (!recording) {
            recordSeconds = 0
            return@LaunchedEffect
        }
        val start = SystemClock.elapsedRealtime()
        while (recording) {
            recordSeconds = ((SystemClock.elapsedRealtime() - start) / 1000L).toInt()
            // 最长 60s 自动结束
            if (recordSeconds >= 60) {
                val file = voiceRecorder.stop(minMs = 500L)
                recording = false
                if (file != null) onSendVoiceFile(file)
                else Toast.makeText(context, "录音太短", Toast.LENGTH_SHORT).show()
                break
            }
            kotlinx.coroutines.delay(200)
        }
    }

    DisposableEffect(Unit) {
        onDispose { voiceRecorder.cancel() }
    }

    // 多选：相册（图片/视频）与任意文件，对齐 WPF Multiselect
    val pickAlbumLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { onSendFile(it) }
    }
    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.forEach { onSendFile(it) }
    }

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
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.Black
                )
            }
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${peer.hostName} · ${peer.ip.hostAddress}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = "更多",
                        tint = Color.Black
                    )
                }
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

        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        keyboard?.hide()
                        focusManager.clearFocus()
                        emojiOpen = false
                        attachOpen = false
                    }
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

            // 录音中遮罩
            if (recording) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color(0xEE333333), MaterialTheme.shapes.medium)
                            .padding(horizontal = 28.dp, vertical = 20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在录音 ${recordSeconds}s",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "松开发送 · 最长 60 秒",
                            color = Color(0xFFCCCCCC),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // 表情面板
        if (emojiOpen) {
            EmojiPickerPanel(
                onPick = { emoji ->
                    input += emoji
                },
                onClose = { emojiOpen = false }
            )
        }

        // 附件面板（+ 拉起，与表情类似）
        if (attachOpen) {
            AttachmentPanel(
                onAlbum = {
                    attachOpen = false
                    pickAlbumLauncher.launch("image/*")
                },
                onFile = {
                    attachOpen = false
                    pickFileLauncher.launch("*/*")
                },
                onClose = { attachOpen = false }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 语音 / 键盘 切换
            IconButton(
                onClick = {
                    if (voiceMode) {
                        voiceMode = false
                    } else {
                        keyboard?.hide()
                        focusManager.clearFocus()
                        ensureMicThenVoiceMode()
                    }
                }
            ) {
                Icon(
                    imageVector = if (voiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
                    contentDescription = if (voiceMode) "键盘" else "语音",
                    tint = Color(0xFF576B95)
                )
            }

            if (voiceMode) {
                // 按住说话
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (recording) Color(0xFFD0D0D0) else Color(0xFFF5F5F5),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    val ok = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!ok) {
                                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                                        return@detectTapGestures
                                    }
                                    val started = voiceRecorder.start()
                                    if (started == null) {
                                        Toast.makeText(context, "无法开始录音", Toast.LENGTH_SHORT).show()
                                        return@detectTapGestures
                                    }
                                    recording = true
                                    val released = tryAwaitRelease()
                                    val file = if (released) {
                                        voiceRecorder.stop(minMs = 500L)
                                    } else {
                                        voiceRecorder.cancel()
                                        null
                                    }
                                    recording = false
                                    if (file != null) {
                                        onSendVoiceFile(file)
                                    } else if (released) {
                                        Toast.makeText(context, "录音太短", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            if (recording) "松开 结束" else "按住 说话",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF333333)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = {
                        emojiOpen = !emojiOpen
                        if (emojiOpen) {
                            attachOpen = false
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (emojiOpen) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                        contentDescription = if (emojiOpen) "键盘" else "表情",
                        tint = Color(0xFF576B95)
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 140.dp)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                emojiOpen = false
                                attachOpen = false
                            }
                        },
                    singleLine = false,
                    maxLines = 5,
                    minLines = 1,
                    placeholder = { Text("输入消息...") }
                )
            }

            Spacer(Modifier.width(4.dp))
            val hasText = !voiceMode && input.trim().isNotEmpty()
            if (hasText) {
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty()) return@Button
                        onSendText(text)
                        input = ""
                        emojiOpen = false
                        attachOpen = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WeChatGreen)
                ) { Text("发送") }
            } else if (!voiceMode) {
                IconButton(
                    onClick = {
                        attachOpen = !attachOpen
                        if (attachOpen) {
                            emojiOpen = false
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (attachOpen) Icons.Default.Keyboard else Icons.Default.Add,
                        contentDescription = if (attachOpen) "键盘" else "附件",
                        tint = Color(0xFF576B95)
                    )
                }
            }
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

/** 附件选择面板：相册（图片多选）/ 文件（任意文件多选），对齐 WPF 端多选 */
@Composable
private fun AttachmentPanel(
    onAlbum: () -> Unit,
    onFile: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F7F7))
            .padding(vertical = 16.dp, horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AttachAction(
                icon = Icons.Default.PhotoLibrary,
                label = "相册",
                onClick = onAlbum
            )
            AttachAction(
                icon = Icons.Default.Folder,
                label = "文件",
                onClick = onFile
            )
        }
    }
}

@Composable
private fun AttachAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color.White,
            shadowElevation = 1.dp,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color(0xFF576B95),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.DarkGray)
    }
}
