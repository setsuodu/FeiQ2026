package com.setsuodu.feiq.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
internal fun ChatBubbleRow(
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
internal fun BubbleContent(msg: ChatUiMsg, context: Context) {
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
        // 图片 / 视频：独立 MediaViewer（ExoPlayer）
        if (msg.kind == MsgKind.Image || msg.kind == MsgKind.Video) {
            try {
                context.startActivity(
                    android.content.Intent(context, MediaViewerActivity::class.java).apply {
                        putExtra(MediaViewerActivity.EXTRA_PATH, path)
                        putExtra(MediaViewerActivity.EXTRA_KIND, if (msg.kind == MsgKind.Video) "video" else "image")
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
            msg.kind == MsgKind.Video && !msg.fileMissing && !msg.filePath.isNullOrEmpty() -> {
                FileCard(msg, onClick = openFile)
            }
            msg.kind == MsgKind.Audio && !msg.fileMissing && !msg.filePath.isNullOrEmpty() && File(msg.filePath!!).exists() -> {
                InlineAudioPlayer(path = msg.filePath!!, label = msg.body)
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
internal fun InlineAudioPlayer(path: String, label: String) {
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableIntStateOf(0) }
    val player = remember(path) {
        try { MediaPlayer().apply { setDataSource(path); prepare() } } catch (_: Exception) { null }
    }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        try {
            durationMs = p.duration.coerceAtLeast(0)
            p.setOnCompletionListener {
                playing = false; positionMs = 0
                try { p.seekTo(0) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }
    DisposableEffect(path) {
        onDispose { try { player?.stop() } catch (_: Exception) {}; player?.release() }
    }
    LaunchedEffect(playing) {
        while (playing) {
            try { positionMs = player?.currentPosition ?: 0 } catch (_: Exception) { }
            kotlinx.coroutines.delay(200)
        }
    }
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    fun fmt(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
    Row(
        modifier = Modifier
            .background(Color(0xFFF0F4FF), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(min = 160.dp, max = 240.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            val p = player ?: return@IconButton
            try {
                if (playing) { p.pause(); playing = false }
                else { p.start(); playing = true }
            } catch (_: Exception) { playing = false }
        }, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = Color(0xFF12B7F5)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label.removePrefix("🎵 ").trim().ifEmpty { File(path).name },
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp))
            Text("${fmt(positionMs)} / ${fmt(durationMs)}",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
internal fun FileCard(msg: ChatUiMsg, onClick: () -> Unit) {
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
