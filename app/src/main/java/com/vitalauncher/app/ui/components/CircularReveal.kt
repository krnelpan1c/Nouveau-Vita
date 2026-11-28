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
import com.vitalauncher.app.model.ROW_SLOT_COUNTS

// Exact layout metrics of HomeScreen and EditModeScreen's home PageGrid so the folder zoom circle
// aligns pixel-for-pixel with every slot (center and outer edges) on both open and close.
private val HOME_GRID_H_PAD = 24.dp
private val HOME_BUBBLE_RADIUS = 46.dp
// FolderBubble has a 4.dp gap + 16.dp label beneath the circle; its circle center sits half that
// (10.dp) above the centered Column's vertical midpoint.
private val HOME_LABEL_HALF_OFFSET = 10.dp

private val EDIT_GRID_H_PAD = 48.dp // 28.dp panel margin + 20.dp PageGrid padding
private val EDIT_GRID_V_PAD = 56.dp // 40.dp panel margin + 16.dp PageGrid padding
private val EDIT_BUBBLE_RADIUS = 32.dp

/**
 * The "zoom into the folder" backdrop: a single giant glossy bubble — shaded just like a normal
 * app bubble — that grows from the tapped icon's exact position and size out to a sphere far bigger
 * than the screen, so only its curved, glowing rim remains visible peeking in at the edges (the
 * Vita's own folder-open look). Folder [content] fades in on top of it, unclipped, once the
 * bubble has grown large enough to cover the screen.
 */
@Composable
fun FolderZoomLayer(
    visible: Boolean,
    originSlotIndex: Int,
    isEditMode: Boolean,
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

    val hPadPx = with(density) { (if (isEditMode) EDIT_GRID_H_PAD else HOME_GRID_H_PAD).toPx() }
    val topPadPx = with(density) { (if (isEditMode) EDIT_GRID_V_PAD else STATUS_BAR_HEIGHT).toPx() }
    val bottomPadPx = with(density) { (if (isEditMode) EDIT_GRID_V_PAD else 0.dp).toPx() }
    val labelOffsetPx = with(density) { (if (isEditMode) 0.dp else HOME_LABEL_HALF_OFFSET).toPx() }
    val startRadiusPx = with(density) { (if (isEditMode) EDIT_BUBBLE_RADIUS else HOME_BUBBLE_RADIUS).toPx() }

    val (row, posInRow) = rowAndPosInRow(originSlotIndex)
    val rowCount = ROW_SLOT_COUNTS[row]
    val rowTotal = ROW_SLOT_COUNTS.size
    val maxRowCount = ROW_SLOT_COUNTS.max()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progress.value
            val gridWidth = (size.width - hPadPx * 2f).coerceAtLeast(1f)
            val gridHeight = (size.height - topPadPx - bottomPadPx).coerceAtLeast(1f)
            val pitch = gridWidth / maxRowCount
            val bandTop = (row * gridHeight) / rowTotal
            val bandBottom = ((row + 1) * gridHeight) / rowTotal
            val bandCenterY = (bandTop + bandBottom) / 2f

            val startCenter = Offset(
                x = size.width / 2f + (posInRow - (rowCount - 1) / 2f) * pitch,
                y = topPadPx + bandCenterY - labelOffsetPx,
            )
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

private fun rowAndPosInRow(slotIndex: Int): Pair<Int, Int> {
    var remaining = slotIndex.coerceAtLeast(0)
    ROW_SLOT_COUNTS.forEachIndexed { row, count ->
        if (remaining < count) return row to remaining
        remaining -= count
    }
    val lastRow = ROW_SLOT_COUNTS.lastIndex
    return lastRow to (ROW_SLOT_COUNTS[lastRow] - 1)
}
