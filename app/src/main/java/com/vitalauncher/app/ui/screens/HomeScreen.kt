package com.vitalauncher.app.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vitalauncher.app.LauncherViewModel
import com.vitalauncher.app.PerformanceBooster
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.AppRepository
import com.vitalauncher.app.model.GridItem
import com.vitalauncher.app.model.SlotLocation
import com.vitalauncher.app.ui.components.AppBubble
import com.vitalauncher.app.ui.components.FolderBubble
import com.vitalauncher.app.ui.components.PageGrid
import com.vitalauncher.app.ui.components.PageIndicatorDots
import com.vitalauncher.app.ui.components.STATUS_BAR_HEIGHT
import kotlinx.coroutines.delay

private val BUBBLE_SIZE = 92.dp

@Composable
fun HomeScreen(viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { viewModel.pages.size })

    // Suppresses the swipe chime on the very first composition, where currentPage "changes" from
    // nothing to the initial page rather than from an actual swipe/navigation.
    val isFirstPageRender = remember { mutableStateOf(true) }
    LaunchedEffect(pagerState.currentPage) {
        viewModel.onCurrentPageChanged(pagerState.currentPage)
        if (isFirstPageRender.value) {
            isFirstPageRender.value = false
        } else {
            SoundManager.playPageSwipe()
        }
    }

    // Keeps both UI thread and RenderThread boosted via ADPF while a swipe or fling is in flight,
    // since Qualcomm's View-only SilkyScrolls heuristic doesn't detect Compose VerticalPager.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) return@LaunchedEffect
        PerformanceBooster.boostScrollTick()
        while (true) {
            delay(48)
            PerformanceBooster.boostScrollTick()
        }
    }

    LaunchedEffect(viewModel.pendingPageScrollTo) {
        val target = viewModel.pendingPageScrollTo ?: return@LaunchedEffect
        pagerState.animateScrollToPage(target)
        viewModel.clearPendingPageScroll()
    }

    Box(modifier = modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            beyondBoundsPageCount = 1,
            key = { pageIndex -> viewModel.pages[pageIndex].id },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = STATUS_BAR_HEIGHT),
        ) { pageIndex ->
            val page = viewModel.pages[pageIndex]
            val focused = viewModel.focusedSlot
            val focusedSlotIndex = if (viewModel.showFocusRing && focused is SlotLocation.Home && focused.pageIndex == pageIndex) {
                focused.slotIndex
            } else null
            PageGrid(
                // Give each page its own hardware RenderNode display list so VerticalPager
                // translations move the cached page layer rather than re-recording 10 bubbles.
                modifier = Modifier
                    .graphicsLayer()
                    .padding(horizontal = 24.dp),
                focusedSlotIndex = focusedSlotIndex,
                focusRingDiameter = BUBBLE_SIZE + 2.dp,
            ) { slotIndex ->
                when (val item = page.slots.getOrNull(slotIndex)) {
                    is GridItem.Entry -> AppBubble(
                        app = item.app,
                        size = BUBBLE_SIZE,
                        onClick = {
                            SoundManager.notifyAppLaunched()
                            AppRepository.launch(context, item.app)
                        },
                        onLongClick = {
                            viewModel.enterEditModeAndGrab(SlotLocation.Home(pageIndex, slotIndex))
                        },
                    )
                    is GridItem.FolderEntry -> FolderBubble(
                        folder = item.folder,
                        size = BUBBLE_SIZE,
                        onClick = { viewModel.showFolder(item.folder, slotIndex) },
                        onLongClick = {
                            viewModel.enterEditModeAndGrab(SlotLocation.Home(pageIndex, slotIndex))
                        },
                    )
                    null -> {
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(BUBBLE_SIZE)
                                .combinedClickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = {},
                                    onLongClick = { viewModel.enterEditMode() },
                                ),
                        )
                    }
                }
            }
        }
        HomePageIndicator(
            pagerState = pagerState,
            pageCount = viewModel.pages.size,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp),
        )
    }
}

/** Isolates the [PagerState.currentPage] state read so crossing the 50% page threshold mid-swipe
 * only recomposes the indicator dots rather than the outer [HomeScreen] / [VerticalPager] scope. */
@Composable
private fun HomePageIndicator(pagerState: PagerState, pageCount: Int, modifier: Modifier = Modifier) {
    PageIndicatorDots(
        count = pageCount,
        current = pagerState.currentPage,
        modifier = modifier,
    )
}
