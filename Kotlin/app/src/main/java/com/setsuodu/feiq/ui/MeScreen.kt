package com.setsuodu.feiq.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.setsuodu.feiq.data.AppSettings
import java.io.File
import java.io.FileOutputStream

@Composable
internal fun MeScreen(
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

    val appVersion = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val dest = File(context.filesDir, "avatar.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 1:1 中心裁剪后落盘
                if (!com.setsuodu.feiq.data.AvatarCache.saveSquareFromStream(input, dest)) {
                    // 兜底：原样复制
                    context.contentResolver.openInputStream(uri)?.use { input2 ->
                        FileOutputStream(dest).use { output -> input2.copyTo(output) }
                    }
                }
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

        Spacer(Modifier.weight(1f))
        Text(
            "版本 $appVersion",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
