package com.setsuodu.feiq.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.setsuodu.feiq.data.AvatarCache
import com.setsuodu.feiq.data.ChatStore
import com.setsuodu.feiq.service.Peer

@Composable
internal fun ChatScreen(
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
    var emojiOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextButton(
                onClick = {
                    emojiOpen = !emojiOpen
                    if (emojiOpen) {
                        attachOpen = false
                        keyboard?.hide()
                        focusManager.clearFocus()
                    }
                }
            ) { Text(if (emojiOpen) "⌨️" else "😀", style = MaterialTheme.typography.titleMedium) }
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
            Spacer(Modifier.width(6.dp))
            val hasText = input.trim().isNotEmpty()
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
            } else {
                // 与表情按钮风格一致：+ 拉起附件菜单（相册 / 文件，可多选）
                TextButton(
                    onClick = {
                        attachOpen = !attachOpen
                        if (attachOpen) {
                            emojiOpen = false
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    }
                ) { Text(if (attachOpen) "⌨️" else "＋", style = MaterialTheme.typography.titleMedium) }
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
            AttachAction(icon = "🖼", label = "相册", onClick = onAlbum)
            AttachAction(icon = "📁", label = "文件", onClick = onFile)
        }
    }
}

@Composable
private fun AttachAction(icon: String, label: String, onClick: () -> Unit) {
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
                Text(icon, style = MaterialTheme.typography.headlineSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.DarkGray)
    }
}
