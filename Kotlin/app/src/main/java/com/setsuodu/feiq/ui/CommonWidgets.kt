package com.setsuodu.feiq.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
internal fun AvatarCircle(letter: String, bg: Color, imagePath: String? = null) {
    val bmp = remember(imagePath) {
        if (imagePath.isNullOrEmpty()) null
        else try {
            BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
        } catch (_: Exception) { null }
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = "头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(letter.take(1), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
