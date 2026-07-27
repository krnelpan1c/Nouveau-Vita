package com.vitalauncher.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.BackgroundMode
import com.vitalauncher.app.model.FolderInfo
import com.vitalauncher.app.model.GridItem
import com.vitalauncher.app.model.NavDirection
import com.vitalauncher.app.model.SlotLocation
import com.vitalauncher.app.ui.components.FolderZoomLayer
import com.vitalauncher.app.ui.components.VitaStatusBar
import com.vitalauncher.app.ui.screens.EditModeScreen
import com.vitalauncher.app.ui.screens.FolderScreen
import com.vitalauncher.app.ui.screens.HomeScreen
import com.vitalauncher.app.ui.screens.WallpaperPickerScreen
import com.vitalauncher.app.ui.theme.vitaSkyBrush
import kotlinx.coroutines.delay
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Forces the ViewModel (and with it, SoundManager.init) to be created synchronously here
        // rather than lazily whenever Compose first reads `viewModel` — that first read happens
        // inside setContent{} below, which isn't composed until the next frame, i.e. after
        // onResume() already ran. SoundManager.onActivityResume() in onResume() needs the
        // MediaPlayer to already exist so it can actually start the menu music on cold start,
        // instead of silently no-oping and only starting it after the next app launch/return.
        viewModel
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // Lets the system-drawn wallpaper (static image or live) show through behind our window
        // whenever the gradient background layer goes transparent for it.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        hideSystemBars()

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                VitaLauncherRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The Home key was pressed again while some other screen was showing: collapse back to home.
        viewModel.resetToHome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        // Fires on cold start too, but the "returned home" chime only actually plays if an app
        // launch armed it — see SoundManager.notifyAppLaunched.
        SoundManager.onActivityResume()
        // Also the reliable point to notice apps installed/uninstalled while we were backgrounded
        // — including returning from the "Information" button's system uninstall screen — since
        // there's no manifest broadcast receiver for package changes; see
        // LauncherViewModel.syncInstalledApps.
        viewModel.syncInstalledApps()
    }

    override fun onPause() {
        super.onPause()
        // A launcher is "backgrounded" every time any other app opens, so this is the reliable
        // point to flush layout/wallpaper changes to disk before the process might be killed —
        // the debounced auto-save in the ViewModel may not have run yet at this point.
        viewModel.persistImmediately()
        SoundManager.onActivityPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        SoundManager.release()
    }

    /** Hides the real status and navigation bars so only our own Vita-style bar shows; a swipe
     * from an edge can still reveal them temporarily (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE),
     * transparent and without the forced black scrim behind the transient nav bar. */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------- Gamepad / D-pad navigation ----------

    private var navDirectionHeld: NavDirection? = null
    private var navHeldSinceMs = 0L
    private var navLastFiredMs = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        directionForKeyCode(event.keyCode)?.let { direction ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> tryNavigate(direction)
                KeyEvent.ACTION_UP -> if (navDirectionHeld == direction) navDirectionHeld = null
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && isConfirmKeyCode(event.keyCode)) {
            viewModel.confirmFocused()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            viewModel.onYButtonPressed()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            viewModel.toggleLiftAtFocus()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            viewModel.cancelGrabOrExit()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK) {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val stickX = event.getAxisValue(MotionEvent.AXIS_X)
            val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
            val x = if (abs(hatX) > 0.5f) hatX else stickX
            val y = if (abs(hatY) > 0.5f) hatY else stickY
            val direction = directionForStick(x, y)
            if (direction != null) tryNavigate(direction) else navDirectionHeld = null
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun directionForKeyCode(keyCode: Int): NavDirection? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> NavDirection.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> NavDirection.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> NavDirection.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> NavDirection.RIGHT
        else -> null
    }

    private fun isConfirmKeyCode(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BUTTON_A ||
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun directionForStick(x: Float, y: Float): NavDirection? {
        val deadzone = 0.5f
        return if (abs(x) >= abs(y)) {
            when {
                x >= deadzone -> NavDirection.RIGHT
                x <= -deadzone -> NavDirection.LEFT
                else -> null
            }
        } else {
            when {
                y >= deadzone -> NavDirection.DOWN
                y <= -deadzone -> NavDirection.UP
                else -> null
            }
        }
    }

    /** Fires [direction] immediately on a fresh press, then throttles repeats while held so the
     * cursor doesn't fly across the grid faster than it can visibly glide. */
    private fun tryNavigate(direction: NavDirection) {
        val now = System.currentTimeMillis()
        if (direction != navDirectionHeld) {
            navDirectionHeld = direction
            navHeldSinceMs = now
            navLastFiredMs = now
            viewModel.moveFocus(direction)
            return
        }
        val requiredGap = if (now - navHeldSinceMs < NAV_INITIAL_DELAY_MS) Long.MAX_VALUE else NAV_REPEAT_MS
        if (now - navLastFiredMs >= requiredGap) {
            navLastFiredMs = now
            viewModel.moveFocus(direction)
        }
    }

    private companion object {
        const val NAV_INITIAL_DELAY_MS = 380L
        const val NAV_REPEAT_MS = 220L
    }
}

private fun homeContainer(viewModel: LauncherViewModel) = EditContainer(
    pageCount = viewModel.pages.size,
    slotAt = { pageIndex, slotIndex -> viewModel.pages.getOrNull(pageIndex)?.slots?.getOrNull(slotIndex) },
    locationAt = { pageIndex, slotIndex -> SlotLocation.Home(pageIndex, slotIndex) },
    allowAddPage = true,
)

private fun folderContainer(folder: FolderInfo) = EditContainer(
    pageCount = 1,
    slotAt = { _, slotIndex ->
        folder.items.getOrNull(slotIndex)?.let { GridItem.Entry(it) }
    },
    locationAt = { _, slotIndex -> SlotLocation.InFolder(folder.id, slotIndex) },
    allowAddPage = false,
)

private data class EditContainer(
    val pageCount: Int,
    val slotAt: (Int, Int) -> GridItem?,
    val locationAt: (Int, Int) -> SlotLocation,
    val allowAddPage: Boolean,
)

/**
 * The whole app is four stacked layers, back to front:
 *  1. A static sky background that never itself moves, scales, or animates.
 *  2. The home icon grid (and, above it, the edit-mode overview), the only layer that scales
 *     when entering/exiting edit mode.
 *  3. Folder content, its own independent layer (no shared instance with the background/status
 *     bar) fronted by a single giant bubble that grows from the tapped icon.
 *  4. The status bar, always on top and never affected by anything happening beneath it.
 */
@Composable
private fun VitaLauncherRoot(viewModel: LauncherViewModel) {
    var lastFolder by remember { mutableStateOf<FolderInfo?>(null) }
    LaunchedEffect(viewModel.openFolder) {
        viewModel.openFolder?.let { lastFolder = it }
    }

    val homeInEditMode = viewModel.isEditMode && viewModel.openFolder == null
    val homeScale by animateFloatAsState(
        targetValue = if (homeInEditMode) 0.92f else 1f,
        animationSpec = tween(260),
        label = "homeZoom",
    )
    // The edit-mode overview and the open-folder layer both redraw their own icons on top of a
    // transparent backdrop, so the plain home grid underneath must fade out in either case —
    // otherwise it shows through as a second, misaligned copy of whatever's on top of it.
    val homeAlpha by animateFloatAsState(
        targetValue = if (homeInEditMode || viewModel.openFolder != null) 0f else 1f,
        animationSpec = tween(200),
        label = "homeFade",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            viewModel.onTouchInteraction()
                        } else {
                            // Every pointer is up: any touch that was already down when edit mode
                            // was entered has now fully released, so a fresh drag can be trusted.
                            viewModel.armDrag()
                        }
                    }
                }
            },
    ) {
        // Layer 1: static background — a solid gradient theme, or transparent so the system
        // wallpaper (drawn behind our window via FLAG_SHOW_WALLPAPER) shows through instead.
        val backgroundBrush = if (viewModel.backgroundMode == BackgroundMode.SYSTEM_WALLPAPER) {
            null
        } else {
            vitaSkyBrush(viewModel.colorThemeIndex)
        }
        Box(
            Modifier.fillMaxSize().let { m ->
                if (backgroundBrush != null) m.background(backgroundBrush) else m
            },
        )

        // Layer 2: home icon grid + edit-mode overview.
        HomeScreen(viewModel, modifier = Modifier.scale(homeScale).alpha(homeAlpha))

        AnimatedVisibility(
            visible = homeInEditMode,
            enter = scaleIn(initialScale = 0.85f, animationSpec = tween(260)) + fadeIn(tween(200)),
            exit = scaleOut(targetScale = 0.85f, animationSpec = tween(220)) + fadeOut(tween(180)),
        ) {
            val container = homeContainer(viewModel)
            EditModeScreen(
                viewModel = viewModel,
                pageCount = container.pageCount,
                slotAt = container.slotAt,
                locationAt = container.locationAt,
                allowAddPage = container.allowAddPage,
                onAddPage = { viewModel.addPage() },
            )
        }

        // Layer 3: folder content, independent of the background/status bar. Its backdrop is a
        // single giant glossy bubble growing from the tapped icon, so only its curved rim shows.
        FolderZoomLayer(
            visible = viewModel.openFolder != null,
            originFraction = viewModel.folderOpenOrigin,
        ) {
            lastFolder?.let { folder ->
                // Same fade used for the home grid underneath its own edit-mode overview: without
                // it, the plain folder view stays fully visible and shows through the overview's
                // transparent backdrop as a second, misaligned copy of the same icons.
                val folderAlpha by animateFloatAsState(
                    targetValue = if (viewModel.isEditMode) 0f else 1f,
                    animationSpec = tween(200),
                    label = "folderFade",
                )
                FolderScreen(viewModel = viewModel, folder = folder, modifier = Modifier.alpha(folderAlpha))

                AnimatedVisibility(
                    visible = viewModel.isEditMode,
                    enter = scaleIn(initialScale = 0.85f, animationSpec = tween(260)) + fadeIn(tween(200)),
                    exit = scaleOut(targetScale = 0.85f, animationSpec = tween(220)) + fadeOut(tween(180)),
                ) {
                    val container = folderContainer(folder)
                    EditModeScreen(
                        viewModel = viewModel,
                        pageCount = container.pageCount,
                        slotAt = container.slotAt,
                        locationAt = container.locationAt,
                        allowAddPage = container.allowAddPage,
                    )
                }
            }
        }

        // Layer 3.5: the fullscreen wallpaper/color picker, opened from edit mode.
        AnimatedVisibility(
            visible = viewModel.wallpaperPickerOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(180)),
        ) {
            WallpaperPickerScreen(viewModel = viewModel)
        }

        // Layer 3.7: brief toast messages (e.g. "That page is full") — was previously tracked in
        // the ViewModel but never actually rendered anywhere, so warnings like a full page or a
        // full folder failed silently with no feedback at all.
        val toastMessage = viewModel.toast
        LaunchedEffect(toastMessage) {
            if (toastMessage == null) return@LaunchedEffect
            delay(1800)
            viewModel.consumeToast()
        }
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(text = toastMessage.orEmpty(), color = Color.White, fontSize = 14.sp)
            }
        }

        // Layer 4: status bar, fixed on top, always present, never scaled or animated.
        VitaStatusBar()
    }
}
