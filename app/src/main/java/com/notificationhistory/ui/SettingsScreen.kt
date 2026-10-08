package com.notificationhistory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notificationhistory.data.AppSettings
import com.notificationhistory.service.MediaBackup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings = vm.settings
    val context = LocalContext.current
    var retention by remember { mutableIntStateOf(settings.retentionDays) }
    var ongoing by remember { mutableStateOf(settings.saveOngoing) }
    var mediaBackup by remember { mutableStateOf(settings.mediaBackup) }
    var confirmClear by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val filesGranted = remember(tick) { MediaBackup.hasPermission() }
    val batteryOk = remember(tick) { SystemSettings.isIgnoringBatteryOptimizations(context) }
    val excluded by vm.excluded.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Настройки") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                ListItem(
                    modifier = Modifier.clickable { vm.navigate(Screen.AppFilter) },
                    leadingContent = { Icon(Icons.Outlined.Apps, null) },
                    headlineContent = { Text("Какие приложения сохранять") },
                    supportingContent = {
                        Text(if (excluded.isEmpty()) "Все приложения" else "Выключено: ${excluded.size}")
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
                ListItem(
                    modifier = Modifier.clickable { vm.navigate(Screen.Setup) },
                    leadingContent = {
                        Icon(
                            if (batteryOk) Icons.Outlined.BatteryChargingFull else Icons.Outlined.WarningAmber, null,
                            tint = if (batteryOk) Color.Unspecified else MaterialTheme.colorScheme.error,
                        )
                    },
                    headlineContent = { Text("Работа в фоне") },
                    supportingContent = {
                        Text(
                            if (batteryOk) "Ограничения батареи сняты" else "Система может выключать приложение — нажмите, чтобы исправить",
                            color = if (batteryOk) Color.Unspecified else MaterialTheme.colorScheme.error,
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
                HorizontalDivider()
                SectionHeader("Хранить историю")
            }
            items(AppSettings.RETENTION_OPTIONS) { (days, label) ->
                ListItem(
                    modifier = Modifier.selectable(
                        selected = retention == days,
                        role = Role.RadioButton,
                        onClick = {
                            retention = days
                            settings.retentionDays = days
                            vm.purgeNow()
                        },
                    ),
                    leadingContent = { RadioButton(selected = retention == days, onClick = null) },
                    headlineContent = { Text(label) },
                )
            }

            item {
                HorizontalDivider()
                SectionHeader("Что сохранять")
                ListItem(
                    modifier = Modifier.clickable {
                        ongoing = !ongoing
                        settings.saveOngoing = ongoing
                    },
                    headlineContent = { Text("Постоянные уведомления") },
                    supportingContent = { Text("Музыка, загрузки, навигация, фоновые сервисы. Обычно это шум.") },
                    trailingContent = { Switch(checked = ongoing, onCheckedChange = null) },
                )
                val supported = MediaBackup.isSupported()
                ListItem(
                    modifier = Modifier.clickable(enabled = supported) {
                        mediaBackup = !mediaBackup
                        settings.mediaBackup = mediaBackup
                        if (mediaBackup && !MediaBackup.hasPermission()) SystemSettings.openAllFilesAccess(context)
                    },
                    headlineContent = { Text("Копии фото и стикеров из мессенджеров") },
                    supportingContent = {
                        Text(
                            when {
                                !supported -> "Доступно на Android 11 и новее."
                                mediaBackup && !filesGranted -> "Нужно разрешение «Доступ ко всем файлам» — нажмите, чтобы выдать."
                                else -> "WhatsApp, Telegram, AyuGram: входящие фото, стикеры, видео и голосовые копируются сразу, " +
                                    "поэтому остаются, даже если отправитель их удалит. В мессенджере должна быть включена автозагрузка медиа."
                            },
                            color = if (mediaBackup && supported && !filesGranted) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                    trailingContent = { Switch(checked = mediaBackup && supported, onCheckedChange = null, enabled = supported) },
                )
            }

            item {
                HorizontalDivider()
                SectionHeader("Данные")
                ListItem(
                    modifier = Modifier.clickable { SystemSettings.openListenerSettings(context) },
                    headlineContent = { Text("Доступ к уведомлениям") },
                    supportingContent = { Text("Системные настройки разрешения") },
                )
                ListItem(
                    modifier = Modifier.clickable { confirmClear = true },
                    headlineContent = { Text("Очистить всю историю", color = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить всю историю?") },
            text = { Text("Все сохранённые уведомления, сообщения и копии файлов будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.deleteAll() }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}
