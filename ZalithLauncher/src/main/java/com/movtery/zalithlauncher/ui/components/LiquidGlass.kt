/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.setting.AllSettings
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.sources.PrismalGlassLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Liquid Glass (PrismalAGSL) 采样的背景图层。
 * 仅当设置开启且背景可被采样（图片背景）时由 MainActivity 提供，否则为 null
 */
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<PrismalGlassLayer?> { null }

/** 容器色叠加在玻璃上的不透明度，保证文字可读 */
private const val GLASS_SURFACE_ALPHA = 0.3f

/**
 * 当前可用的 Liquid Glass 背景图层；为 null 表示应使用普通样式
 */
@Composable
fun liquidGlassBackdrop(): PrismalGlassLayer? {
    if (!AllSettings.launcherLiquidGlass.state) return null
    return LocalLiquidGlassBackdrop.current
}

/**
 * 统一的 Liquid Glass 表面，所有使用该效果的组件都应通过它绘制
 * @param containerColor 叠加在玻璃上的容器色（会被降低不透明度）
 * @param contentColor 内容颜色
 * @param onClick 非空时启用按压动画与点击
 */
@Composable
fun LiquidGlassSurface(
    backdrop: PrismalGlassLayer,
    shape: Shape,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = LocalContentColor.current,
    blurRadius: Dp = 8.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    // PrismalGlassSurface builds its press ripple with remember(onClick, scope), but the
    // ripple's pointer input is keyed on the scope only and keeps driving the instance
    // created first. If onClick changes identity (any lambda capturing toggled state),
    // drawing reads a new, never-animated ripple and the spring press disappears until
    // the screen is recreated. Hand Prismal a callback whose identity never changes and
    // forward to the latest onClick, so a single ripple lives as long as the surface.
    val latestOnClick = rememberUpdatedState(onClick)
    val hasClick = onClick != null
    val stableOnClick: (() -> Unit)? = remember(hasClick) {
        if (hasClick) {
            val callback: () -> Unit = { latestOnClick.value?.invoke() }
            callback
        } else {
            null
        }
    }

    PrismalGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = { shape },
        onClick = stableOnClick,
        blurRadius = blurRadius,
        surfaceColor = if (containerColor.isSpecified) {
            containerColor.copy(alpha = GLASS_SURFACE_ALPHA)
        } else {
            Color.Unspecified
        }
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/** Holds the release animation that is scheduled but may not have started yet. */
private class PendingRelease {
    var job: Job? = null
}

private val GlassPressSpring = spring<Float>(dampingRatio = 0.48f, stiffness = 420f)

/** Minimum time a press stays visible so very quick taps still show the spring. */
private const val MIN_PRESS_HOLD_MS = 90L

/**
 * Reuse PrismalAGSL's native spring press response on interactive content that is
 * not itself a PrismalGlassSurface (for example, rows inside a glass settings card).
 * The spring/ripple/offset all come from PrismalPressRipple, matching glass surfaces.
 */
@Composable
fun Modifier.liquidGlassPressAnimation(enabled: Boolean = true): Modifier {
    if (!enabled) return this

    val animationScope = rememberCoroutineScope()
    val pressProgress = remember { Animatable(0f) }
    val pendingRelease = remember { PendingRelease() }

    // Observe pointer events in the Initial pass so Material clickables and switches
    // can consume their own events without cancelling the glass press animation.
    val pressGesture = Modifier.pointerInput(animationScope) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )

            // A new press owns the animation: drop any release still waiting from a previous
            // tap (otherwise it would fire later and snap this press back to 0), then spring
            // towards 1 from wherever the previous animation currently is.
            pendingRelease.job?.cancel()
            pendingRelease.job = null
            animationScope.launch {
                pressProgress.animateTo(1f, GlassPressSpring)
            }

            var lastEventUptime = down.uptimeMillis
            try {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change != null) lastEventUptime = change.uptimeMillis
                    pressed = change?.pressed == true
                }
            } finally {
                // Keep a very short tap pressed long enough to actually be seen, then spring
                // back. Never cancel the press animation here: Animatable interrupts it
                // itself and continues from the current visual value.
                val remainingHold = (MIN_PRESS_HOLD_MS - (lastEventUptime - down.uptimeMillis))
                    .coerceAtLeast(0L)
                pendingRelease.job = animationScope.launch {
                    if (remainingHold > 0L) delay(remainingHold)
                    pressProgress.animateTo(0f, GlassPressSpring)
                }
            }
        }
    }

    return this
        .graphicsLayer {
            val progress = pressProgress.value
            val pressLiftPx = 4.dp.toPx()
            val lift = if (size.height > 0f) pressLiftPx / size.height else 0f
            scaleX = 1f + lift * progress
            scaleY = 1f + lift * progress
        }
        .drawWithContent {
            val progress = pressProgress.value
            if (progress > 0f) {
                drawRect(Color.White.copy(alpha = 0.12f * progress))
            }
            drawContent()
        }
        .then(pressGesture)
}
/**
 * 条形区域（如顶部栏）：开启 Liquid Glass 时绘制玻璃背景，否则只是普通容器
 */
@Composable
fun LiquidGlassBar(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val backdrop = liquidGlassBackdrop()
    if (backdrop == null) {
        Box(modifier = modifier) { content() }
    } else {
        LiquidGlassSurface(
            backdrop = backdrop,
            shape = shape,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = LocalContentColor.current,
            blurRadius = 12.dp
        ) {
            content()
        }
    }
}
