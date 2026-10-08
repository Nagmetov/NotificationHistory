package com.notificationhistory.ui

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notificationhistory.data.NotificationRecord
import com.notificationhistory.data.RemovalReason
import com.notificationhistory.data.Status
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: MainViewModel, screen: Screen.Detail) {
    val record by remember(screen.id) { vm.record(screen.id) }.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Уведомление") },
                actions = {
                    val r = record
                    if (r != null) {
                        IconButton(onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("notification", r.copyText())))
                            }
                            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Outlined.ContentCopy, "Копировать текст") }
                        IconButton(onClick = { vm.delete(r.id); vm.back() }) {
                            Icon(Icons.Outlined.Delete, "Удалить из истории")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val r = record ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(r.packageName, size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Text(r.appLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(r.status, r.removalReason)
            }

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.title?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.headlineSmall)
                    }
                    r.fullText?.takeIf { it.isNotBlank() && it != r.lines }?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                    r.subText?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            r.imagePath?.let { path ->
                MediaThumb(
                    path, com.notificationhistory.data.MediaKind.IMAGE,
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            vm.navigate(Screen.Viewer(path, com.notificationhistory.data.MediaKind.IMAGE, "Картинка из уведомления", r.status != Status.ACTIVE))
                        },
                )
            }

            r.lines?.let { lines ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    SelectionContainer {
                        Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Сообщения", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            lines.lineSequence().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(vertical = 8.dp)) {
                    InfoRow("Пришло", formatFull(r.postTime))
                    if (r.updateCount > 0) {
                        InfoRow("Повторно отправлено", "${r.updateCount} раз, последний — ${formatFull(r.lastUpdate)}")
                    }
                    if (r.status == Status.ACTIVE) {
                        InfoRow("Статус", "Всё ещё в шторке уведомлений")
                    } else {
                        r.removedTime?.let { InfoRow(if (r.status == Status.REPLACED) "Обновлено" else "Исчезло", formatFull(it)) }
                        InfoRow("Причина", RemovalReason.description(r.removalReason))
                    }
                    r.channelId?.let { InfoRow("Канал", it) }
                    r.category?.let { InfoRow("Категория", it) }
                    InfoRow("Пакет", r.packageName)
                }
            }

            val launch = remember(r.packageName) { context.packageManager.getLaunchIntentForPackage(r.packageName) }
            if (launch != null) {
                FilledTonalButton(onClick = { context.startActivity(launch) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть ${r.appLabel}")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun NotificationRecord.copyText(): String = listOfNotNull(
    title?.takeIf { it.isNotBlank() },
    fullText?.takeIf { it.isNotBlank() && it != lines },
    lines,
    subText?.takeIf { it.isNotBlank() },
).joinToString("\n")
