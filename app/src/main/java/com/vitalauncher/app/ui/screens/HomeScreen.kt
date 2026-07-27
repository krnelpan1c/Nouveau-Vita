package com.vitalauncher.app.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vitalauncher.app.LauncherViewModel
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.AppRepository
import com.vitalauncher.app.model.GridItem
import com.vitalauncher.app.model.SlotLocation
import com.vitalauncher.app.ui.components.AppBubble
import com.vitalauncher.app.ui.components.FolderBubble
import com.vitalauncher.app.ui.components.PageGrid
import com.vitalauncher.app.ui.components.PageIndicatorDots
import com.vitalauncher.app.ui.components.STATUS_BAR_HEIGHT

private val BUBBLE_SIZE = 92.dp

@Composable
fun HomeScreen(viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { viewModel.pages.size })
    val swipeProgress = pagerState.currentPageOffsetFraction

    LaunchedEffect(pagerState.currentPage) {
        viewModel.onCurrentPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(viewModel.pendingPageScrollTo) {
        val target = viewModel.pendingPageScrollTo ?: return@LaunchedEffect
        pagerState.animateScrollToPage(target)
        viewModel.clearPendingPageScroll()
    }

    Box(modifier = modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
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
                    modifier = Modifier.padding(horizontal = 24.dp),
                    focusedSlotIndex = focusedSlotIndex,
                    focusRingDiameter = BUBBLE_SIZE + 2.dp,
                ) { slotIndex ->
                    val item = page.slots.getOrNull(slotIndex)
                    val interactionSource = remember { MutableInteractionSource() }
                    when (item) {
                        is GridItem.Entry -> AppBubble(
                            app = item.app,
                            size = BUBBLE_SIZE,
                            swipeProgress = swipeProgress,
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
                            swipeProgress = swipeProgress,
                            onClick = { viewModel.showFolder(item.folder, slotIndex) },
                            onLongClick = {
                                viewModel.enterEditModeAndGrab(SlotLocation.Home(pageIndex, slotIndex))
                            },
                        )
                        null -> Box(
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
        PageIndicatorDots(
            count = viewModel.pages.size,
            current = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp),
        )
    }
}
