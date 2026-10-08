package com.notificationhistory.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.notificationhistory.data.FilterCounts
import com.notificationhistory.data.HistoryFilter
import com.notificationhistory.data.RemovalReason
import com.notificationhistory.data.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- Иконки приложений ----------

private val iconCache = LruCache<String, Bitmap>(64)

@Composable
fun AppIcon(packageName: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(iconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName).toBitmap(px, px)
                }.getOrNull()?.also { iconCache.put(packageName, it) }
            }
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier.size(size))
    } else {
        Surface(
            modifier = modifier.size(size).clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Icon(Icons.Outlined.Apps, null, Modifier.padding(size / 5))
        }
    }
}

// ---------- Метка статуса ----------

@Composable
fun statusColors(status: Int, reason: Int?): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    if (status == Status.ACTIVE) {
        val green = if (dark) Color(0xFF81C784) else Color(0xFF1B5E20)
        return green.copy(alpha = 0.16f) to green
    }
    return when (RemovalReason.kind(reason)) {
        RemovalReason.Kind.USER_DISMISSED -> cs.errorContainer to cs.onErrorContainer
        RemovalReason.Kind.APP_REMOVED -> {
            val orange = if (dark) Color(0xFFFFB74D) else Color(0xFF9A4A00)
            orange.copy(alpha = 0.18f) to orange
        }
        RemovalReason.Kind.OPENED -> cs.primaryContainer to cs.onPrimaryContainer
        RemovalReason.Kind.REPLACED, RemovalReason.Kind.SYSTEM -> cs.surfaceVariant to cs.onSurfaceVariant
    }
}

fun statusLabel(status: Int, reason: Int?): String =
    if (status == Status.ACTIVE) "В шторке" else RemovalReason.shortLabel(reason)

@Composable
fun StatusChip(status: Int, reason: Int?, modifier: Modifier = Modifier) {
    val (bgTarget, fgTarget) = statusColors(status, reason)
    // Уведомление смахнули, пока экран открыт, — метка плавно перекрашивается.
    val bg by animateColorAsState(bgTarget, tween(Motion.MEDIUM), label = "chip-bg")
    val fg by animateColorAsState(fgTarget, tween(Motion.MEDIUM), label = "chip-fg")
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, modifier = modifier.animateContentSize()) {
        Text(
            statusLabel(status, reason),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---------- Вкладки фильтра ----------

@Composable
fun FilterTabs(selected: HistoryFilter, counts: FilterCounts?, onSelect: (HistoryFilter) -> Unit) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        HistoryFilter.entries.forEach { f ->
            Tab(
                selected = f == selected,
                onClick = { onSelect(f) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (counts != null) "${f.title} · " else f.title, maxLines = 1)
                        if (counts != null) AnimatedCount(counts[f])
                    }
                },
            )
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionHeader(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------- Форматирование времени ----------

private val ru = Locale.forLanguageTag("ru")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", ru)
private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", ru)
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
private val fullFmt = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm:ss", ru)

private fun Long.toLocal() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

fun formatTime(ms: Long): String = ms.toLocal().format(timeFmt)

fun formatFull(ms: Long): String = ms.toLocal().format(fullFmt)

fun formatDay(ms: Long): String {
    val date = ms.toLocal().toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "Сегодня"
        date == today.minusDays(1) -> "Вчера"
        date.year == today.year -> date.format(dayFmt)
        else -> date.format(dayYearFmt)
    }
}

/** Короткая метка для списка: время, если сегодня, иначе дата. */
fun formatShort(ms: Long): String {
    val date = ms.toLocal().toLocalDate()
    return if (date == LocalDate.now()) formatTime(ms) else formatDay(ms)
}
