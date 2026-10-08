package com.notificationhistory.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.notificationhistory.data.AppSettings
import com.notificationhistory.service.MediaBackup
import com.notificationhistory.service.NotificationCaptureService

/**
 * Пошаговая настройка: без доступа к уведомлениям приложение бесполезно,
 * а без снятия ограничений батареи оболочки вроде HyperOS выгружают сервис — и история молча перестаёт писаться.
 *
 * @param onDone null — экран открыт из настроек (есть кнопка «Назад»), иначе это первый запуск.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(settings: AppSettings, onBack: (() -> Unit)?, onDone: () -> Unit) {
    val context = LocalContext.current
    // Перечитываем состояние при каждом возвращении из системных настроек.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val listener = remember(tick) { NotificationCaptureService.isEnabled(context) }
    val battery = remember(tick) { SystemSettings.isIgnoringBatteryOptimizations(context) }
    val files = remember(tick) { MediaBackup.hasPermission() }
    var autostartDone by remember { mutableStateOf(settings.autostartConfirmed) }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    },
                    title = { Text("Настройка работы") },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onBack == null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.NotificationsActive, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Text("Давайте всё настроим", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Пара шагов, чтобы история сохранялась всегда — даже когда приложение закрыто. " +
                            "Все данные хранятся только на этом телефоне.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SetupStep(
                number = 1,
                title = "Доступ к уведомлениям",
                text = "Без этого приложение не видит уведомлений. Обязательно.",
                done = listener,
                action = "Выдать доступ",
                onAction = { SystemSettings.openListenerSettings(context) },
            ) {
                if (!listener) {
                    Hint(
                        "Переключатель серый и не нажимается? Android блокирует его для приложений не из Google Play. " +
                            "Откройте «О приложении» → ⋮ → «Разрешить ограниченные настройки» и вернитесь сюда.",
                    )
                    TextButton(onClick = { SystemSettings.openAppDetails(context) }) { Text("Открыть «О приложении»") }
                }
            }

            SetupStep(
                number = 2,
                title = "Работа в фоне без ограничений",
                text = "Иначе система «экономит заряд» и выключает приложение — уведомления перестают сохраняться, " +
                    "и вы об этом не узнаете. На расход батареи почти не влияет: приложение просыпается только когда приходит уведомление.",
                done = battery,
                action = "Снять ограничения",
                onAction = { SystemSettings.requestIgnoreBatteryOptimizations(context) },
            ) {
                if (SystemSettings.isXiaomi) {
                    Hint("На Xiaomi дополнительно: «Контроль активности» → «Нет ограничений».")
                    TextButton(onClick = { SystemSettings.openBatterySaverSettings(context) }) {
                        Text("Открыть «Контроль активности»")
                    }
                }
            }

            if (SystemSettings.isAggressiveOem) {
                SetupStep(
                    number = 3,
                    title = "Автозапуск",
                    text = "На этом телефоне без автозапуска приложение не перезапустится после перезагрузки " +
                        "или очистки памяти. Включите переключатель напротив «История уведомлений».",
                    done = autostartDone,
                    action = "Открыть автозапуск",
                    onAction = { SystemSettings.openAutostartSettings(context) },
                ) {
                    if (!autostartDone) {
                        TextButton(onClick = {
                            autostartDone = true
                            settings.autostartConfirmed = true
                        }) { Text("Готово, автозапуск включён") }
                    }
                    if (SystemSettings.isXiaomi) {
                        Hint("Совет: в списке недавних приложений потяните карточку вниз и нажмите 🔒 — HyperOS не будет её закрывать.")
                    }
                }
            }

            if (MediaBackup.isSupported()) {
                SetupStep(
                    number = if (SystemSettings.isAggressiveOem) 4 else 3,
                    title = "Фото и стикеры (по желанию)",
                    text = "Доступ ко всем файлам нужен, чтобы сохранять копии фото, стикеров и голосовых из WhatsApp и Telegram, " +
                        "пока отправитель их не удалил.",
                    done = files,
                    action = "Выдать доступ к файлам",
                    onAction = {
                        settings.mediaBackup = true
                        SystemSettings.openAllFilesAccess(context)
                    },
                    optional = true,
                )
            }

            if (onBack == null) {
                Button(
                    onClick = {
                        settings.setupDone = true
                        onDone()
                    },
                    enabled = listener,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(if (listener) "Готово" else "Сначала выдайте доступ к уведомлениям")
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    text: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
    optional: Boolean = false,
    extra: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (done) cs.surfaceContainerLow else cs.surfaceContainerHigh, tween(Motion.MEDIUM), label = "card")
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (!done && !optional) BorderStroke(1.dp, cs.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier.animateContentSize(Motion.gentleSpring()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val circle by animateColorAsState(if (done) Color(0xFF2E7D32) else cs.primary, tween(Motion.MEDIUM), label = "step")
                Surface(
                    shape = CircleShape,
                    color = circle,
                    contentColor = Color.White,
                    modifier = Modifier.size(28.dp),
                ) {
                    // Вернулись из системных настроек с выданным доступом — номер шага превращается в галочку.
                    AnimatedContent(
                        targetState = done,
                        transitionSpec = { popIn togetherWith popOut },
                        contentAlignment = Alignment.Center,
                        label = "step-mark",
                    ) { isDone ->
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            if (isDone) Icon(Icons.Filled.Check, "Готово", Modifier.size(18.dp))
                            else Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            AnimatedContent(
                targetState = done,
                transitionSpec = { with(Motion) { contentSwap() } },
                label = "step-action",
            ) { isDone ->
                when {
                    isDone -> Text("✓ Сделано", style = MaterialTheme.typography.labelLarge, color = Color(0xFF43A047))
                    optional -> OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
                    else -> FilledTonalButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
                }
            }
            extra()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
