package com.vitalauncher.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vitalauncher.app.model.ROW_SLOT_COUNTS
import com.vitalauncher.app.ui.theme.VitaColors
import kotlin.math.roundToInt

/** Horizontal pitch scale for [PageGrid] instances showing a folder's contents — tighter than the
 * home screen's so icons don't spread across the whole width of the giant zoomed-in bubble. */
const val FOLDER_PITCH_SCALE = 0.85f

/**
 * Lays out a page's slots in the Vita's signature non-uniform 3/4/3 row grid: three rows,
 * rows evenly spaced vertically, and every row sharing the *same* horizontal pitch between icon
 * centers (derived from the densest row) rather than each row independently stretching its own
 * icons to fill the full width — so the 3-icon rows sit at the same spacing as the 4-icon row,
 * just centered with fewer of them.
 *
 * When [focusedSlotIndex] is non-null, a glowing cyan ring the size of [focusRingDiameter]
 * glides smoothly to that slot's bubble instead of snapping to it, for D-pad/gamepad navigation.
 * If [liftedContent] is also given, it rides along on top of that same ring — the "picked up"
 * bubble following the cursor, whether the pickup came from a gamepad lift or a touch drag.
 *
 * When [dragEnabled], press-and-hold-then-drag is recognized on every slot: [onSlotDragStart]
 * fires once the hold registers, [onSlotDragHover] fires with the slot currently under the
 * finger (or null once the drag strays far enough to count as "off the grid"), and
 * [onSlotDragEnd] fires on release/cancel with which vertical edge (if any) the drag ended
 * beyond: -1 above this grid, +1 below it, 0 if it ended on/near the grid itself.
 */
@Composable
fun PageGrid(
    modifier: Modifier = Modifier,
    focusedSlotIndex: Int? = null,
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
    var rootPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var rootHeightPx by remember { mutableStateOf(0f) }
    // Top-left + width of each slot's tightly-wrapped content (bubble + label), in root px.
    val slotTopLeft = remember { mutableStateMapOf<Int, Offset>() }
    val slotWidth = remember { mutableStateMapOf<Int, Float>() }
    val maxRowCount = ROW_SLOT_COUNTS.max()
    val escapeThresholdPx = with(density) { 130.dp.toPx() }

    fun nearestSlot(position: Offset): Int? {
        var bestIdx: Int? = null
        var bestDist = Float.MAX_VALUE
        for ((idx, topLeft) in slotTopLeft) {
            val w = slotWidth[idx] ?: continue
            val center = Offset(topLeft.x + w / 2f, topLeft.y + w / 2f)
            val d = (position - center).getDistance()
            if (d < bestDist) {
                bestDist = d
                bestIdx = idx
            }
        }
        return if (bestDist <= escapeThresholdPx) bestIdx else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootPositionInRoot = it.positionInRoot()
                rootHeightPx = it.size.height.toFloat()
            },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            var rowStart = 0
            for (rowCount in ROW_SLOT_COUNTS) {
                val thisRowStart = rowStart
                NonUniformRow(
                    rowCount = rowCount,
                    maxRowCount = maxRowCount,
                    pitchScale = horizontalPitchScale,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    repeat(rowCount) { i ->
                        val thisSlot = thisRowStart + i
                        Box(
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    slotTopLeft[thisSlot] = coords.positionInRoot()
                                    slotWidth[thisSlot] = coords.size.width.toFloat()
                                }
                                .then(
                                    if (dragEnabled) {
                                        Modifier.pointerInput(thisSlot) {
                                            var dragRootPos = Offset.Zero
                                            var verticalEscape = 0
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { offset ->
                                                    val topLeft = slotTopLeft[thisSlot] ?: rootPositionInRoot
                                                    dragRootPos = topLeft + offset
                                                    verticalEscape = 0
                                                    onSlotDragStart?.invoke(thisSlot)
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragRootPos += dragAmount
                                                    val nearest = nearestSlot(dragRootPos)
                                                    verticalEscape = when {
                                                        nearest != null -> 0
                                                        dragRootPos.y < rootPositionInRoot.y -> -1
                                                        dragRootPos.y > rootPositionInRoot.y + rootHeightPx -> 1
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
                                ),
                        ) {
                            slotContent(thisSlot)
                        }
                    }
                }
                rowStart += rowCount
            }
        }

        val topLeft = focusedSlotIndex?.let { slotTopLeft[it] }
        val width = focusedSlotIndex?.let { slotWidth[it] }
        if (topLeft != null && width != null) {
            val diameterPx = with(density) { focusRingDiameter.toPx() }
            val relative = topLeft - rootPositionInRoot
            val ringLeft = relative.x + width / 2f - diameterPx / 2f
            val ringTop = relative.y
            FocusCursor(left = ringLeft, top = ringTop, diameterPx = diameterPx, liftedContent = liftedContent)
        }
    }
}

/**
 * Places [rowCount] children centered in the row, spaced at the same pitch (1 / [maxRowCount] of
 * the row's width) a full row of [maxRowCount] children would use, instead of each stretching
 * its own children edge-to-edge with [Arrangement.SpaceEvenly].
 */
@Composable
private fun NonUniformRow(
    rowCount: Int,
    maxRowCount: Int,
    pitchScale: Float = 1f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val rowWidth = constraints.maxWidth
        val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
        val pitch = (rowWidth.toFloat() / maxRowCount) * pitchScale

        layout(rowWidth, rowHeight) {
            placeables.forEachIndexed { i, placeable ->
                val centerX = rowWidth / 2f + (i - (rowCount - 1) / 2f) * pitch
                val x = (centerX - placeable.width / 2f).roundToInt()
                val y = (rowHeight - placeable.height) / 2
                placeable.placeRelative(x, y)
            }
        }
    }
}

@Composable
private fun FocusCursor(left: Float, top: Float, diameterPx: Float, liftedContent: (@Composable () -> Unit)?) {
    val density = LocalDensity.current
    val spec = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
    val animatedLeft by animateFloatAsState(left, spec, label = "cursorX")
    val animatedTop by animateFloatAsState(top, spec, label = "cursorY")
    val animatedDiameter by animateFloatAsState(diameterPx, spec, label = "cursorD")

    Box(
        modifier = Modifier
            .offset { IntOffset(animatedLeft.toInt(), animatedTop.toInt()) }
            .size(with(density) { animatedDiameter.toDp() }),
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
