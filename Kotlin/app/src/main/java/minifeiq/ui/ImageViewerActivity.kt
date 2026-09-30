package minifeiq.ui

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 应用内全屏看图，不走系统图库。
 * Intent extra: "path" = 本地文件绝对路径
 */
class ImageViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH)
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
                        "图片不存在或已失效",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val bmp = remember(path) {
                        try {
                            BitmapFactory.decodeFile(path)?.asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (bmp == null) {
                        Text("无法解码图片", color = Color.White, modifier = Modifier.align(Alignment.Center))
                    } else {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offsetX by remember { mutableFloatStateOf(0f) }
                        var offsetY by remember { mutableFloatStateOf(0f) }
                        Image(
                            bitmap = bmp,
                            contentDescription = "图片",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                }
                        )
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
    }
}
