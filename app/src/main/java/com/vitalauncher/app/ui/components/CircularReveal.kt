package com.vitalauncher.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The "zoom into the folder" backdrop: a single giant glossy bubble — shaded just like a normal
 * app bubble — that grows from the tapped icon's position and size out to a sphere far bigger
 * than the screen, so only its curved, glowing rim remains visible peeking in at the edges (the
 * Vita's own folder-open look). Folder [content] fades in on top of it, unclipped, once the
 * bubble has grown large enough to cover the screen.
 */
@Composable
fun FolderZoomLayer(
    visible: Boolean,
    originFraction: Pair<Float, Float>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }
    var shouldCompose by remember { mutableStateOf(visible) }

    LaunchedEffect(visible) {
        if (visible) {
            shouldCompose = true
            progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        } else {
            progress.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            shouldCompose = false
        }
    }

    if (!shouldCompose && progress.value <= 0f) return

    val startRadiusPx = with(density) { 46.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progress.value
            val startCenter = Offset(size.width * originFraction.first, size.height * originFraction.second)
            val endCenter = Offset(size.width * 0.5f, size.height * 0.5f)
            val center = Offset(
                startCenter.x + (endCenter.x - startCenter.x) * t,
                startCenter.y + (endCenter.y - startCenter.y) * t,
            )
            // Deliberately smaller than half the diagonal: the bubble's curved rim should still
            // land inside the screen at rest (peeking in from the sides), not be pushed off it.
            val endRadius = size.width * 0.42f
            val radius = startRadiusPx + (endRadius - startRadiusPx) * t

            // Deliberately no solid fill here: the real home background (gradient theme or system
            // wallpaper) already sits behind this layer and should show straight through, with
            // only the glossy rim glow below marking the bubble's curved edge.
            // Glassy rim glow, brightest right at the bubble's own edge.
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.82f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.45f),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }

        val contentAlpha = ((progress.value - 0.5f) / 0.5f).coerceIn(0f, 1f)
        Box(modifier = Modifier.fillMaxSize().alpha(contentAlpha)) {
            content()
        }
    }
}
