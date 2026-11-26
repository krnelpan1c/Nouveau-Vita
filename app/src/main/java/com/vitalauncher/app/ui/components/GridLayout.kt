package com.vitalauncher.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vitalauncher.app.model.ROW_SLOT_COUNTS
import com.vitalauncher.app.ui.theme.VitaColors
import kotlin.math.roundToInt

/** Horizontal pitch scale for [PageGrid] instances showing a folder's contents — tighter than the
 * home screen's so icons don't spread across the whole width of the giant zoomed-in bubble. */
const val FOLDER_PITCH_SCALE = 0.85f

private val CURSOR_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

private class SlotBounds(
    var left: Float = 0f,
    var top: Float = 0f,
    var width: Float = 0f,
    var valid: Boolean = false,
)

/**
 * Lays out a page's slots in the Vita's signature non-uniform 3/4/3 row grid: three rows,
 * rows evenly spaced vertically, and every row sharing the *same* horizontal pitch between icon
 * centers (derived from the densest row) rather than each row independently stretching its own
 * icons to fill the full width — so the 3-icon rows sit at the same spacing as the 4-icon row,
 * just centered with fewer of them.
 *
 * Focus state is read lazily inside a sibling [PageFocusOverlay] via [focusedSlotIndexProvider] so
 * moving the D-pad cursor (including across page boundaries) never recomposes or re-lays out the
 * grid's slots or app bubbles.
 */
@Composable
fun PageGrid(
    modifier: Modifier = Modifier,
    focusedSlotIndexProvider: () -> Int? = { null },
    focusRingDiameter: Dp = 92.dp,
    liftedContent: (@Composable () -> Unit)? = null,
    dragEnabled: Boolean = false,
    /** Scales the horizontal pitch between icon centers — folders use a tighter value than the
     * home screen so their contents don't spread across the whole giant zoomed-in bubble. */
    horizontalPitchScale: Float = 1f,
    onSlotDragStart: ((slotIndex: Int) -> Unit)? = null,
    onSlotDragHover: ((slotIndex: Int?) -> Unit)? = null,
    onSlotDragEnd: ((verticalEscape: Int) -> Unit)? = null,
    slotContent: @Composable (slotIndex: Int) -> Unit,
) {
    val density = LocalDensity.current
    val totalSlots = ROW_SLOT_COUNTS.sum()
    val maxRowCount = ROW_SLOT_COUNTS.max()
    val escapeThresholdPx = remember(density) { with(density) { 130.dp.toPx() } }

    // Stored in grid-local coordinates and updated only during actual layout passes — never via
    // onGloballyPositioned — so 120 Hz pager scrolling triggers zero position callbacks or
    // SnapshotStateMap mutations.
    val slotBounds = remember(totalSlots) { Array(totalSlots) { SlotBounds() } }
    var gridHeightPx by remember { mutableFloatStateOf(0f) }
    var geometryVersion by remember { mutableIntStateOf(0) }

    fun nearestSlot(localPosition: Offset): Int? {
        var bestIdx: Int? = null
        var bestDist = Float.MAX_VALUE
        for (idx in slotBounds.indices) {
            val b = slotBounds[idx]
            if (!b.valid) continue
            val center = Offset(b.left + b.width / 2f, b.top + b.width / 2f)
            val d = (localPosition - center).getDistance()
            if (d < bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }
        return if (bestDist <= escapeThresholdPx) bestIdx else null
    }

    Box(modifier = modifier.fillMaxSize()) {
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                repeat(totalSlots) { thisSlot ->
                    Box(
                        modifier = if (dragEnabled) {
                            Modifier.pointerInput(thisSlot) {
                                var dragLocalPos = Offset.Zero
                                var verticalEscape = 0
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        val b = slotBounds[thisSlot]
                                        dragLocalPos = Offset(b.left, b.top) + offset
                                        verticalEscape = 0
                                        onSlotDragStart?.invoke(thisSlot)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragLocalPos += dragAmount
                                        val nearest = nearestSlot(dragLocalPos)
                                        verticalEscape = when {
                                            nearest != null -> 0
                                            dragLocalPos.y < 0f -> -1
                                            dragLocalPos.y > gridHeightPx -> 1
                                            else -> 0
                                        }
                                        onSlotDragHover?.invoke(nearest)
                                    },
                                    onDragEnd = { onSlotDragEnd?.invoke(verticalEscape) },
                                    onDragCancel = { onSlotDragEnd?.invoke(0) },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ) {
                        slotContent(thisSlot)
                    }
                }
            },
        ) { measurables, constraints ->
            val gridWidth = constraints.maxWidth
            val gridHeight = constraints.maxHeight
            val rowTotal = ROW_SLOT_COUNTS.size
            val pitch = (gridWidth.toFloat() / maxRowCount) * horizontalPitchScale

            val placeables = arrayOfNulls<Placeable>(totalSlots)
            val rowHeights = IntArray(rowTotal)
            var slotCursor = 0
            for (rowIdx in 0 until rowTotal) {
                val bandTop = (rowIdx * gridHeight) / rowTotal
                val bandBottom = ((rowIdx + 1) * gridHeight) / rowTotal
                val bandHeight = (bandBottom - bandTop).coerceAtLeast(0)
                val childConstraints = Constraints(
                    minWidth = 0,
                    maxWidth = gridWidth,
                    minHeight = 0,
                    maxHeight = bandHeight,
                )
                val rowCount = ROW_SLOT_COUNTS[rowIdx]
                var maxH = 0
                for (i in 0 until rowCount) {
                    val p = measurables[slotCursor + i].measure(childConstraints)
                    placeables[slotCursor + i] = p
                    if (p.height > maxH) maxH = p.height
                }
                rowHeights[rowIdx] = maxH
                slotCursor += rowCount
            }

            layout(gridWidth, gridHeight) {
                var anyChanged = false
                if (gridHeightPx != gridHeight.toFloat()) {
                    gridHeightPx = gridHeight.toFloat()
                    anyChanged = true
                }
                var placeCursor = 0
                for (rowIdx in 0 until rowTotal) {
                    val bandTop = (rowIdx * gridHeight) / rowTotal
                    val bandBottom = ((rowIdx + 1) * gridHeight) / rowTotal
                    val bandHeight = (bandBottom - bandTop).coerceAtLeast(0)
                    val rowHeight = rowHeights[rowIdx]
                    val rowTop = bandTop + (bandHeight - rowHeight) / 2
                    val rowCount = ROW_SLOT_COUNTS[rowIdx]

                    for (i in 0 until rowCount) {
                        val slotIdx = placeCursor + i
                        val placeable = placeables[slotIdx] ?: continue
                        val centerX = gridWidth / 2f + (i - (rowCount - 1) / 2f) * pitch
                        val x = (centerX - placeable.width / 2f).roundToInt()
                        val y = rowTop + (rowHeight - placeable.height) / 2
                        placeable.placeRelative(x, y)

                        val xf = x.toFloat()
                        val yf = y.toFloat()
                        val wf = placeable.width.toFloat()
                        val b = slotBounds[slotIdx]
                        if (!b.valid || b.left != xf || b.top != yf || b.width != wf) {
                            b.left = xf
                            b.top = yf
                            b.width = wf
                            b.valid = true
                            anyChanged = true
                        }
                    }
                    placeCursor += rowCount
                }
                if (anyChanged) geometryVersion++
            }
        }

        PageFocusOverlay(
            focusedSlotIndexProvider = focusedSlotIndexProvider,
            slotBounds = slotBounds,
            geometryVersionProvider = { geometryVersion },
            focusRingDiameter = focusRingDiameter,
            liftedContent = liftedContent,
        )
    }
}

@Composable
private fun PageFocusOverlay(
    focusedSlotIndexProvider: () -> Int?,
    slotBounds: Array<SlotBounds>,
    geometryVersionProvider: () -> Int,
    focusRingDiameter: Dp,
    liftedContent: (@Composable () -> Unit)?,
) {
    val focusedSlotIndex by remember(focusedSlotIndexProvider) {
        derivedStateOf { focusedSlotIndexProvider() }
    }
    val idx = focusedSlotIndex ?: return
    if (idx !in slotBounds.indices) return

    // Read geometryVersion only while this page actually hosts the focus ring.
    val version = geometryVersionProvider()
    val b = slotBounds[idx]
    if (!b.valid || version < 0) return

    val density = LocalDensity.current
    val diameterPx = with(density) { focusRingDiameter.toPx() }
    val ringLeft = b.left + b.width / 2f - diameterPx / 2f
    val ringTop = b.top
    FocusCursor(
        left = ringLeft,
        top = ringTop,
        diameter = focusRingDiameter,
        liftedContent = liftedContent,
    )
}

@Composable
private fun FocusCursor(
    left: Float,
    top: Float,
    diameter: Dp,
    liftedContent: (@Composable () -> Unit)?,
) {
    val animatedLeft = animateFloatAsState(left, CURSOR_SPRING, label = "cursorX")
    val animatedTop = animateFloatAsState(top, CURSOR_SPRING, label = "cursorY")

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = animatedLeft.value
                translationY = animatedTop.value
            }
            .size(diameter),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(VitaColors.GrabbedRing.copy(alpha = 0.22f)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(3.dp, VitaColors.GrabbedRing, CircleShape),
        )
        if (liftedContent != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                liftedContent()
            }
        }
    }
}
