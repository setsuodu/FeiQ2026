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
