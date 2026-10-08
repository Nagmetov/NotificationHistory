package com.notificationhistory.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.notificationhistory.data.AppExtras
import com.notificationhistory.data.AppGroup
import com.notificationhistory.data.HistoryFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История уведомлений") },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BatteryWarning { vm.navigate(Screen.Setup) }
            FilterTabs(filter, counts) { vm.filter.value = it }
            val list = groups
            // Ключ — вкладка и «пусто/не пусто»: при обновлении данных внутри вкладки список не мигает.
            AnimatedContent(
                targetState = Triple(filter, list == null, list?.isEmpty() == true),
                transitionSpec = { with(Motion) { contentSwap() } },
                label = "home-list",
            ) { (shownFilter, loading, empty) ->
                when {
                    loading -> Unit
                    empty -> EmptyState(
                        when (shownFilter) {
                            HistoryFilter.ALL -> "Пока пусто.\nНовые уведомления будут появляться здесь автоматически."
                            HistoryFilter.ACTIVE -> "Сейчас в шторке нет сохранённых уведомлений."
                            HistoryFilter.REMOVED -> "Удалённых уведомлений пока нет."
                        }
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(list.orEmpty(), key = { _, g -> g.packageName }) { index, group ->
                            Column(Modifier.animateItem().appearIn(index)) {
                                AppGroupRow(group, extras[group.packageName] ?: AppExtras(), showRemoved = shownFilter != HistoryFilter.ACTIVE) {
                                    vm.navigate(Screen.App(group.packageName, group.appLabel))
                                }
                                HorizontalDivider(Modifier.padding(start = 72.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Напоминание, если система снова может выключать приложение (например, после обновления оболочки). */
@Composable
private fun BatteryWarning(onClick: () -> Unit) {
    val context = LocalContext.current
    var ok by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        ok = SystemSettings.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    AnimatedVisibility(visible = !ok, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "⚠️ Система может выключать приложение для экономии заряда — уведомления перестанут сохраняться. Нажмите, чтобы исправить.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun AppGroupRow(group: AppGroup, extras: AppExtras, showRemoved: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.pressScale(onClick).animateContentSize(),
        leadingContent = { AppIcon(group.packageName) },
        headlineContent = { Text(group.appLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.lastPreview, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showRemoved && (extras.deletedMessages > 0 || extras.editedMessages > 0 || extras.deletedMedia > 0)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (extras.deletedMessages > 0) CountBadge("🗑 сообщ. ${extras.deletedMessages}", deleted = true)
                        if (extras.deletedMedia > 0) CountBadge("🖼 файлов ${extras.deletedMedia}", deleted = true)
                        if (extras.editedMessages > 0) CountBadge("✏️ ${extras.editedMessages}", deleted = false)
                    }
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatShort(group.lastTime), style = MaterialTheme.typography.labelMedium)
                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    AnimatedCount(group.count)
                }
            }
        },
    )
}
