package com.notificationhistory.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Щипок — масштаб, перетаскивание — сдвиг, двойной тап — приблизить/вернуть. */
@Composable
private fun ZoomableMedia(screen: Screen.Viewer, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    fun reset() = scope.launch {
        launch { scale.animateTo(1f, Motion.gentleSpring()) }
        launch { offset.animateTo(Offset.Zero, Motion.gentleSpring()) }
    }

    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale.value > 1.05f) reset() else scope.launch {
                        // Приближаем к точке касания, а не к центру.
                        val center = Offset(size.width / 2f, size.height / 2f)
                        launch { scale.animateTo(2.5f, Motion.gentleSpring()) }
                        launch { offset.animateTo((center - tap) * 1.5f, Motion.gentleSpring()) }
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scope.launch {
                        val next = (scale.value * zoom).coerceIn(1f, 5f)
                        scale.snapTo(next)
                        offset.snapTo(if (next <= 1f) Offset.Zero else offset.value + pan)
                    }
                }
            },
    ) {
        MediaThumb(
            screen.path, screen.kind,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
            contentScale = ContentScale.Fit,
            maxPx = 2048,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: MainViewModel, screen: Screen.Viewer) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text(screen.title, style = MaterialTheme.typography.titleMedium, maxLines = 1) },
                actions = {
                    IconButton(onClick = { shareFile(context, screen.path) }) { Icon(Icons.Outlined.Share, "Поделиться") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (screen.deleted) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "🗑 Оригинал удалён из мессенджера — это сохранённая копия",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            ZoomableMedia(screen, Modifier.fillMaxWidth().weight(1f))
            FilledTonalButton(onClick = { openFile(context, screen.path) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть в другом приложении")
            }
        }
    }
}
