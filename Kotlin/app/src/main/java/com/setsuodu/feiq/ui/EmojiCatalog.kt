package com.setsuodu.feiq.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

data class EmojiItem(
    val char: String,
    val code: String,
    val image: String,
    val shortcode: String
)

data class EmojiCategory(
    val categoryName: String,
    val categoryTitle: String,
    val emojis: List<EmojiItem>
)

object EmojiCatalog {
    @Volatile private var loaded = false
    private val categories = mutableListOf<EmojiCategory>()
    private val byChar = ConcurrentHashMap<String, EmojiItem>()
    private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                context.assets.open("emoji/emoji_map.json").use { input ->
                    val json = input.bufferedReader().readText()
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val catObj = arr.getJSONObject(i)
                        val list = mutableListOf<EmojiItem>()
                        val emojis = catObj.getJSONArray("emojis")
                        for (j in 0 until emojis.length()) {
                            val e = emojis.getJSONObject(j)
                            val item = EmojiItem(
                                char = e.optString("char"),
                                code = e.optString("code"),
                                image = e.optString("image"),
                                shortcode = e.optString("shortcode")
                            )
                            list.add(item)
                            if (item.char.isNotEmpty()) {
                                byChar[item.char] = item
                                val stripped = item.char.replace("\uFE0F", "")
                                if (stripped != item.char) byChar.putIfAbsent(stripped, item)
                            }
                        }
                        categories.add(
                            EmojiCategory(
                                categoryName = catObj.optString("category_name"),
                                categoryTitle = catObj.optString("category_title"),
                                emojis = list
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                if (categories.isEmpty()) {
                    val face = listOf(
                        EmojiItem("😀", "1f600", "1f600.png", "[:smile:]"),
                        EmojiItem("😂", "1f602", "1f602.png", "[:joy:]"),
                        EmojiItem("👍", "1f44d", "1f44d.png", "[:like:]"),
                        EmojiItem("❤️", "2764", "2764.png", "[:heart:]"),
                        EmojiItem("🔥", "1f525", "1f525.png", "[:fire:]")
                    )
                    categories.add(EmojiCategory("face", "表情", face))
                    face.forEach { byChar[it.char] = it }
                }
            }
            loaded = true
        }
    }

    fun categories(context: Context): List<EmojiCategory> {
        ensureLoaded(context)
        return categories.toList()
    }

    fun getBitmap(context: Context, item: EmojiItem): ImageBitmap? {
        val key = item.image.ifEmpty { item.code }
        bitmapCache[key]?.let { return it }
        val tries = listOfNotNull(
            item.image.takeIf { it.isNotEmpty() },
            "${item.code}.png".takeIf { item.code.isNotEmpty() },
            "${item.code}-fe0f.png".takeIf { item.code.isNotEmpty() }
        )
        for (name in tries) {
            try {
                context.assets.open("emoji/$name").use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream) ?: return@use
                    val ib = bmp.asImageBitmap()
                    bitmapCache[key] = ib
                    return ib
                }
            } catch (_: Exception) { }
        }
        return null
    }

    fun getBitmapByChar(context: Context, ch: String): ImageBitmap? {
        ensureLoaded(context)
        val item = byChar[ch] ?: byChar[ch.replace("\uFE0F", "")] ?: return null
        return getBitmap(context, item)
    }

    data class Segment(val isEmoji: Boolean, val text: String, val bitmap: ImageBitmap?)

    fun parseSegments(context: Context, text: String): List<Segment> {
        ensureLoaded(context)
        val result = mutableListOf<Segment>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            var elemEnd = i + charCount
            if (elemEnd < text.length && text[elemEnd] == '\uFE0F') elemEnd++
            val elem = text.substring(i, elemEnd)
            val img = getBitmapByChar(context, elem)
            if (img != null) {
                if (sb.isNotEmpty()) {
                    result.add(Segment(false, sb.toString(), null))
                    sb.clear()
                }
                result.add(Segment(true, elem, img))
            } else {
                sb.append(elem)
            }
            i = elemEnd
        }
        if (sb.isNotEmpty()) result.add(Segment(false, sb.toString(), null))
        return result
    }
}

/** 气泡内混排：有素材图则用 InlineTextContent 嵌入图片，否则普通 Text */
@Composable
fun EmojiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    val context = LocalContext.current
    val segments = remember(text) { EmojiCatalog.parseSegments(context, text) }
    val hasEmoji = segments.any { it.isEmoji && it.bitmap != null }

    if (!hasEmoji) {
        Text(text, modifier = modifier, style = style, color = color)
        return
    }

    val inlineMap = remember(segments) {
        val map = mutableMapOf<String, InlineTextContent>()
        segments.forEachIndexed { idx, seg ->
            if (seg.isEmoji && seg.bitmap != null) {
                val id = "e$idx"
                map[id] = InlineTextContent(
                    Placeholder(
                        width = 20.sp,
                        height = 20.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    Image(
                        bitmap = seg.bitmap,
                        contentDescription = seg.text,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        map
    }

    val annotated = remember(segments) {
        buildAnnotatedString {
            segments.forEachIndexed { idx, seg ->
                if (seg.isEmoji && seg.bitmap != null) {
                    appendInlineContent("e$idx", seg.text)
                } else {
                    append(seg.text)
                }
            }
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        inlineContent = inlineMap
    )
}

@Composable
fun EmojiPickerPanel(
    onPick: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val cats = remember { EmojiCatalog.categories(context) }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(Color.White)
    ) {
        HorizontalDivider()
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            cats.forEach { cat ->
                item {
                    Text(
                        cat.categoryTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                item {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Start
                    ) {
                        cat.emojis.forEach { item ->
                            val bmp = remember(item.code) { EmojiCatalog.getBitmap(context, item) }
                            TextButton(
                                onClick = { onPick(item.char) },
                                contentPadding = PaddingValues(4.dp),
                                modifier = Modifier.size(40.dp)
                            ) {
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = item.shortcode,
                                        modifier = Modifier.size(28.dp)
                                    )
                                } else {
                                    Text(item.char, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
