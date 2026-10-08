package com.notificationhistory.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notificationhistory.data.ConversationSummary
import com.notificationhistory.data.HistoryFilter
import com.notificationhistory.data.MediaKind
import com.notificationhistory.data.MediaRecord
import com.notificationhistory.data.NotificationRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHistoryScreen(vm: MainViewModel, screen: Screen.App) {
    val content by remember(screen.packageName) { vm.appContent(screen.packageName) }
        .collectAsStateWithLifecycle(AppContent())
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

    val sections = buildList {
        add(AppSection.NOTIFICATIONS)
        if (content.hasChats) add(AppSection.CHATS)
        if (content.hasMedia) add(AppSection.MEDIA)
    }
    // Для мессенджеров по умолчанию открываем переписки — там самое интересное.
    val section = vm.appSection[screen.packageName]?.takeIf { it in sections }
        ?: if (content.hasChats) AppSection.CHATS else AppSection.NOTIFICATIONS

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(screen.packageName, size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(screen.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Очистить историю приложения") },
                            onClick = { menu = false; confirm = ConfirmAction.CLEAR },
                        )
                        DropdownMenuItem(
                            text = { Text("Не сохранять это приложение") },
                            onClick = { menu = false; confirm = ConfirmAction.EXCLUDE },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (sections.size > 1) {
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    sections.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = s == section,
                            onClick = { vm.appSection[screen.packageName] = s },
                            shape = SegmentedButtonDefaults.itemShape(i, sections.size),
                            label = { Text(s.title, maxLines = 1) },
                        )
                    }
                }
            }
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    // Раздел правее — въезжает справа, левее — слева, как страницы.
                    val forward = targetState.ordinal > initialState.ordinal
                    with(Motion) { screenTransition(forward) }
                },
                label = "section",
            ) { shown ->
                Column(Modifier.fillMaxSize()) {
                    when (shown) {
                        AppSection.NOTIFICATIONS -> NotificationsSection(vm, screen)
                        AppSection.CHATS -> ChatsSection(vm, screen)
                        AppSection.MEDIA -> MediaSection(vm, screen)
                    }
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(action.title) },
            text = { Text(action.message(screen.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.deletePackage(screen.packageName)
                    if (action == ConfirmAction.EXCLUDE) vm.setExcluded(listOf(screen.packageName), exclude = true)
                    vm.back()
                }) { Text(action.button) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

private enum class ConfirmAction(val title: String, val button: String) {
    CLEAR("Очистить историю?", "Очистить"),
    EXCLUDE("Не сохранять приложение?", "Исключить");

    fun message(label: String) = when (this) {
        CLEAR -> "Все сохранённые уведомления, сообщения и файлы «$label» будут удалены."
        EXCLUDE -> "История «$label» будет удалена, новые уведомления сохраняться не будут. Вернуть можно в настройках."
    }
}

// ---------- Уведомления ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotificationsSection(vm: MainViewModel, screen: Screen.App) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val records by remember(screen.packageName) { vm.notificationsFor(screen.packageName) }
        .collectAsStateWithLifecycle(null)
    FilterTabs(filter, counts = null) { vm.filter.value = it }
    val list = records
    when {
        list == null -> Unit
        list.isEmpty() -> EmptyState(if (filter == HistoryFilter.ALL) "История пуста." else "Здесь ничего нет.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            list.groupBy { formatDay(it.postTime) }.forEach { (day, dayItems) ->
                stickyHeader(key = "h_$day") { SectionHeader(day) }
                itemsIndexed(dayItems, key = { _, r -> r.id }) { index, record ->
                    Box(Modifier.animateItem().appearIn(index)) {
                        NotificationCard(record) { vm.navigate(Screen.Detail(record.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(record: NotificationRecord, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(CardDefaults.elevatedShape).pressScale(onClick).animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.title?.takeIf { it.isNotBlank() } ?: "(без заголовка)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime(record.postTime),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val body = record.lines ?: record.fullText
            if (!body.isNullOrBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            record.imagePath?.let {
                MediaThumb(
                    it, MediaKind.IMAGE,
                    Modifier.padding(top = 4.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                StatusChip(record.status, record.removalReason)
                record.removedTime?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "в ${formatTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------- Чаты ----------

@Composable
private fun ChatsSection(vm: MainViewModel, screen: Screen.App) {
    val convs by remember(screen.packageName) { vm.conversations(screen.packageName) }
        .collectAsStateWithLifecycle(null)
    val list = convs ?: return
    if (list.isEmpty()) {
        EmptyState("Переписок пока нет.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, c -> c.convKey }) { index, c ->
            Column(Modifier.animateItem().appearIn(index)) {
                ConversationRow(c) { vm.navigate(Screen.Chat(screen.packageName, c.convKey, c.title)) }
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.pressScale(onClick),
        headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.lastText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.deleted > 0 || c.edited > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (c.deleted > 0) CountBadge("🗑 удалено ${c.deleted}", deleted = true)
                        if (c.edited > 0) CountBadge("✏️ изменено ${c.edited}", deleted = false)
                    }
                }
            }
        },
        trailingContent = { Text(formatShort(c.lastTime), style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
fun CountBadge(text: String, deleted: Boolean) {
    val (bg, fg) = if (deleted) {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ---------- Медиа ----------

@Composable
private fun MediaSection(vm: MainViewModel, screen: Screen.App) {
    var onlyDeleted by remember { mutableStateOf(false) }
    val media by remember(screen.packageName, onlyDeleted) { vm.media(screen.packageName, onlyDeleted) }
        .collectAsStateWithLifecycle(null)
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !onlyDeleted, onClick = { onlyDeleted = false }, label = { Text("Все") })
        FilterChip(selected = onlyDeleted, onClick = { onlyDeleted = true }, label = { Text("Удалённые") })
    }
    val list = media ?: return
    if (list.isEmpty()) {
        EmptyState(if (onlyDeleted) "Удалённых файлов нет." else "Файлов пока нет.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItemsIndexed(list, key = { _, m -> m.id }) { index, m ->
            MediaTile(m, Modifier.animateItem().appearIn(index)) {
                vm.navigate(Screen.Viewer(m.localPath, m.kind, "${MediaKind.label(m.kind)} · ${formatFull(m.createdTime)}", m.sourceDeletedTime != null))
            }
        }
    }
}

@Composable
private fun MediaTile(m: MediaRecord, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).pressScale(onClick)) {
        MediaThumb(
            m.localPath, m.kind, Modifier.fillMaxSize(),
            contentScale = if (m.kind == MediaKind.STICKER) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
        )
        if (m.sourceDeletedTime != null) {
            Surface(
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                shape = RoundedCornerShape(bottomStart = 8.dp),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(Icons.Outlined.DeleteOutline, "Удалено", Modifier.padding(4.dp).size(16.dp))
            }
        }
        Text(
            formatShort(m.createdTime),
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
