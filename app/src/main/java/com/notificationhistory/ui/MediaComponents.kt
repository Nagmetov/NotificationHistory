package com.notificationhistory.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.notificationhistory.data.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val thumbCache = LruCache<String, Bitmap>(48)

/** Уменьшенная копия картинки/кадр видео; декодируется в фоне и кэшируется. */
@Composable
fun rememberThumbnail(path: String?, kind: Int, maxPx: Int = 512): Bitmap? {
    val key = "$path@$maxPx"
    val bitmap by produceState(thumbCache.get(key), key) {
        if (value == null && path != null) {
            value = withContext(Dispatchers.IO) { decodeThumb(path, kind, maxPx) }?.also { thumbCache.put(key, it) }
        }
    }
    return bitmap
}

private fun decodeThumb(path: String, kind: Int, maxPx: Int): Bitmap? = runCatching {
    val file = File(path)
    if (!file.exists()) return null
    when (kind) {
        MediaKind.IMAGE, MediaKind.STICKER -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        MediaKind.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, Size(maxPx, maxPx), null)
        } else null
        else -> null
    }
}.getOrNull()

/** Превью медиафайла: картинка, кадр видео или значок типа файла. */
@Composable
fun MediaThumb(
    path: String?,
    kind: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxPx: Int = 512,
    /** Подогнать высоту под пропорции картинки (для ленты сообщений). */
    fitAspect: Boolean = false,
) {
    val bmp = rememberThumbnail(path, kind, maxPx)
    val sized = if (fitAspect && bmp != null) modifier.aspectRatio(bmp.width.toFloat() / bmp.height) else modifier
    // У стикеров прозрачный фон — подложка под ними смотрится как серая плашка.
    val bg = if (kind == MediaKind.STICKER && bmp != null) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    Box(sized.then(bg), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = MediaKind.label(kind), contentScale = contentScale, modifier = Modifier.fillMaxSize())
            if (kind == MediaKind.VIDEO) {
                Icon(Icons.Outlined.PlayCircle, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(8.dp))
            }
        } else {
            Icon(
                when (kind) {
                    MediaKind.AUDIO -> Icons.Outlined.Audiotrack
                    MediaKind.VIDEO -> Icons.Outlined.PlayCircle
                    MediaKind.DOCUMENT -> Icons.Outlined.Description
                    else -> Icons.Outlined.Image
                },
                contentDescription = MediaKind.label(kind),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun mediaKindOfMime(mime: String?): Int = when {
    mime == null -> MediaKind.DOCUMENT
    mime == "image/webp" -> MediaKind.STICKER
    mime.startsWith("image/") -> MediaKind.IMAGE
    mime.startsWith("video/") -> MediaKind.VIDEO
    mime.startsWith("audio/") -> MediaKind.AUDIO
    else -> MediaKind.DOCUMENT
}

/** Открывает нашу копию файла в подходящем приложении (галерея, плеер…). */
fun openFile(context: Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_SHORT).show()
    }
}

fun shareFile(context: Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Поделиться"))
}
