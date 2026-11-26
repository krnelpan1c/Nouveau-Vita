package com.vitalauncher.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Sub-pixel threshold in page units (~0.4 px on a 2400 px tall display) so springs never snap
// visibly at the end of a transition.
private const val PAGE_VISIBILITY_THRESHOLD = 0.0002f
private const val SNAP_SPRING_STIFFNESS = 460f
private const val PROGRAMMATIC_SPRING_STIFFNESS = 360f
private const val OVERSCROLL_RESISTANCE = 0.28f
private const val MAX_OVERSCROLL_PAGES = 0.26f

private val SNAP_SPRING = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = SNAP_SPRING_STIFFNESS,
    visibilityThreshold = PAGE_VISIBILITY_THRESHOLD,
)

private val PROGRAMMATIC_SPRING = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = PROGRAMMATIC_SPRING_STIFFNESS,
    visibilityThreshold = PAGE_VISIBILITY_THRESHOLD,
)

/**
 * State holder for [VitaVerticalPager].
 *
 * Unlike Compose's `LazyLayout`-backed `VerticalPager` — which re-measures on every scroll frame
 * and subcomposes/disposes pages synchronously mid-drag — [VitaPagerState] drives page translation
 * purely through a continuous [pageOffset] float read in the `graphicsLayer` draw phase. Dragging,
 * flinging, and D-pad page animations trigger zero compositions and zero layout passes.
 */
@Stable
class VitaPagerState(
    initialPage: Int,
    initialPageCount: Int,
    private val scope: CoroutineScope,
) {
    private var _pageCount by mutableIntStateOf(initialPageCount.coerceAtLeast(0))
    val pageCount: Int get() = _pageCount
    val maxPage: Int get() = (_pageCount - 1).coerceAtLeast(0)

    private var _pageOffset by mutableFloatStateOf(initialPage.coerceIn(0, maxPage).toFloat())

    /** Continuous scroll position in page units (`0f` = first page, `1f` = second page, ...).
     * Read ONLY inside `graphicsLayer` or `snapshotFlow` so scrolling never triggers recomposition. */
    val pageOffset: Float get() = _pageOffset

    /** Nearest page index to the current scroll position. */
    val currentPage: Int by derivedStateOf {
        _pageOffset.roundToInt().coerceIn(0, maxPage)
    }

    /** Last page the pager came to rest on. */
    var settledPage by mutableIntStateOf(initialPage.coerceIn(0, maxPage))
        private set

    /** True while the user is actively dragging or a snap/programmatic animation is running. */
    var isScrollInProgress by mutableStateOf(false)
        private set

    /** True while the active motion was initiated by [animateScrollToPage] rather than touch. */
    var isProgrammaticScroll by mutableStateOf(false)
        private set

    private var animationJob: Job? = null
    private var animationGeneration = 0L
    private var currentVelocity = 0f
    private var dragRawOffset = 0f

    internal fun updatePageCount(newCount: Int) {
        val sanitized = newCount.coerceAtLeast(0)
        if (_pageCount == sanitized) return
        _pageCount = sanitized
        val max = (sanitized - 1).coerceAtLeast(0)
        if (_pageOffset > max.toFloat()) {
            cancelActiveAnimation()
            _pageOffset = max.toFloat()
            settledPage = max
            isScrollInProgress = false
            isProgrammaticScroll = false
        } else if (settledPage > max) {
            settledPage = max
        }
    }

    private fun cancelActiveAnimation() {
        animationGeneration++
        animationJob?.cancel()
        animationJob = null
        currentVelocity = 0f
    }

    internal fun beginUserDrag() {
        cancelActiveAnimation()
        dragRawOffset = invertOverscroll(_pageOffset, maxPage.toFloat())
        isProgrammaticScroll = false
        isScrollInProgress = true
    }

    internal fun applyUserDragDelta(deltaPages: Float) {
        dragRawOffset += deltaPages
        _pageOffset = applyOverscroll(dragRawOffset, maxPage.toFloat())
    }

    internal fun endUserDrag(
        pageVelocity: Float,
        flingVelocityThresholdPagesPerSec: Float,
    ) {
        val max = maxPage
        val cur = _pageOffset
        val target = when {
            max == 0 -> 0
            cur <= 0f -> 0
            cur >= max.toFloat() -> max
            abs(pageVelocity) >= flingVelocityThresholdPagesPerSec -> {
                val directed = if (pageVelocity > 0f) ceil(cur).toInt() else floor(cur).toInt()
                directed.coerceIn(0, max)
            }
            else -> cur.roundToInt().coerceIn(0, max)
        }
        val clampedVel = clampSpringVelocity(
            velocity = pageVelocity,
            remainingDistance = target.toFloat() - cur,
            stiffness = SNAP_SPRING_STIFFNESS,
        )
        startSpringAnimation(
            target = target,
            initialVelocity = clampedVel,
            spec = SNAP_SPRING,
            programmatic = false,
        )
    }

    /** Smoothly animates to [page], preserving momentum if another scroll is already in flight. */
    fun animateScrollToPage(page: Int) {
        val target = page.coerceIn(0, maxPage)
        val cur = _pageOffset
        if (abs(target.toFloat() - cur) < PAGE_VISIBILITY_THRESHOLD && !isScrollInProgress) {
            _pageOffset = target.toFloat()
            settledPage = target
            return
        }
        val preservedVelocity = clampSpringVelocity(
            velocity = currentVelocity,
            remainingDistance = target.toFloat() - cur,
            stiffness = PROGRAMMATIC_SPRING_STIFFNESS,
        )
        startSpringAnimation(
            target = target,
            initialVelocity = preservedVelocity,
            spec = PROGRAMMATIC_SPRING,
            programmatic = true,
        )
    }

    fun scrollToPageImmediate(page: Int) {
        val target = page.coerceIn(0, maxPage)
        cancelActiveAnimation()
        _pageOffset = target.toFloat()
        settledPage = target
        isScrollInProgress = false
        isProgrammaticScroll = false
    }

    private fun startSpringAnimation(
        target: Int,
        initialVelocity: Float,
        spec: androidx.compose.animation.core.SpringSpec<Float>,
        programmatic: Boolean,
    ) {
        val gen = ++animationGeneration
        animationJob?.cancel()
        val startValue = _pageOffset
        isScrollInProgress = true
        isProgrammaticScroll = programmatic

        animationJob = scope.launch {
            try {
                animate(
                    initialValue = startValue,
                    targetValue = target.toFloat(),
                    initialVelocity = initialVelocity,
                    animationSpec = spec,
                ) { value, velocity ->
                    if (gen != animationGeneration) return@animate
                    _pageOffset = value
                    currentVelocity = velocity
                }
                if (gen == animationGeneration) {
                    _pageOffset = target.toFloat()
                    currentVelocity = 0f
                    settledPage = target
                }
            } finally {
                if (gen == animationGeneration) {
                    animationJob = null
                    currentVelocity = 0f
                    isScrollInProgress = false
                    isProgrammaticScroll = false
                }
            }
        }
    }

    private companion object {
        /** Prevents critically-damped springs from overshooting and rebounding when the user flicks
         * hard while already very close to the target page (v0 > omega * distance). */
        fun clampSpringVelocity(velocity: Float, remainingDistance: Float, stiffness: Float): Float {
            if (remainingDistance == 0f) return velocity.coerceIn(-4f, 4f)
            val omega = sqrt(stiffness)
            // Only cap velocity pushing past the target; allow opposing velocity to decelerate naturally.
            val sameDirection = (velocity > 0f && remainingDistance > 0f) ||
                (velocity < 0f && remainingDistance < 0f)
            if (!sameDirection) return velocity.coerceIn(-6f, 6f)
            val maxNoOvershoot = omega * abs(remainingDistance)
            val limit = maxNoOvershoot.coerceAtMost(8f)
            return velocity.coerceIn(-limit, limit)
        }

        fun applyOverscroll(raw: Float, max: Float): Float {
            val k = MAX_OVERSCROLL_PAGES / OVERSCROLL_RESISTANCE
            return when {
                raw < 0f -> {
                    val over = -raw
                    -(MAX_OVERSCROLL_PAGES * over) / (over + k)
                }
                raw > max -> {
                    val over = raw - max
                    max + (MAX_OVERSCROLL_PAGES * over) / (over + k)
                }
                else -> raw
            }
        }

        fun invertOverscroll(mapped: Float, max: Float): Float {
            val k = MAX_OVERSCROLL_PAGES / OVERSCROLL_RESISTANCE
            val cap = MAX_OVERSCROLL_PAGES * 0.98f
            return when {
                mapped < 0f -> {
                    val y = (-mapped).coerceAtMost(cap)
                    -(y * k) / (MAX_OVERSCROLL_PAGES - y)
                }
                mapped > max -> {
                    val y = (mapped - max).coerceAtMost(cap)
                    max + (y * k) / (MAX_OVERSCROLL_PAGES - y)
                }
                else -> mapped
            }
        }
    }
}

@Composable
fun rememberVitaPagerState(
    initialPage: Int = 0,
    pageCount: Int,
): VitaPagerState {
    val scope = rememberCoroutineScope()
    val state = remember {
        VitaPagerState(
            initialPage = initialPage,
            initialPageCount = pageCount,
            scope = scope,
        )
    }
    SideEffect {
        state.updatePageCount(pageCount)
    }
    return state
}

/**
 * Full-screen vertical pager built specifically for the launcher's fixed page set: every page is
 * composed once with its own hardware `RenderNode`, and scrolling updates only `translationY` in
 * the draw phase — eliminating mid-drag subcomposition, layout passes, and 50%-threshold hitches.
 */
@Composable
fun VitaVerticalPager(
    state: VitaPagerState,
    pageCount: Int,
    key: (pageIndex: Int) -> Any,
    modifier: Modifier = Modifier,
    pageContent: @Composable (pageIndex: Int) -> Unit,
) {
    val density = LocalDensity.current
    val flingThresholdPxPerSec = remember(density) { with(density) { 320.dp.toPx() } }
    // Plain array avoids triggering recomposition or restarting pointerInput on size changes.
    val viewportHeightHolder = remember { IntArray(1) }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportHeightHolder[0] = it.height }
            .pointerInput(state, flingThresholdPxPerSec) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (state.pageCount <= 1) return@awaitEachGesture

                    var initialOverSlop = 0f
                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        initialOverSlop = overSlop
                    } ?: return@awaitEachGesture

                    val viewportHeight = viewportHeightHolder[0].toFloat()
                    if (viewportHeight <= 0f) return@awaitEachGesture

                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPointerInputChange(down)
                    velocityTracker.addPointerInputChange(drag)

                    state.beginUserDrag()
                    state.applyUserDragDelta(-initialOverSlop / viewportHeight)

                    val completed = verticalDrag(drag.id) { change ->
                        val dy = change.positionChange().y
                        change.consume()
                        velocityTracker.addPointerInputChange(change)
                        state.applyUserDragDelta(-dy / viewportHeight)
                    }

                    val velocityY = if (completed) velocityTracker.calculateVelocity().y else 0f
                    val pageVelocity = -velocityY / viewportHeight
                    state.endUserDrag(
                        pageVelocity = pageVelocity,
                        flingVelocityThresholdPagesPerSec = flingThresholdPxPerSec / viewportHeight,
                    )
                }
            },
    ) {
        for (pageIndex in 0 until pageCount) {
            key(key(pageIndex)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val delta = pageIndex - state.pageOffset
                            val visible = delta > -1.0f && delta < 1.0f
                            translationY = delta * size.height
                            alpha = if (visible) 1f else 0f
                        },
                ) {
                    pageContent(pageIndex)
                }
            }
        }
    }
}
