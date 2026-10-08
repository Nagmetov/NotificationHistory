package com.notificationhistory.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.notificationhistory.service.NotificationCaptureService

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.purgeNow()

        setContent {
            NotificationHistoryTheme {
                var granted by remember { mutableStateOf(NotificationCaptureService.isEnabled(this)) }
                var setupDone by remember { mutableStateOf(vm.settings.setupDone) }
                LifecycleResumeEffect(Unit) {
                    granted = NotificationCaptureService.isEnabled(this@MainActivity)
                    NotificationCaptureService.requestRebind(this@MainActivity)
                    vm.scanMedia()
                    onPauseOrDispose { }
                }

                // Первый запуск или доступ к уведомлениям отозван — сначала настройка.
                AnimatedContent(
                    targetState = !granted || !setupDone,
                    transitionSpec = { with(Motion) { contentSwap() } },
                    label = "setup",
                ) { needSetup ->
                    if (needSetup) {
                        SetupScreen(vm.settings, onBack = null, onDone = { setupDone = true })
                    } else {
                        Navigation(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun Navigation(vm: MainViewModel) {
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
    // Глубина стека нужна, чтобы понять направление: вперёд — справа, назад — слева.
    AnimatedContent(
        targetState = vm.backStack.last() to vm.backStack.size,
        transitionSpec = {
            with(Motion) { screenTransition(forward = targetState.second >= initialState.second) }
        },
        contentKey = { it.first },
        label = "screen",
    ) { (screen, _) ->
        when (screen) {
            Screen.Home -> HomeScreen(vm)
            is Screen.App -> AppHistoryScreen(vm, screen)
            is Screen.Detail -> DetailScreen(vm, screen)
            is Screen.Chat -> ChatScreen(vm, screen)
            is Screen.Viewer -> ViewerScreen(vm, screen)
            Screen.Settings -> SettingsScreen(vm)
            Screen.AppFilter -> AppFilterScreen(vm)
            Screen.Setup -> SetupScreen(vm.settings, onBack = vm::back, onDone = vm::back)
        }
    }
}
