package com.notificationhistory.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notificationhistory.data.MediaKind
import com.notificationhistory.data.MessageRecord
import com.notificationhistory.data.MsgStatus

private enum class MsgFilter(val title: String) { ALL("Все"), DELETED("Удалённые"), EDITED("Изменённые") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(vm: MainViewModel, screen: Screen.Chat) {
    val all by remember(screen) { vm.messages(screen.packageName, screen.convKey) }
        .collectAsStateWithLifecycle(null)
    var filter by remember { mutableStateOf(MsgFilter.ALL) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text(screen.title, maxLines = 1) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val messages = all ?: return@Column
            val deleted = messages.count { it.status == MsgStatus.DELETED }
            val edited = messages.count { it.status == MsgStatus.EDITED }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MsgFilter.entries.forEach { f ->
                    val n = when (f) { MsgFilter.ALL -> messages.size; MsgFilter.DELETED -> deleted; MsgFilter.EDITED -> edited }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.title} · $n") })
                }
            }
            val shown = when (filter) {
                MsgFilter.ALL -> messages
                MsgFilter.DELETED -> messages.filter { it.status == MsgStatus.DELETED }
                MsgFilter.EDITED -> messages.filter { it.status == MsgStatus.EDITED }
            }
            if (shown.isEmpty()) {
                EmptyState("Здесь ничего нет.")
                return@Column
            }
            val state = rememberLazyListState()
            // Как в мессенджере: открываемся на последних сообщениях.
            // Индекс с запасом на заголовки дней; LazyColumn сам ограничит его последним элементом.
            // Второй проход — после того как картинки загрузились и поменяли высоту строк.
            LaunchedEffect(filter, shown.size) {
                state.scrollToItem(shown.size * 2)
                kotlinx.coroutines.delay(400)
                state.scrollToItem(shown.size * 2)
            }
            val isGroup = messages.map { it.sender }.filter { it != null && it != "Вы" }.distinct().size > 1
            LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                shown.groupBy { formatDay(it.time) }.forEach { (day, items) ->
                    stickyHeader(key = "h_$day") { SectionHeader(day) }
                    itemsIndexed(items, key = { _, m -> m.id }) { index, m ->
                        Box(Modifier.animateItem().appearIn(index)) { MessageBubble(m, showSender = isGroup, vm = vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: MessageRecord, showSender: Boolean, vm: MainViewModel) {
    val mine = m.sender == "Вы"
    val cs = MaterialTheme.colorScheme
    val isDeleted = m.status == MsgStatus.DELETED
    val bg by animateColorAsState(
        when {
            isDeleted -> cs.errorContainer.copy(alpha = 0.45f)
            mine -> cs.primaryContainer
            else -> cs.surfaceContainerHigh
        },
        tween(Motion.MEDIUM),
        label = "bubble",
    )
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp),
            border = if (isDeleted) BorderStroke(1.dp, cs.error) else null,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                .animateContentSize(Motion.gentleSpring()),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Сообщение удалили, пока переписка открыта, — пометка «выпрыгивает».
                AnimatedVisibility(visible = isDeleted, enter = popIn + expandVertically(), exit = popOut) {
                    Text(
                        "🗑 Удалено" + (m.changedTime?.let { " в ${formatTime(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.error,
                    )
                }
                if (showSender && !mine && m.sender != null) {
                    Text(m.sender, style = MaterialTheme.typography.labelLarge, color = cs.primary)
                }
                MessageMedia(m, vm)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        m.history.forEach { old ->
                            Text(
                                old,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                                textDecoration = TextDecoration.LineThrough,
                            )
                        }
                        m.text?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (m.status == MsgStatus.EDITED) {
                        Text(
                            "✏️ изменено" + (m.changedTime?.let { " в ${formatTime(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = cs.tertiary,
                        )
                    }
                    Text(formatTime(m.time), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Вложение сообщения: файл из уведомления или копия из папки мессенджера. */
@Composable
private fun MessageMedia(m: MessageRecord, vm: MainViewModel) {
    val path = m.mediaPath ?: m.linkedMedia?.localPath
    val kind = m.linkedMedia?.kind ?: mediaKindOfMime(m.mediaMime)
    if (path == null) {
        if (m.mediaMime != null) {
            Text(
                "📎 ${MediaKind.label(kind)} — мессенджер не дал доступ к файлу",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val deleted = m.status == MsgStatus.DELETED || m.linkedMedia?.sourceDeletedTime != null
    val sticker = kind == MediaKind.STICKER
    MediaThumb(
        path, kind,
        (if (sticker) Modifier.size(140.dp) else Modifier.width(240.dp).heightIn(min = 80.dp, max = 360.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { vm.navigate(Screen.Viewer(path, kind, "${MediaKind.label(kind)} · ${formatFull(m.time)}", deleted)) },
        contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
        fitAspect = !sticker,
    )
}
