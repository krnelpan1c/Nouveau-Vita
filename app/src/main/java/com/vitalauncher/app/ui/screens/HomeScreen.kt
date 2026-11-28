package com.vitalauncher.app.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitalauncher.app.LauncherViewModel
import com.vitalauncher.app.PerformanceBooster
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.GridItem
import com.vitalauncher.app.model.Page
import com.vitalauncher.app.model.SlotLocation
import com.vitalauncher.app.ui.components.AppBubble
import com.vitalauncher.app.ui.components.FolderBubble
import com.vitalauncher.app.ui.components.PageGrid
import com.vitalauncher.app.ui.components.PageIndicatorDots
import com.vitalauncher.app.ui.components.STATUS_BAR_HEIGHT
import com.vitalauncher.app.ui.components.VitaPagerState
import com.vitalauncher.app.ui.components.VitaVerticalPager
import com.vitalauncher.app.ui.components.rememberVitaPagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val BUBBLE_SIZE = 92.dp

@Composable
fun HomeScreen(viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    val pageCount = viewModel.pages.size
    val pagerState = rememberVitaPagerState(initialPage = 0, pageCount = pageCount)

    // Observe all scroll/page/focus side-effects via snapshotFlow inside a single LaunchedEffect
    // keyed only on stable references so neither currentPage, isScrollInProgress, nor
    // pendingPageScrollTo is ever read in HomeScreen's composition scope.
    LaunchedEffect(pagerState, viewModel) {
        launch {
            // Drop the initial emission so cold start doesn't chime or overwrite ViewModel state.
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .drop(1)
                .collect { page ->
                    if (!viewModel.isEditMode) {
                        viewModel.onCurrentPageChanged(page, isProgrammatic = pagerState.isProgrammaticScroll)
                        SoundManager.playPageSwipe()
                    }
                }
        }

        launch {
            snapshotFlow { pagerState.isScrollInProgress }
                .distinctUntilChanged()
                .collectLatest { scrolling ->
                    if (!scrolling) return@collectLatest
                    PerformanceBooster.boostScrollTick()
                    while (true) {
                        delay(48)
                        PerformanceBooster.boostScrollTick()
                    }
                }
        }

        launch {
            snapshotFlow { if (viewModel.isEditMode) null else viewModel.pendingPageScrollTo }
                .collect { target ->
                    if (target != null) {
                        pagerState.animateScrollToPage(target)
                        viewModel.clearPendingPageScroll(target)
                    }
                }
        }

        launch {
            // While hidden behind EditModeScreen, silently stay aligned with the edited page so
            // exiting edit mode reveals the exact page the user was viewing.
            snapshotFlow { if (viewModel.isEditMode) viewModel.currentPageIndex else -1 }
                .distinctUntilChanged()
                .collect { editPage ->
                    if (editPage >= 0 && pagerState.currentPage != editPage) {
                        pagerState.scrollToPageImmediate(editPage)
                    }
                }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        VitaVerticalPager(
            state = pagerState,
            pageCount = pageCount,
            key = { pageIndex -> viewModel.pages[pageIndex].id },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = STATUS_BAR_HEIGHT),
        ) { pageIndex ->
            val page = viewModel.pages[pageIndex]
            HomePageContent(
                page = page,
                pageIndex = pageIndex,
                viewModel = viewModel,
            )
        }
        HomePageIndicator(
            pagerState = pagerState,
            pageCount = pageCount,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp),
        )
    }
}

@Composable
private fun HomePageContent(
    page: Page,
    pageIndex: Int,
    viewModel: LauncherViewModel,
) {
    val focusedSlotIndexProvider = remember(viewModel, pageIndex) {
        {
            val focused = viewModel.focusedSlot
            if (viewModel.showFocusRing && focused is SlotLocation.Home && focused.pageIndex == pageIndex) {
                focused.slotIndex
            } else {
                null
            }
        }
    }

    PageGrid(
        modifier = Modifier.padding(horizontal = 24.dp),
        focusedSlotIndexProvider = focusedSlotIndexProvider,
        focusRingDiameter = BUBBLE_SIZE + 2.dp,
    ) { slotIndex ->
        when (val item = page.slots.getOrNull(slotIndex)) {
            is GridItem.Entry -> {
                val app = item.app
                val onClick = remember(viewModel, app) {
                    { viewModel.launchApp(app) }
                }
                val onLongClick = remember(viewModel, pageIndex, slotIndex) {
                    {
                        viewModel.enterEditModeAndGrab(SlotLocation.Home(pageIndex, slotIndex))
                    }
                }
                AppBubble(
                    app = app,
                    size = BUBBLE_SIZE,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            }
            is GridItem.FolderEntry -> {
                val folder = item.folder
                val onClick = remember(viewModel, folder, slotIndex) {
                    { viewModel.showFolder(folder, slotIndex) }
                }
                val onLongClick = remember(viewModel, pageIndex, slotIndex) {
                    {
                        viewModel.enterEditModeAndGrab(SlotLocation.Home(pageIndex, slotIndex))
                    }
                }
                FolderBubble(
                    folder = folder,
                    size = BUBBLE_SIZE,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            }
            null -> {
                val interactionSource = remember { MutableInteractionSource() }
                val onLongClick = remember(viewModel) { { viewModel.enterEditMode() } }
                Box(
                    modifier = Modifier
                        .size(BUBBLE_SIZE)
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {},
                            onLongClick = onLongClick,
                        ),
                )
            }
        }
    }
}

/** Isolates the [VitaPagerState.currentPage] state read so crossing the 50% page threshold
 * mid-swipe only recomposes the tiny indicator dots rather than [HomeScreen]. */
@Composable
private fun HomePageIndicator(pagerState: VitaPagerState, pageCount: Int, modifier: Modifier = Modifier) {
    PageIndicatorDots(
        count = pageCount,
        current = pagerState.currentPage,
        modifier = modifier,
    )
}
