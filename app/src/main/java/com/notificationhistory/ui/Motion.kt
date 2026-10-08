package com.notificationhistory.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Единые параметры движения, чтобы всё в приложении двигалось в одном характере. */
object Motion {
    const val SHORT = 180
    const val MEDIUM = 320

    fun <T> gentleSpring() = spring<T>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Переход между экранами: вглубь — новый экран въезжает справа, назад — наоборот. */
    fun <S> AnimatedContentTransitionScope<S>.screenTransition(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        val enter = slideInHorizontally(tween(MEDIUM, easing = FastOutSlowInEasing)) { w -> dir * w / 4 } +
            fadeIn(tween(MEDIUM))
        val exit = slideOutHorizontally(tween(MEDIUM, easing = FastOutSlowInEasing)) { w -> -dir * w / 6 } +
            fadeOut(tween(SHORT))
        return (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else 0f }
    }

    /** Смена содержимого вкладки/раздела: лёгкий подъём и проявление. */
    fun <S> AnimatedContentTransitionScope<S>.contentSwap(): ContentTransform =
        (fadeIn(tween(MEDIUM, delayMillis = 60)) + slideInVertically(tween(MEDIUM)) { it / 24 })
            .togetherWith(fadeOut(tween(SHORT)))
            .using(SizeTransform(clip = false))
}

/** Карточка чуть «проседает» под пальцем — тактильный отклик без вибрации. */
fun Modifier.pressScale(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.gentleSpring(), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
}

/**
 * Появление элемента списка при первом показе экрана: каскадом, сверху вниз.
 * Элементы дальше первого экрана появляются без задержки, чтобы прокрутка не «тормозила».
 */
fun Modifier.appearIn(index: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(10) * 35L))
        launch { progress.animateTo(1f, tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) }
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

/** Число, которое при изменении «прокручивается» вверх или вниз, как на табло. */
@Composable
fun AnimatedCount(count: Int, style: TextStyle = LocalTextStyle.current, prefix: String = "") {
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(Motion.gentleSpring()) { if (up) it else -it } + fadeIn(tween(Motion.SHORT)))
                .togetherWith(slideOutVertically(tween(Motion.SHORT)) { if (up) -it else it } + fadeOut(tween(Motion.SHORT)))
                .using(SizeTransform(clip = false))
        },
        label = "count",
    ) { value -> Text("$prefix$value", style = style) }
}

/** Мягкое появление/исчезновение со «щелчком» — для значков статуса и галочек. */
val popIn = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.6f) +
    fadeIn(tween(Motion.SHORT))
val popOut = scaleOut(tween(Motion.SHORT), targetScale = 0.8f) + fadeOut(tween(Motion.SHORT))
