package com.notificationhistory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class AppListFilter(val title: String) { ALL("Все"), ON("Сохраняются"), OFF("Не сохраняются") }

/** Выбор приложений, уведомления которых сохраняются в историю. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFilterScreen(vm: MainViewModel) {
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    val excluded by vm.excluded.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppListFilter.ALL) }
    var menu by remember { mutableStateOf(false) }

    val shown = remember(apps, excluded, query, filter) {
        apps.orEmpty()
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
            .filter {
                when (filter) {
                    AppListFilter.ALL -> true
                    AppListFilter.ON -> it.packageName !in excluded
                    AppListFilter.OFF -> it.packageName in excluded
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                title = { Text("Какие приложения сохранять") },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        // Действуют на то, что сейчас показано: можно найти «банк» и выключить все найденные.
                        DropdownMenuItem(
                            text = { Text(if (query.isBlank()) "Включить все" else "Включить найденные") },
                            onClick = { menu = false; vm.setExcluded(shown.map { it.packageName }, exclude = false) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (query.isBlank()) "Выключить все" else "Выключить найденные") },
                            onClick = { menu = false; vm.setExcluded(shown.map { it.packageName }, exclude = true) },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск приложения") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Очистить") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppListFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
                }
            }
            val total = apps?.size ?: 0
            Text(
                "Сохраняются ${total - (apps.orEmpty().count { it.packageName in excluded })} из $total. " +
                    "Новые приложения сохраняются по умолчанию.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (apps == null) return@Column
            if (shown.isEmpty()) {
                EmptyState("Ничего не найдено.")
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.packageName }) { app ->
                    val enabled = app.packageName !in excluded
                    ListItem(
                        modifier = Modifier.clickable { vm.setExcluded(listOf(app.packageName), exclude = enabled) },
                        leadingContent = { AppIcon(app.packageName) },
                        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (app.inHistory) {
                            { Text("есть в истории", style = MaterialTheme.typography.labelSmall) }
                        } else null,
                        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                    )
                }
            }
        }
    }
}
