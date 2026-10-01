package com.setsuodu.feiq.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.max

/**
 * 独立全屏媒体查看：图片（缩放，水平撑满、松手贴边）或视频（ExoPlayer）。
 * Intent extras:
 *   "path" = 本地文件绝对路径
 *   "kind" = "image" | "video"（可缺省，按扩展名推断）
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

/**
 * 相册式手感：
 * - 最小缩放 = 水平撑满容器宽度（不露左右黑边）
 * - 拖动/缩放过程中实时钳制 offset
 * - 松手后水平（及垂直，若图片已盖住该轴）贴边，禁止悬停露黑边
 */
@Composable
private fun ImageViewerScreen(path: String) {
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
            .pointerInput(minScale, maxScale, container) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val cw = container.width.toFloat()
                    val ch = container.height.toFloat()
                    if (cw <= 0f || ch <= 0f) return@detectTransformGestures

                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)

                    // 以手势中心为缩放锚点
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
        if (!ready) return@Box
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
