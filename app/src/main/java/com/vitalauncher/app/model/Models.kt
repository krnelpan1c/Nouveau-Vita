package com.vitalauncher.app.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import java.util.UUID

/** Number of slots in each row of a page/folder, top to bottom. Sum must equal [SLOTS_PER_PAGE]. */
val ROW_SLOT_COUNTS = listOf(3, 4, 3)
val SLOTS_PER_PAGE = ROW_SLOT_COUNTS.sum()
const val MAX_FOLDER_ITEMS = 10

/** A launchable thing on the home screen: a regular app, or — when [shortcutId] is set — a pinned
 * shortcut (e.g. a website or a specific chat) published by [packageName] and accepted by us. */
@Immutable
data class AppInfo(
    val packageName: String,
    val activityClassName: String,
    val label: String,
    val icon: ImageBitmap,
    val shortcutId: String? = null,
) {
    val isShortcut: Boolean get() = shortcutId != null
    val key: String get() = if (shortcutId != null) "shortcut:$packageName/$shortcutId" else "$packageName/$activityClassName"
}

/** A folder's contents are a fixed-size grid of slots, exactly like [Page] — not a compact list —
 * so an app can sit in any specific slot with real gaps around it. A compact list would silently
 * re-pack around whichever slot an app was dropped on, snapping it back next to the others. */
@Stable
class FolderInfo(
    val id: String = UUID.randomUUID().toString(),
    name: String = "Folder",
) {
    var name: String by mutableStateOf(name)
    val items: SnapshotStateList<AppInfo?> = mutableStateListOf<AppInfo?>().apply {
        repeat(MAX_FOLDER_ITEMS) { add(null) }
    }

    fun isEmpty(): Boolean = items.all { it == null }
    fun firstEmptySlot(): Int = items.indexOfFirst { it == null }
}

@Stable
sealed class GridItem {
    @Immutable
    data class Entry(val app: AppInfo) : GridItem()
    @Stable
    data class FolderEntry(val folder: FolderInfo) : GridItem()
}

/** One page of the home screen (or the single page of a folder): a fixed-size list of slots. */
@Stable
class Page(
    val id: String = UUID.randomUUID().toString(),
) {
    val slots: SnapshotStateList<GridItem?> = mutableStateListOf<GridItem?>().apply {
        repeat(SLOTS_PER_PAGE) { add(null) }
    }

    fun isEmpty(): Boolean = slots.all { it == null }
    fun firstEmptySlot(): Int = slots.indexOfFirst { it == null }
}

/** Identifies a single slot, either on a home page or inside an open folder. */
@Immutable
sealed class SlotLocation {
    @Immutable
    data class Home(val pageIndex: Int, val slotIndex: Int) : SlotLocation()
    @Immutable
    data class InFolder(val folderId: String, val slotIndex: Int) : SlotLocation()
}

/** A gamepad/D-pad navigation direction. */
enum class NavDirection { UP, DOWN, LEFT, RIGHT }

/** What's drawn behind the home icons: one of [VitaColorThemes][com.vitalauncher.app.ui.theme.VitaColorThemes],
 * or the device's own system wallpaper (static image or live) drawn behind the window itself. */
enum class BackgroundMode { GRADIENT, SYSTEM_WALLPAPER }
