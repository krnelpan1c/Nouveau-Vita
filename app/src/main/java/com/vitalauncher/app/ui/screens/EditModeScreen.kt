package com.vitalauncher.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.LauncherViewModel
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.GridItem
import com.vitalauncher.app.model.SLOTS_PER_PAGE
import com.vitalauncher.app.model.SlotLocation
import com.vitalauncher.app.ui.components.AppBubble
import com.vitalauncher.app.ui.components.BubbleContextMenu
import com.vitalauncher.app.ui.components.FOLDER_PITCH_SCALE
import com.vitalauncher.app.ui.components.FolderBubble
import com.vitalauncher.app.ui.components.PageGrid
import com.vitalauncher.app.ui.components.PageIndicatorDots
import kotlin.math.roundToInt

private val EDIT_BUBBLE_SIZE = 64.dp

/**
 * The "move things around" overview: bubbles with a "..." context button, a bottom hint bar, and
 * (for the home screen only) an add-page button and a white-bordered panel framing it all. Inside
 * a folder there's no frame — the edit affordances sit directly on top of the folder's own giant
 * zoomed-in bubble (see [allowAddPage], which doubles as "is this the home screen" here).
 */
@Composable
fun EditModeScreen(
    viewModel: LauncherViewModel,
    pageCount: Int,
    slotAt: (pageIndex: Int, slotIndex: Int) -> GridItem?,
    locationAt: (pageIndex: Int, slotIndex: Int) -> SlotLocation,
    allowAddPage: Boolean,
    onAddPage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isHomeContext = allowAddPage
    val pagerState = rememberPagerState(
        initialPage = if (isHomeContext) viewModel.currentPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) else 0,
        pageCount = { pageCount },
    )

    BackHandler(enabled = true) { viewModel.cancelGrabOrExit() }

    LaunchedEffect(viewModel.pendingPageScrollTo) {
        val target = viewModel.pendingPageScrollTo ?: return@LaunchedEffect
        if (target in 0 until pageCount) pagerState.animateScrollToPage(target)
        viewModel.clearPendingPageScroll()
    }

    // Keeps the D-pad cursor's page glued to whatever page is actually being viewed here,
    // including plain manual swipes the cursor itself had no part in.
    LaunchedEffect(pagerState.currentPage) {
        if (isHomeContext) viewModel.onCurrentPageChanged(pagerState.currentPage)
    }

    // Root-coordinate center of every slot's "..." badge, keyed by location — kept continuously
    // up to date (not just at tap time) so the context menu can pop from the right spot whether
    // it was opened by touch or by the Y button acting on whatever the D-pad cursor highlights.
    val badgePositions = remember { mutableStateMapOf<SlotLocation, Offset>() }

    // Uses the ViewModel's own cross-context lookup rather than this container's slotAt: grabbed
    // can reference a folder item that's been pulled out onto the home overview but not dropped
    // yet, in which case it's a different context than the one currently on screen here.
    val grabbedItem = viewModel.grabbed?.let { viewModel.itemAt(it) }
    val liftedContent: (@Composable () -> Unit)? = grabbedItem?.let { item ->
        {
            when (item) {
                is GridItem.Entry -> AppBubble(app = item.app, size = EDIT_BUBBLE_SIZE, showLabel = false)
                is GridItem.FolderEntry -> FolderBubble(folder = item.folder, size = EDIT_BUBBLE_SIZE, showLabel = false)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val panelModifier = if (isHomeContext) {
            // Deliberately no background fill here: the real home background (gradient theme or
            // system wallpaper) already sits behind this panel and should show straight through,
            // with only the rounded white border to frame it.
            Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = panelModifier) {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
                var dragEscaped by remember { mutableStateOf(false) }
                val focused = viewModel.focusedSlot
                val focusedSlotIndex = if (viewModel.showFocusRing && focused != null) {
                    (0 until SLOTS_PER_PAGE).firstOrNull { s -> locationAt(pageIndex, s) == focused }
                } else null
                PageGrid(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    focusedSlotIndex = focusedSlotIndex,
                    focusRingDiameter = EDIT_BUBBLE_SIZE + 2.dp,
                    liftedContent = liftedContent,
                    dragEnabled = viewModel.dragArmed,
                    horizontalPitchScale = if (isHomeContext) 1f else FOLDER_PITCH_SCALE,
                    onSlotDragStart = { slotIndex -> viewModel.grabForDrag(locationAt(pageIndex, slotIndex)) },
                    onSlotDragHover = { slotIndex ->
                        if (slotIndex != null) {
                            dragEscaped = false
                            viewModel.updateDragHover(locationAt(pageIndex, slotIndex))
                        } else {
                            dragEscaped = true
                        }
                    },
                    onSlotDragEnd = { verticalEscape ->
                        when {
                            verticalEscape != 0 && isHomeContext -> viewModel.movePageAcrossViaDrag(verticalEscape)
                            dragEscaped && !isHomeContext -> viewModel.endDragWithEscape()
                            else -> viewModel.endDrag()
                        }
                        dragEscaped = false
                    },
                ) { slotIndex ->
                    val location = locationAt(pageIndex, slotIndex)
                    val item = slotAt(pageIndex, slotIndex)
                    val isGrabbed = viewModel.grabbed == location
                    EditableSlot(
                        item = item,
                        isGrabbed = isGrabbed,
                        // Guarded the same way as dragging: a tap landing here can be the tail
                        // end of the very touch that long-pressed this slot into edit mode in
                        // the first place, and it must not be mistaken for a fresh tap.
                        onBodyClick = {
                            if (!viewModel.dragArmed) return@EditableSlot
                            // Nothing being carried and this is a folder: open it (same as a
                            // plain-home tap) instead of grabbing it — grabbing a folder to
                            // reposition it is still available via drag or the D-pad's Move
                            // button. With something already grabbed, tapping a folder should
                            // still drop/merge into it as usual.
                            if (viewModel.grabbed == null && item is GridItem.FolderEntry) {
                                viewModel.showFolder(item.folder, slotIndex)
                            } else {
                                viewModel.onSlotTapped(location)
                            }
                        },
                        onMenuClick = {
                            if (viewModel.dragArmed) {
                                SoundManager.playItemTap()
                                viewModel.openContextMenu(location)
                            }
                        },
                        onBadgePositioned = { pos -> badgePositions[location] = pos },
                    )
                }
            }

            if (pageCount > 1) {
                PageIndicatorDots(
                    count = pageCount,
                    current = pagerState.currentPage,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 6.dp),
                )
            }

            if (allowAddPage && onAddPage != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f))
                        .clickable {
                            SoundManager.playItemTap()
                            onAddPage()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = "Add page", tint = Color.Black)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f))
                        .clickable {
                            SoundManager.playItemTap()
                            viewModel.removePage(pagerState.currentPage)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = "Remove page", tint = Color.Black)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f))
                        .clickable {
                            SoundManager.playItemTap()
                            viewModel.openWallpaperPicker()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = Icons.Filled.Wallpaper, contentDescription = "Change wallpaper", tint = Color.Black)
                }
            }
        }

        BottomHintBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            onBack = {
                SoundManager.playItemTap()
                viewModel.cancelGrabOrExit()
            },
            onMove = { viewModel.toggleLiftAtFocus() },
            onMenu = { viewModel.onYButtonPressed() },
        )

        // Retains the menu's location through its exit animation: contextMenuFor already goes
        // null the instant a dismiss/action fires, and the content below needs a moment longer.
        var lastMenuLocation by remember { mutableStateOf<SlotLocation?>(null) }
        LaunchedEffect(viewModel.contextMenuFor) {
            viewModel.contextMenuFor?.let { lastMenuLocation = it }
        }

        if (viewModel.contextMenuFor != null) {
            val dismissInteractionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(indication = null, interactionSource = dismissInteractionSource) {
                        viewModel.dismissContextMenu()
                    },
            )
        }

        val density = LocalDensity.current
        // Matches BubbleContextMenu's own fixed width — needed here to anchor its top-right
        // corner (rather than its top-left) at the badge, since that's the corner it visually
        // grows from.
        val menuWidthPx = with(density) { 180.dp.roundToPx() }
        val menuAnchor = lastMenuLocation?.let { badgePositions[it] }
        AnimatedVisibility(
            visible = viewModel.contextMenuFor != null,
            enter = fadeIn(tween(120)) + scaleIn(
                initialScale = 0.15f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = tween(180),
            ),
            exit = fadeOut(tween(100)) + scaleOut(
                targetScale = 0.15f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = tween(140),
            ),
            modifier = if (menuAnchor != null) {
                Modifier.offset {
                    IntOffset(
                        (menuAnchor.x.roundToInt() - menuWidthPx).coerceAtLeast(0),
                        menuAnchor.y.roundToInt(),
                    )
                }
            } else {
                Modifier.align(Alignment.Center)
            },
        ) {
            val location = lastMenuLocation
            if (location != null) {
                val (menuPage, menuSlot) = when (location) {
                    is SlotLocation.Home -> location.pageIndex to location.slotIndex
                    is SlotLocation.InFolder -> 0 to location.slotIndex
                }
                val canShowInfo = slotAt(menuPage, menuSlot) is GridItem.Entry
                BubbleContextMenu(
                    canShowInformation = canShowInfo,
                    onCreateFolder = {
                        SoundManager.playItemTap()
                        viewModel.onCreateFolderRequested(location)
                    },
                    onInformation = {
                        SoundManager.playItemTap()
                        viewModel.onInformationRequested(location)
                    },
                )
            }
        }
    }
}

/**
 * A single edit-mode bubble. Deliberately has NO fixed outer size: it's sized by its dominant
 * child (the bubble itself, exactly [EDIT_BUBBLE_SIZE]) so the tracked bounds [PageGrid] uses for
 * the D-pad focus ring line up exactly with the bubble — the "..." badge overflows the top-right
 * corner via [Modifier.offset] without enlarging those bounds. While [isGrabbed], the real bubble
 * rides the floating cursor overlay instead, so this slot shows only a faint empty placeholder.
 */
@Composable
private fun EditableSlot(
    item: GridItem?,
    isGrabbed: Boolean,
    onBodyClick: () -> Unit,
    onMenuClick: () -> Unit,
    onBadgePositioned: (Offset) -> Unit,
) {
    Box {
        if (isGrabbed) {
            Box(
                modifier = Modifier
                    .size(EDIT_BUBBLE_SIZE)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(onClick = onBodyClick),
            )
        } else {
            when (item) {
                is GridItem.Entry -> AppBubble(
                    app = item.app,
                    size = EDIT_BUBBLE_SIZE,
                    showLabel = false,
                    onClick = onBodyClick,
                )
                is GridItem.FolderEntry -> FolderBubble(
                    folder = item.folder,
                    size = EDIT_BUBBLE_SIZE,
                    showLabel = false,
                    onClick = onBodyClick,
                )
                null -> Box(
                    modifier = Modifier
                        .size(EDIT_BUBBLE_SIZE)
                        .clip(CircleShape)
                        .clickable(onClick = onBodyClick),
                )
            }
        }
        if (item != null && !isGrabbed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(22.dp)
                    .onGloballyPositioned { coords ->
                        val topLeft = coords.positionInRoot()
                        onBadgePositioned(topLeft + Offset(coords.size.width / 2f, coords.size.height / 2f))
                    }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f))
                    .clickable(onClick = onMenuClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "Options",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun BottomHintBar(modifier: Modifier = Modifier, onBack: () -> Unit, onMove: () -> Unit, onMenu: () -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        HintItem(glyph = "○", label = "Back", onClick = onBack)
        Box(modifier = Modifier.size(28.dp))
        HintItem(glyph = "△", label = "Menu", onClick = onMenu)
        Box(modifier = Modifier.size(28.dp))
        HintItem(glyph = "□", label = "Move", onClick = onMove)
    }
}

@Composable
private fun HintItem(glyph: String, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text = glyph, color = Color.White, fontSize = 16.sp)
        Text(text = "  $label", color = Color.White, fontSize = 14.sp)
    }
}
