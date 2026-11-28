package com.vitalauncher.app

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.AppInfo
import com.vitalauncher.app.model.AppRepository
import com.vitalauncher.app.model.BackgroundMode
import com.vitalauncher.app.model.FolderInfo
import com.vitalauncher.app.model.GridItem
import com.vitalauncher.app.model.LauncherStateStore
import com.vitalauncher.app.model.MAX_FOLDER_ITEMS
import com.vitalauncher.app.model.NavDirection
import com.vitalauncher.app.model.Page
import com.vitalauncher.app.model.PendingDrop
import com.vitalauncher.app.model.ROW_SLOT_COUNTS
import com.vitalauncher.app.model.SLOTS_PER_PAGE
import com.vitalauncher.app.model.SlotLocation
import com.vitalauncher.app.recents.ShizukuRecentsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_RECENT_APPS = 6
private const val DROP_SAFETY_TIMEOUT_MS = 450L

@Stable
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    /** Home screen pages. Always has at least one page. */
    val pages: SnapshotStateList<Page> = mutableStateListOf(Page())

    /** Up to 6 most recently opened apps, ordered most recent (index 0) to oldest. */
    val recentApps: SnapshotStateList<AppInfo> = mutableStateListOf()

    private var appsByPackage: Map<String, AppInfo> = emptyMap()

    var currentPageIndex by mutableStateOf(0)
        private set

    var isLoading by mutableStateOf(true)
        private set

    // --- Folder viewer state ---
    var openFolder by mutableStateOf<FolderInfo?>(null)
        private set

    /** Home-grid slot index (0..9) of [openFolder], used by FolderZoomLayer to compute exact pixel
     * start/end coordinates for both normal Home mode and Edit mode. */
    var folderOriginSlotIndex by mutableStateOf(4)
        private set

    // --- Edit mode state ---
    var isEditMode by mutableStateOf(false)
        private set
    var grabbed by mutableStateOf<SlotLocation?>(null)
        private set
    var grabSessionId by mutableStateOf(0L)
        private set
    var pendingDrop by mutableStateOf<PendingDrop?>(null)
        private set
    var contextMenuFor by mutableStateOf<SlotLocation?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    private var dropSeq = 0L
    private var dropSafetyJob: Job? = null

    // --- Background / wallpaper ---
    var wallpaperPickerOpen by mutableStateOf(false)
        private set
    var backgroundMode by mutableStateOf(BackgroundMode.GRADIENT)
        private set
    var colorThemeIndex by mutableStateOf(0)
        private set

    fun openWallpaperPicker() {
        wallpaperPickerOpen = true
    }

    fun closeWallpaperPicker() {
        wallpaperPickerOpen = false
    }

    /** Switches the home background to a solid gradient theme (see [com.vitalauncher.app.ui.theme.VitaColorThemes]). */
    fun setColorTheme(index: Int) {
        colorThemeIndex = index
        backgroundMode = BackgroundMode.GRADIENT
    }

    /** Switches the home background to whatever the system wallpaper is (static image or live),
     * drawn by the OS itself behind our window rather than by us. */
    fun useSystemWallpaper() {
        backgroundMode = BackgroundMode.SYSTEM_WALLPAPER
    }

    // --- Gamepad / D-pad focus navigation ---
    var focusedSlot by mutableStateOf<SlotLocation?>(null)
        private set
    var showFocusRing by mutableStateOf(false)
        private set
    var pendingPageScrollTo by mutableStateOf<Int?>(null)
        private set

    /** False right after entering edit mode until the finger that triggered it (if any) lifts —
     * otherwise that same still-held touch gets picked up as an instant drag by the edit-mode
     * grid, which sits at a different scale/layout than the home screen it was long-pressed on. */
    var dragArmed by mutableStateOf(true)
        private set

    fun armDrag() {
        if (!dragArmed) dragArmed = true
    }

    init {
        SoundManager.init(application)
        viewModelScope.launch {
            val apps = AppRepository.loadLaunchableApps(application)
            indexLoadedApps(apps)
            val restored = LauncherStateStore.load(application, apps)
            if (restored != null) applyRestoredState(restored, apps) else populatePages(apps)
            isLoading = false
            refreshRecentApps()
            observeStateForPersistence()
        }
    }

    override fun onCleared() {
        super.onCleared()
        ShizukuRecentsClient.release()
    }

    private fun indexLoadedApps(apps: List<AppInfo>) {
        val byPkg = LinkedHashMap<String, AppInfo>(apps.size)
        for (app in apps) {
            if (!app.isShortcut && app.packageName !in byPkg) {
                byPkg[app.packageName] = app
            }
        }
        appsByPackage = byPkg
    }

    /** Launches [app], plays the launch sound, and bumps it to the front of [recentApps]. */
    fun launchApp(app: AppInfo) {
        SoundManager.notifyAppLaunched()
        recordRecentApp(app)
        AppRepository.launch(getApplication(), app)
    }

    private fun recordRecentApp(app: AppInfo) {
        recentApps.removeAll { it.key == app.key }
        recentApps.add(0, app)
        while (recentApps.size > MAX_RECENT_APPS) {
            recentApps.removeAt(recentApps.lastIndex)
        }
    }

    /**
     * When Shizuku is running and already authorized by the user (without ever prompting), syncs
     * [recentApps] with Android's live Recents stack so cleared apps disappear from the status bar.
     * Silently keeps the launcher-tracked MRU list when Shizuku is unavailable or unauthorized.
     */
    fun refreshRecentApps() {
        if (isLoading) return
        viewModelScope.launch {
            val livePackages = ShizukuRecentsClient.queryRecentPackages(getApplication(), maxCount = 24)
                ?: return@launch
            val ownPkg = getApplication<Application>().packageName
            val byPkg = appsByPackage
            val resolved = livePackages
                .asSequence()
                .filter { it != ownPkg }
                .mapNotNull { byPkg[it] }
                .distinctBy { it.key }
                .take(MAX_RECENT_APPS)
                .toList()
            if (recentApps.size != resolved.size ||
                recentApps.indices.any { recentApps[it].key != resolved[it].key }
            ) {
                recentApps.clear()
                recentApps.addAll(resolved)
            }
        }
    }

    private fun populatePages(apps: List<AppInfo>) {
        pages.clear()
        if (apps.isEmpty()) {
            pages.add(Page())
            return
        }
        apps.chunked(SLOTS_PER_PAGE).forEach { chunk ->
            val page = Page()
            chunk.forEachIndexed { i, app -> page.slots[i] = GridItem.Entry(app) }
            pages.add(page)
        }
    }

    private fun applyRestoredState(restored: LauncherStateStore.Restored, apps: List<AppInfo>) {
        pages.clear()
        for (slots in restored.pages) {
            val page = Page()
            slots.forEachIndexed { i, item -> page.slots[i] = item }
            pages.add(page)
        }
        if (pages.isEmpty()) pages.add(Page())
        backgroundMode = restored.backgroundMode
        colorThemeIndex = restored.colorThemeIndex

        // Apps installed since the layout was last saved aren't referenced anywhere in it —
        // append them to the first free slot instead of letting them silently disappear.
        placeNewlyInstalledApps(apps)
    }

    /** Re-queries the package manager and reconciles the grid against whatever's actually
     * installed right now: slots (and folder entries) for apps uninstalled since the last check
     * are cleared out — a folder emptied by this is pruned exactly like [pruneFolderIfEmpty] does
     * for a manual removal — and any newly installed app is appended to the home screen. Called
     * from [MainActivity.onResume], since returning from the system "app info"/uninstall screen
     * (reached via [onInformationRequested]) or from an install flow (e.g. the Play Store) both
     * land back here — this is the only reliable point to notice either change. */
    private var lastSyncSignature: Set<String>? = null

    fun syncInstalledApps() {
        if (isLoading) return
        viewModelScope.launch {
            // Returning home from another app is the most common trigger, and decoding every app's
            // icon on each return competes with the UI for CPU/memory while the home screen is
            // animating back in. Skip the full reload unless the installed set actually changed.
            val signature = AppRepository.installedSignature(getApplication())
            if (signature == lastSyncSignature) return@launch
            lastSyncSignature = signature
            val apps = AppRepository.loadLaunchableApps(getApplication())
            indexLoadedApps(apps)
            val appsByKey = apps.associateBy { it.key }
            val installedKeys = appsByKey.keys
            // Shortcuts can only be read while we're the default launcher; otherwise keep the
            // ones already placed rather than mistaking "can't read" for "was removed".
            val keepShortcuts = !AppRepository.hasShortcutHostPermission(getApplication())
            fun isGone(app: AppInfo) = app.key !in installedKeys && !(app.isShortcut && keepShortcuts)

            for (idx in recentApps.indices.reversed()) {
                val existing = recentApps[idx]
                if (isGone(existing)) {
                    recentApps.removeAt(idx)
                } else {
                    appsByKey[existing.key]?.let { recentApps[idx] = it }
                }
            }

            for (page in pages) {
                for (i in page.slots.indices) {
                    when (val slot = page.slots[i]) {
                        is GridItem.Entry -> if (isGone(slot.app)) page.slots[i] = null
                        is GridItem.FolderEntry -> {
                            val folder = slot.folder
                            for (j in folder.items.indices) {
                                val app = folder.items[j]
                                if (app != null && isGone(app)) folder.items[j] = null
                            }
                            if (folder.isEmpty()) {
                                if (openFolder?.id == folder.id) closeFolder()
                                page.slots[i] = null
                            }
                        }
                        null -> {}
                    }
                }
            }

            placeNewlyInstalledApps(apps)
            refreshRecentApps()
        }
    }

    /** Adds every app in [apps] that isn't already placed anywhere in the grid (home pages or
     * folders) to the first free home slot. */
    private fun placeNewlyInstalledApps(apps: List<AppInfo>) {
        val placedKeys = mutableSetOf<String>()
        for (page in pages) {
            for (slot in page.slots) {
                when (slot) {
                    is GridItem.Entry -> placedKeys.add(slot.app.key)
                    is GridItem.FolderEntry -> slot.folder.items.forEach { it?.let { app -> placedKeys.add(app.key) } }
                    null -> {}
                }
            }
        }
        apps.filter { it.key !in placedKeys }.forEach { placeOnHome(it) }
    }

    /** Watches every bit of state that defines the persisted layout (slot contents, folder
     * names/items, background choice) and writes it to disk shortly after it settles — so manual
     * save calls aren't needed at each of the many places that can mutate it. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeStateForPersistence() {
        viewModelScope.launch {
            snapshotFlow { LauncherStateStore.serialize(pages.toList(), backgroundMode, colorThemeIndex) }
                .debounce(400)
                .distinctUntilChanged()
                .collect { json ->
                    withContext(Dispatchers.IO) { LauncherStateStore.save(getApplication(), json) }
                    lastSavedJson = json
                }
        }
    }

    /** Synchronously flushes the current layout to disk, bypassing the debounce — called when the
     * activity is about to go into the background, since the process may be killed shortly after
     * without the debounced write ever getting a chance to run. */
    @Volatile private var lastSavedJson: String? = null

    fun persistImmediately() {
        if (isLoading) return
        val json = LauncherStateStore.serialize(pages.toList(), backgroundMode, colorThemeIndex)
        // This runs in onPause, i.e. right as another app is launching, so skip the disk write
        // when nothing changed and otherwise keep the (fsync-ing) commit off the main thread.
        if (json == lastSavedJson) return
        lastSavedJson = json
        val app = getApplication<Application>()
        Thread { LauncherStateStore.save(app, json) }.start()
    }

    // ---------- Navigation ----------

    fun showFolder(folder: FolderInfo, originSlotIndex: Int? = null) {
        finalizePendingDrop()
        openFolder = folder
        focusedSlot = null
        folderOriginSlotIndex = originSlotIndex ?: findFolderSlotIndex(folder.id) ?: 4
    }

    private fun findFolderSlotIndex(folderId: String): Int? {
        for (page in pages) {
            val idx = page.slots.indexOfFirst { it is GridItem.FolderEntry && it.folder.id == folderId }
            if (idx >= 0) return idx
        }
        return null
    }

    fun closeFolder() {
        finalizePendingDrop()
        openFolder?.let { folder ->
            findFolderSlotIndex(folder.id)?.let { folderOriginSlotIndex = it }
        }
        openFolder = null
        focusedSlot = null
    }

    /** Resets all transient UI state back to the plain home screen (used on Home-key re-press). */
    fun resetToHome() {
        exitEditMode()
        closeFolder()
        focusedSlot = null
        showFocusRing = false
        wallpaperPickerOpen = false
    }

    // ---------- Edit mode ----------

    /** Commits any in-flight drop animation immediately so rapid inputs are never lost or delayed. */
    fun finalizePendingDrop(id: Long? = null) {
        val drop = pendingDrop ?: return
        if (id != null && drop.id != id) return
        dropSafetyJob?.cancel()
        dropSafetyJob = null
        pendingDrop = null
        if (!drop.isCancel && drop.source != drop.target) {
            performMoveOrMerge(drop.source, drop.target)
        }
    }

    private fun startPendingDrop(
        item: GridItem,
        source: SlotLocation,
        visualStart: SlotLocation,
        target: SlotLocation,
        isCancel: Boolean,
    ) {
        val id = ++dropSeq
        val drop = PendingDrop(
            id = id,
            sessionId = grabSessionId,
            item = item,
            source = source,
            visualStart = visualStart,
            target = target,
            isCancel = isCancel,
        )
        pendingDrop = drop
        dropSafetyJob?.cancel()
        dropSafetyJob = viewModelScope.launch {
            delay(DROP_SAFETY_TIMEOUT_MS)
            finalizePendingDrop(id)
        }
    }

    fun enterEditModeAndGrab(location: SlotLocation) {
        finalizePendingDrop()
        isEditMode = true
        grabSessionId++
        grabbed = location
        focusedSlot = location
        showFocusRing = true
        contextMenuFor = null
        dragArmed = false
        SoundManager.playEditMode()
    }

    fun enterEditMode() {
        finalizePendingDrop()
        isEditMode = true
        grabbed = null
        contextMenuFor = null
        dragArmed = false
        SoundManager.playEditMode()
    }

    fun exitEditMode() {
        finalizePendingDrop()
        if (isEditMode) SoundManager.playExitEditMode()
        isEditMode = false
        grabbed = null
        contextMenuFor = null
    }

    /** Triangle/Y: outside edit mode this is the entry point into it (same as a long-press);
     * inside edit mode it instead opens the context menu for whatever the cursor is currently
     * highlighting, the gamepad equivalent of tapping a bubble's "..." badge. */
    fun onYButtonPressed() {
        finalizePendingDrop()
        if (!isEditMode) {
            enterEditMode()
            return
        }
        val location = focusedSlot ?: return
        if (slotAt(location) != null) openContextMenu(location)
    }

    /** The Square/X "lift" button: grabs whatever the cursor is on, or drops/merges a held item
     * at the cursor's current position — the gamepad equivalent of tapping a slot twice. */
    fun toggleLiftAtFocus() {
        if (!isEditMode) return
        val location = focusedSlot ?: return
        onSlotTapped(location)
    }

    /** Handles a tap on a slot while in edit mode: lifts an item, or glides/drops a held one. */
    fun onSlotTapped(location: SlotLocation) {
        finalizePendingDrop()
        val current = grabbed
        if (current == null) {
            if (slotAt(location) != null) {
                grabSessionId++
                grabbed = location
                // Keep focusedSlot synced (without turning on the cyan D-pad ring) so switching to
                // D-pad mid-grab smoothly continues moving from this lifted slot.
                focusedSlot = location
            }
            return
        }
        val item = slotAt(current)
        if (item == null) {
            grabbed = null
            return
        }
        val visualStart = if (showFocusRing && focusedSlot != null) focusedSlot!! else current
        grabbed = null
        startPendingDrop(
            item = item,
            source = current,
            visualStart = visualStart,
            target = location,
            isCancel = (current == location),
        )
    }

    /** Starts a touch press-and-hold drag on [location] (a no-op if it's empty). */
    fun grabForDrag(location: SlotLocation) {
        finalizePendingDrop()
        if (slotAt(location) == null) return
        grabSessionId++
        grabbed = location
        focusedSlot = location
        showFocusRing = true
    }

    /** Moves the cursor to follow a live touch drag, without performing a move/merge yet. */
    fun updateDragHover(location: SlotLocation) {
        focusedSlot = location
        showFocusRing = true
    }

    /** Ends a touch drag: drops/merges the held item at the cursor's current position. */
    fun endDrag() {
        val location = focusedSlot ?: run { grabbed = null; return }
        onSlotTapped(location)
    }

    /** Ends a touch drag that was released outside the folder's bounds — hands the held item off
     * to the home overview still grabbed (see [pullFromFolderKeepGrabbed]) rather than finalizing
     * a drop, so it doesn't just vanish if this is the only signal the gesture ever sends. */
    fun endDragWithEscape() {
        finalizePendingDrop()
        if (!pullFromFolderKeepGrabbed()) {
            grabbed = null
            focusedSlot = null
            closeFolder()
        }
    }

    /** The Circle/B "back" button and the hardware back gesture both funnel through here, in
     * order of what's currently "innermost": dismiss a context menu, release whatever's grabbed,
     * back out of an open folder (to the home overview, staying in edit mode if it was already
     * active), and only then actually exit edit mode. */
    fun cancelGrabOrExit() {
        finalizePendingDrop()
        val current = grabbed
        when {
            contextMenuFor != null -> contextMenuFor = null
            current != null -> {
                val item = slotAt(current)
                val visualStart = if (showFocusRing && focusedSlot != null) focusedSlot!! else current
                grabbed = null
                val targetVisible = when (current) {
                    is SlotLocation.Home -> openFolder == null && current.pageIndex == currentPageIndex
                    is SlotLocation.InFolder -> openFolder?.id == current.folderId
                }
                if (item != null && targetVisible) {
                    startPendingDrop(
                        item = item,
                        source = current,
                        visualStart = visualStart,
                        target = current,
                        isCancel = true,
                    )
                }
            }
            openFolder != null -> closeFolder()
            else -> exitEditMode()
        }
    }

    fun openContextMenu(location: SlotLocation) {
        finalizePendingDrop()
        contextMenuFor = location
    }

    fun dismissContextMenu() {
        contextMenuFor = null
    }

    /** Wraps the tapped app in a brand-new folder, in place, immediately — no second tap needed.
     * More apps can be dropped onto the resulting folder afterwards the usual way. */
    fun onCreateFolderRequested(location: SlotLocation) {
        finalizePendingDrop()
        contextMenuFor = null
        val app = (slotAt(location) as? GridItem.Entry)?.app ?: return
        val folder = FolderInfo().apply { items[0] = app }
        setSlot(location, GridItem.FolderEntry(folder))
        grabbed = null
    }

    fun onInformationRequested(location: SlotLocation) {
        finalizePendingDrop()
        contextMenuFor = null
        val item = slotAt(location)
        val app = (item as? GridItem.Entry)?.app ?: return
        if (app.isShortcut) {
            // Shortcuts have no app-info screen; this row is "Remove" for them instead.
            AppRepository.unpinShortcut(getApplication(), app)
            setSlot(location, null)
            grabbed = null
            return
        }
        AppRepository.openAppInfo(getApplication(), app)
    }

    fun addPage() {
        pages.add(Page())
    }

    fun consumeToast() {
        toast = null
    }

    // ---------- Gamepad / D-pad focus navigation ----------

    /** Touching the screen hands control back to the finger; the cyan cursor disappears — unless
     * it's currently the only thing showing an item that's actively grabbed (e.g. the user long-
     * pressed an icon, then tapped some other on-screen control like the wallpaper button):
     * hiding it then would make the held icon look like it vanished, with no visual trace that
     * it's still picked up. */
    fun onTouchInteraction() {
        if (grabbed == null && pendingDrop == null && showFocusRing) showFocusRing = false
    }

    fun clearPendingPageScroll(target: Int? = null) {
        if (target == null || pendingPageScrollTo == target) {
            pendingPageScrollTo = null
        }
    }

    /** Called whenever the home pager's visible page changes from a manual user swipe. Keeps the
     * D-pad cursor's page in lock-step with whatever page is actually on screen — otherwise
     * swiping away from the cursor's page (without touching the cursor itself) leaves it stranded
     * there, and the next D-pad press yanks the view back to that stale page instead of continuing
     * from the one the user is currently looking at.
     *
     * Ignored when [isProgrammatic] is true, because D-pad/drag page transitions already set
     * [currentPageIndex] and [focusedSlot] to the exact destination and must not be clobbered by
     * intermediate pages crossed during rapid multi-page navigation. */
    fun onCurrentPageChanged(pageIndex: Int, isProgrammatic: Boolean = false) {
        if (isProgrammatic) return
        currentPageIndex = pageIndex
        val current = focusedSlot
        if (current is SlotLocation.Home && current.pageIndex != pageIndex) {
            focusedSlot = current.copy(pageIndex = pageIndex)
        }
    }

    /** Moves the D-pad/stick cursor one step, crossing onto the next/previous page when it runs
     * off the top or bottom row (home grids only; a folder has just the one page).
     *
     * With nothing grabbed, blank slots are transparent to navigation — the cursor skips over
     * them to the next actual icon instead of stopping on empty space or treating a gap as a dead
     * end. While carrying something, though, that skipping is turned off: the whole point of
     * picking an item up is often to place it into a *specific* blank slot for a custom layout,
     * so the cursor (and the item riding it) needs to be able to land on every cell freely. */
    fun moveFocus(direction: NavDirection) {
        finalizePendingDrop()
        showFocusRing = true
        val current = focusedSlot ?: run {
            focusedSlot = defaultFocusSlot()
            return
        }
        val skipBlanks = grabbed == null

        when (direction) {
            NavDirection.LEFT, NavDirection.RIGHT -> {
                val delta = if (direction == NavDirection.RIGHT) 1 else -1
                val row = rowOf(current.slotIndexInGrid)
                val start = rowStart(row)
                val count = ROW_SLOT_COUNTS[row]
                var pos = (current.slotIndexInGrid - start) + delta
                if (skipBlanks) {
                    while (pos in 0 until count && slotAt(current.withSlotIndex(start + pos)) == null) {
                        pos += delta
                    }
                }
                if (pos !in 0 until count) {
                    if (grabbed != null && current is SlotLocation.InFolder) tryEjectFromFolder()
                    return
                }
                focusedSlot = current.withSlotIndex(start + pos)
            }
            NavDirection.UP, NavDirection.DOWN -> {
                val rowDelta = if (direction == NavDirection.DOWN) 1 else -1
                val row = rowOf(current.slotIndexInGrid)
                val fraction = colFractionOf(current.slotIndexInGrid)
                val newRow = row + rowDelta

                val landingSlot: Int? = if (!skipBlanks) {
                    if (newRow in ROW_SLOT_COUNTS.indices) slotAtRowAndFraction(newRow, fraction) else null
                } else {
                    var searchRow = newRow
                    var found: Int? = null
                    while (searchRow in ROW_SLOT_COUNTS.indices) {
                        found = nearestOccupiedInRow(current, searchRow, fraction)
                        if (found != null) break
                        searchRow += rowDelta
                    }
                    found
                }

                if (landingSlot != null) {
                    focusedSlot = current.withSlotIndex(landingSlot)
                } else if (current is SlotLocation.Home) {
                    val targetPage = current.pageIndex + rowDelta
                    if (targetPage in pages.indices) {
                        val startRow = if (rowDelta > 0) 0 else ROW_SLOT_COUNTS.lastIndex
                        val newSlotIndex = if (!skipBlanks) {
                            slotAtRowAndFraction(startRow, fraction)
                        } else {
                            val probe = SlotLocation.Home(targetPage, 0)
                            var searchRow = startRow
                            var found: Int? = null
                            while (searchRow in ROW_SLOT_COUNTS.indices) {
                                found = nearestOccupiedInRow(probe, searchRow, fraction)
                                if (found != null) break
                                searchRow += rowDelta
                            }
                            found ?: slotAtRowAndFraction(startRow, fraction)
                        }
                        focusedSlot = SlotLocation.Home(targetPage, newSlotIndex)
                        currentPageIndex = targetPage
                        pendingPageScrollTo = targetPage
                    }
                } else if (grabbed != null && current is SlotLocation.InFolder) {
                    // A folder has just the one page/row-set: running off the top or bottom row
                    // while carrying something means "carry it out of the folder".
                    tryEjectFromFolder()
                }
            }
        }
    }

    /** The occupied slot in [row] closest to [fraction] (column position, 0f..1f, within a row),
     * or null if that whole row is blank. Checked at the exact fractional column first, then
     * outward in both directions, so the cursor lands on whichever icon is nearest to where it
     * "should" be rather than always snapping to one side. [probe] only supplies which page/folder
     * to look in — its own slot index is ignored. */
    private fun nearestOccupiedInRow(probe: SlotLocation, row: Int, fraction: Float): Int? {
        val start = rowStart(row)
        val count = ROW_SLOT_COUNTS[row]
        val preferred = slotAtRowAndFraction(row, fraction)
        if (slotAt(probe.withSlotIndex(preferred)) != null) return preferred
        for (dist in 1 until count) {
            val right = preferred + dist
            if (right < start + count && slotAt(probe.withSlotIndex(right)) != null) return right
            val left = preferred - dist
            if (left >= start && slotAt(probe.withSlotIndex(left)) != null) return left
        }
        return null
    }

    /** Pulls whatever's currently held out of its folder and onto the home overview, still
     * grabbed there — the "drag or D-pad past the rim" escape. Rather than searching every page
     * for open space and finalizing the drop immediately (which could land it somewhere distant,
     * or force it onto an already-full page), it's relocated to a real home slot but left grabbed
     * exactly as if picked up from the home screen directly, so the user keeps full control of
     * where it finally ends up — including carrying it to a different page — before dropping it
     * themselves through the normal move/merge flow. */
    private fun tryEjectFromFolder() {
        pullFromFolderKeepGrabbed()
    }

    private fun pullFromFolderKeepGrabbed(): Boolean {
        val heldLocation = grabbed as? SlotLocation.InFolder ?: return false
        val folder = findFolder(heldLocation.folderId) ?: return false
        val app = folder.items.getOrNull(heldLocation.slotIndex) ?: return false

        // Where the folder itself sits on the home screen — if pulling this out empties it, that
        // slot is exactly where the item should land, rather than the folder sitting there empty
        // (still taking up the space) while the item goes off looking for somewhere else to go.
        var folderPageIndex = -1
        var folderSlotIndex = -1
        for ((pIdx, page) in pages.withIndex()) {
            val sIdx = page.slots.indexOfFirst { it is GridItem.FolderEntry && it.folder.id == folder.id }
            if (sIdx >= 0) {
                folderPageIndex = pIdx
                folderSlotIndex = sIdx
                break
            }
        }

        folder.items[heldLocation.slotIndex] = null
        val folderNowEmpty = folder.isEmpty()
        if (folderNowEmpty && folderPageIndex >= 0) {
            pages[folderPageIndex].slots[folderSlotIndex] = null
        }

        val (targetPage, targetSlot) = when {
            folderNowEmpty && folderPageIndex >= 0 -> folderPageIndex to folderSlotIndex
            else -> {
                val currentPageIdx = currentPageIndex.coerceIn(pages.indices)
                val freeOnCurrent = pages[currentPageIdx].firstEmptySlot()
                when {
                    freeOnCurrent >= 0 -> currentPageIdx to freeOnCurrent
                    // Current page is full and the folder still has other items in it (so its
                    // slot didn't just free up): fall back to any page with room rather than
                    // overwriting whatever already occupies a full page's slots.
                    else -> {
                        val fallbackPage = pages.indexOfFirst { it.firstEmptySlot() >= 0 }
                        if (fallbackPage >= 0) {
                            fallbackPage to pages[fallbackPage].firstEmptySlot()
                        } else {
                            pages.add(Page())
                            (pages.size - 1) to 0
                        }
                    }
                }
            }
        }

        pages[targetPage].slots[targetSlot] = GridItem.Entry(app)
        grabSessionId++
        grabbed = SlotLocation.Home(targetPage, targetSlot)
        focusedSlot = grabbed
        showFocusRing = true
        closeFolder()
        return true
    }

    private fun placeOnHome(app: AppInfo) {
        val pageIndex = pages.indexOfFirst { it.firstEmptySlot() >= 0 }
        if (pageIndex >= 0) {
            val page = pages[pageIndex]
            page.slots[page.firstEmptySlot()] = GridItem.Entry(app)
        } else {
            val page = Page()
            page.slots[0] = GridItem.Entry(app)
            pages.add(page)
        }
    }

    /** Activates whatever the D-pad/stick cursor is currently sitting on (a face-button press). */
    fun confirmFocused() {
        finalizePendingDrop()
        val location = focusedSlot ?: return
        if (isEditMode) {
            // Mirrors the touch behavior: with nothing currently grabbed, confirming on a folder
            // opens it (its own edit view, since edit mode is still active) rather than grabbing
            // it to reposition — that's still available via the Move button or a drag.
            val item = slotAt(location)
            if (grabbed == null && item is GridItem.FolderEntry) {
                showFolder(item.folder, location.slotIndexInGrid)
            } else {
                onSlotTapped(location)
            }
            return
        }
        when (val item = slotAt(location)) {
            is GridItem.Entry -> launchApp(item.app)
            is GridItem.FolderEntry -> showFolder(item.folder, location.slotIndexInGrid)
            null -> {}
        }
    }

    private fun defaultFocusSlot(): SlotLocation {
        openFolder?.let { folder ->
            val firstItem = (0 until MAX_FOLDER_ITEMS).firstOrNull { folder.items.getOrNull(it) != null } ?: 0
            return SlotLocation.InFolder(folder.id, firstItem)
        }
        val pageIndex = currentPageIndex.coerceIn(pages.indices)
        val page = pages[pageIndex]
        val firstOccupied = page.slots.indexOfFirst { it != null }
        return SlotLocation.Home(pageIndex, if (firstOccupied >= 0) firstOccupied else 0)
    }

    private val SlotLocation.slotIndexInGrid: Int
        get() = when (this) {
            is SlotLocation.Home -> slotIndex
            is SlotLocation.InFolder -> slotIndex
        }

    private fun SlotLocation.withSlotIndex(newIndex: Int): SlotLocation = when (this) {
        is SlotLocation.Home -> copy(slotIndex = newIndex)
        is SlotLocation.InFolder -> copy(slotIndex = newIndex)
    }

    private fun rowOf(slotIndex: Int): Int {
        var remaining = slotIndex
        ROW_SLOT_COUNTS.forEachIndexed { row, count ->
            if (remaining < count) return row
            remaining -= count
        }
        return ROW_SLOT_COUNTS.lastIndex
    }

    private fun rowStart(row: Int): Int = ROW_SLOT_COUNTS.take(row).sum()

    private fun colFractionOf(slotIndex: Int): Float {
        val row = rowOf(slotIndex)
        val count = ROW_SLOT_COUNTS[row]
        if (count <= 1) return 0.5f
        val posInRow = slotIndex - rowStart(row)
        return posInRow.toFloat() / (count - 1).toFloat()
    }

    private fun slotAtRowAndFraction(row: Int, fraction: Float): Int {
        val count = ROW_SLOT_COUNTS[row]
        val posInRow = if (count <= 1) 0 else (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
        return rowStart(row) + posInRow
    }

    // ---------- Slot access helpers ----------

    /** Public counterpart to [slotAt] for UI code — needed because [grabbed] can reference a
     * location from a different context than the one currently on screen (a folder item pulled
     * out onto the home overview, still not dropped anywhere), so the "what's lifted" preview
     * can't rely on whichever container's own slot lookup happens to be active. */
    fun itemAt(location: SlotLocation): GridItem? = slotAt(location)

    private fun slotAt(location: SlotLocation): GridItem? = when (location) {
        is SlotLocation.Home -> pages.getOrNull(location.pageIndex)?.slots?.getOrNull(location.slotIndex)
        is SlotLocation.InFolder -> {
            val folder = findFolder(location.folderId)
            folder?.items?.getOrNull(location.slotIndex)?.let { GridItem.Entry(it) }
        }
    }

    private fun setSlot(location: SlotLocation, item: GridItem?) {
        when (location) {
            is SlotLocation.Home -> {
                pages.getOrNull(location.pageIndex)?.slots?.set(location.slotIndex, item)
            }
            is SlotLocation.InFolder -> {
                val folder = findFolder(location.folderId) ?: return
                folder.items[location.slotIndex] = (item as? GridItem.Entry)?.app
            }
        }
    }

    private fun findFolder(folderId: String): FolderInfo? {
        if (openFolder?.id == folderId) return openFolder
        for (page in pages) {
            for (slot in page.slots) {
                if (slot is GridItem.FolderEntry && slot.folder.id == folderId) return slot.folder
            }
        }
        return null
    }

    // ---------- Move / merge ----------

    private fun performMoveOrMerge(source: SlotLocation, dest: SlotLocation) {
        val sourceItem = slotAt(source) ?: return
        val destItem = slotAt(dest)

        when {
            destItem == null -> {
                setSlot(dest, sourceItem)
                clearSlot(source)
            }
            destItem is GridItem.Entry && sourceItem is GridItem.Entry && dest is SlotLocation.Home -> {
                // Dropping one app onto another creates a folder containing both (folders cannot nest).
                val folder = FolderInfo().apply {
                    items[0] = destItem.app
                    items[1] = sourceItem.app
                }
                setSlot(dest, GridItem.FolderEntry(folder))
                clearSlot(source)
            }
            destItem is GridItem.Entry && sourceItem is GridItem.Entry -> {
                // Both slots live inside the same folder: reorder by swapping instead of nesting.
                setSlot(dest, sourceItem)
                setSlot(source, destItem)
            }
            destItem is GridItem.FolderEntry && sourceItem is GridItem.Entry -> {
                val freeSlot = destItem.folder.firstEmptySlot()
                if (freeSlot < 0) {
                    toast = "Folder is full"
                    return
                }
                destItem.folder.items[freeSlot] = sourceItem.app
                clearSlot(source)
            }
            else -> {
                // Folder onto folder, or folder onto itself: simple swap of positions.
                setSlot(dest, sourceItem)
                setSlot(source, destItem)
            }
        }
    }

    private fun clearSlot(location: SlotLocation) {
        when (location) {
            is SlotLocation.Home -> pages.getOrNull(location.pageIndex)?.slots?.set(location.slotIndex, null)
            is SlotLocation.InFolder -> {
                val folder = findFolder(location.folderId) ?: return
                folder.items[location.slotIndex] = null
                pruneFolderIfEmpty(folder)
            }
        }
    }

    /** A folder that's been emptied out (its last app moved/dragged elsewhere) disappears
     * entirely rather than sitting around as an empty bubble. */
    private fun pruneFolderIfEmpty(folder: FolderInfo) {
        if (!folder.isEmpty()) return
        if (openFolder?.id == folder.id) closeFolder()
        for (page in pages) {
            val idx = page.slots.indexOfFirst { it is GridItem.FolderEntry && it.folder.id == folder.id }
            if (idx >= 0) {
                page.slots[idx] = null
                return
            }
        }
    }

    fun renameFolder(folder: FolderInfo, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) folder.name = trimmed
    }

    /** Removes the given page if it's empty (and it isn't the last page). Pages with any apps or
     * folders on them are left alone — clear it out first. */
    fun removePage(pageIndex: Int) {
        finalizePendingDrop()
        if (pages.size <= 1) {
            toast = "Can't remove the only page"
            return
        }
        val page = pages.getOrNull(pageIndex) ?: return
        if (!page.isEmpty()) {
            toast = "Remove all apps from this page first"
            return
        }
        pages.removeAt(pageIndex)
        if (currentPageIndex >= pages.size) currentPageIndex = pages.size - 1
        focusedSlot = null
    }

    /** Touch-drag doesn't hover across pages (each page is its own grid instance), so dragging a
     * held item off the top/bottom edge of the current page carries it onto the adjacent page's
     * first free slot instead, then hops the view there so the result is visible immediately. */
    fun movePageAcrossViaDrag(pageDelta: Int) {
        finalizePendingDrop()
        val source = grabbed as? SlotLocation.Home ?: run {
            grabbed = null
            return
        }
        val targetPage = source.pageIndex + pageDelta
        if (targetPage !in pages.indices) {
            grabbed = null
            return
        }
        val sourceItem = slotAt(source) ?: run {
            grabbed = null
            return
        }
        val targetPageObj = pages[targetPage]
        val destSlotIndex = targetPageObj.firstEmptySlot()
        if (destSlotIndex < 0) {
            toast = "That page is full"
            grabbed = null
            return
        }
        targetPageObj.slots[destSlotIndex] = sourceItem
        clearSlot(source)
        grabbed = null
        focusedSlot = SlotLocation.Home(targetPage, destSlotIndex)
        showFocusRing = true
        currentPageIndex = targetPage
        pendingPageScrollTo = targetPage
    }
}
