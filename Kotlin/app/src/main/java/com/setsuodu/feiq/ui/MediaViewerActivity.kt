package com.setsuodu.feiq.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

/**
 * 独立全屏媒体查看：图片（缩放）或视频（ExoPlayer）。
 * 图片长按 → 底部滑出面板：保存 / 分享（分享暂未实现）。
 */
class MediaViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH)
        val kindHint = intent.getStringExtra(EXTRA_KIND)
        setContent {
            BackHandler { finish() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                if (path.isNullOrEmpty() || !File(path).exists()) {
                    Text(
                        "文件不存在或已失效",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val isVideo = when (kindHint?.lowercase()) {
                        "video" -> true
                        "image" -> false
                        else -> {
                            val ext = path.substringAfterLast('.', "").lowercase()
                            ext in setOf(
                                "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v", "ts", "mpeg", "mpg"
                            )
                        }
                    }
                    if (isVideo) {
                        VideoPlayerScreen(path)
                    } else {
                        ImageViewerScreen(path)
                    }
                }
                IconButton(
                    onClick = { finish() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text("←", color = Color.White)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_KIND = "kind"
    }
}

@Composable
private fun ImageViewerScreen(path: String) {
    val context = LocalContext.current
    val bmp = remember(path) {
        try {
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无法解码图片", color = Color.White)
        }
        return
    }

    val imgW = bmp.width.toFloat().coerceAtLeast(1f)
    val imgH = bmp.height.toFloat().coerceAtLeast(1f)
    val density = LocalDensity.current

    var container by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var minScale by remember { mutableFloatStateOf(1f) }
    var maxScale by remember { mutableFloatStateOf(5f) }
    var ready by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(false) }

    fun clampOffset(s: Float, o: Offset, cw: Float, ch: Float): Offset {
        val dispW = imgW * s
        val dispH = imgH * s
        val maxX = max(0f, (dispW - cw) / 2f)
        val maxY = max(0f, (dispH - ch) / 2f)
        return Offset(
            o.x.coerceIn(-maxX, maxX),
            o.y.coerceIn(-maxY, maxY)
        )
    }

    Box(Modifier.fillMaxSize()) {
        // 图片层
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { size ->
                    container = size
                    if (size.width > 0 && size.height > 0) {
                        val cw = size.width.toFloat()
                        val ch = size.height.toFloat()
                        val ms = cw / imgW
                        minScale = ms
                        maxScale = ms * 5f
                        if (!ready) {
                            scale = ms
                            offset = Offset.Zero
                            ready = true
                        } else {
                            scale = scale.coerceIn(minScale, maxScale)
                            offset = clampOffset(scale, offset, cw, ch)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (showPanel) showPanel = false },
                        onLongPress = { showPanel = true }
                    )
                }
                .pointerInput(minScale, maxScale, container) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (showPanel) return@detectTransformGestures
                        val cw = container.width.toFloat()
                        val ch = container.height.toFloat()
                        if (cw <= 0f || ch <= 0f) return@detectTransformGestures

                        val oldScale = scale
                        val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)
                        val px = centroid.x - cw / 2f
                        val py = centroid.y - ch / 2f
                        val ratio = if (oldScale > 0f) newScale / oldScale else 1f
                        var newOffset = Offset(
                            offset.x * ratio + px * (1f - ratio) + pan.x,
                            offset.y * ratio + py * (1f - ratio) + pan.y
                        )
                        newOffset = clampOffset(newScale, newOffset, cw, ch)
                        scale = newScale
                        offset = newOffset
                    }
                }
        ) {
            if (ready) {
                val wDp = with(density) { imgW.toDp() }
                val hDp = with(density) { imgH.toDp() }
                Image(
                    bitmap = bmp,
                    contentDescription = "图片",
                    contentScale = ContentScale.None,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = wDp, height = hDp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                            transformOrigin = TransformOrigin.Center
                        }
                )
            }
        }

        // 半透明遮罩（点空白收起）
        if (showPanel) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showPanel = false }
            )
        }

        // 底部滑出面板 ≈ 屏幕 1/4
        AnimatedVisibility(
            visible = showPanel,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(280)
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(220)
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.28f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Color(0xFF2C2C2E))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { /* 吞掉点击，避免点到遮罩 */ }
                    .navigationBarsPadding()
            ) {
                // 顶条把手
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF636366))
                    )
                }

                Text(
                    "操作",
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                // 保存
                ActionRow(
                    icon = Icons.Default.Download,
                    label = "保存到相册",
                    onClick = {
                        showPanel = false
                        val ok = saveImageToGallery(context, File(path))
                        Toast.makeText(
                            context,
                            if (ok) "已保存到相册" else "保存失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                HorizontalDivider(
                    color = Color(0xFF3A3A3C),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // 分享（暂未实现）
                ActionRow(
                    icon = Icons.Default.Share,
                    label = "分享",
                    enabled = false,
                    onClick = {
                        // TODO: 分享
                        Toast.makeText(context, "分享功能暂未实现", Toast.LENGTH_SHORT).show()
                    }
                )

                HorizontalDivider(
                    color = Color(0xFF3A3A3C),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // 取消
                ActionRow(
                    icon = Icons.Default.Close,
                    label = "取消",
                    onClick = { showPanel = false }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = if (enabled) Color.White else Color(0xFF636366)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun VideoPlayerScreen(path: String) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/** 写入系统相册 Pictures/FeiQ2026/ */
fun saveImageToGallery(context: Context, src: File): Boolean {
    return try {
        val name = "FeiQ_${System.currentTimeMillis()}_${src.name}"
        val mime = when (src.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FeiQ2026")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(src).use { it.copyTo(out) }
        } ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
