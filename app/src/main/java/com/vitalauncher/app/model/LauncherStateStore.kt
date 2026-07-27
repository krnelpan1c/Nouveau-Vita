package com.vitalauncher.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "vita_launcher_state"
private const val KEY_LAYOUT = "layout_v1"
private const val SCHEMA_VERSION = 1

/**
 * Everything about the home screen that isn't derivable from the installed-app list — which app
 * or folder sits in which slot, folder names/contents, and the chosen background — serialized to
 * a single JSON blob in [android.content.SharedPreferences] so it survives process death and
 * relaunches. App bitmaps/labels are never stored; slots are keyed by [AppInfo.key] and re-resolved
 * against a freshly-loaded app list on read, so uninstalled apps just drop out quietly.
 */
object LauncherStateStore {

    data class Restored(
        val pages: List<List<GridItem?>>,
        val backgroundMode: BackgroundMode,
        val colorThemeIndex: Int,
    )

    fun load(context: Context, apps: List<AppInfo>): Restored? {
        val raw = prefs(context).getString(KEY_LAYOUT, null) ?: return null
        return try {
            parse(raw, apps.associateBy { it.key })
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, json: String) {
        prefs(context).edit().putString(KEY_LAYOUT, json).commit()
    }

    fun serialize(pages: List<Page>, backgroundMode: BackgroundMode, colorThemeIndex: Int): String {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("backgroundMode", backgroundMode.name)
        root.put("colorThemeIndex", colorThemeIndex)
        val pagesArray = JSONArray()
        for (page in pages) {
            val slotsArray = JSONArray()
            for (slot in page.slots) {
                slotsArray.put(slotToJson(slot))
            }
            pagesArray.put(JSONObject().put("slots", slotsArray))
        }
        root.put("pages", pagesArray)
        return root.toString()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun slotToJson(slot: GridItem?): Any = when (slot) {
        null -> JSONObject.NULL
        is GridItem.Entry -> JSONObject()
            .put("type", "app")
            .put("key", slot.app.key)
        is GridItem.FolderEntry -> JSONObject()
            .put("type", "folder")
            .put("id", slot.folder.id)
            .put("name", slot.folder.name)
            // Sparse, position-preserving — same idea as a page's own "slots" array — so an app
            // left in a specific spot inside a folder is restored to that exact spot, not just
            // appended back in wherever a compact list would put it.
            .put("slots", JSONArray(slot.folder.items.map { app -> app?.key ?: JSONObject.NULL }))
    }

    private fun parse(raw: String, appsByKey: Map<String, AppInfo>): Restored? {
        val root = JSONObject(raw)
        val backgroundMode = try {
            BackgroundMode.valueOf(root.optString("backgroundMode", BackgroundMode.GRADIENT.name))
        } catch (e: IllegalArgumentException) {
            BackgroundMode.GRADIENT
        }
        val colorThemeIndex = root.optInt("colorThemeIndex", 0)
        val pagesArray = root.optJSONArray("pages") ?: return null

        val pages = mutableListOf<List<GridItem?>>()
        for (i in 0 until pagesArray.length()) {
            val slotsArray = pagesArray.getJSONObject(i).optJSONArray("slots") ?: JSONArray()
            val slots = mutableListOf<GridItem?>()
            for (s in 0 until slotsArray.length()) {
                slots.add(jsonToSlot(slotsArray.opt(s), appsByKey))
            }
            while (slots.size < SLOTS_PER_PAGE) slots.add(null)
            pages.add(slots.take(SLOTS_PER_PAGE))
        }
        if (pages.isEmpty()) return null
        return Restored(pages, backgroundMode, colorThemeIndex)
    }

    private fun jsonToSlot(value: Any?, appsByKey: Map<String, AppInfo>): GridItem? {
        val obj = value as? JSONObject ?: return null
        return when (obj.optString("type")) {
            "app" -> appsByKey[obj.optString("key")]?.let { GridItem.Entry(it) }
            "folder" -> {
                val folder = FolderInfo(id = obj.optString("id"), name = obj.optString("name", "Folder"))
                val slotsArray = obj.optJSONArray("slots")
                if (slotsArray != null) {
                    // Current sparse format: position i in the JSON array is exactly slot i.
                    for (i in 0 until minOf(slotsArray.length(), MAX_FOLDER_ITEMS)) {
                        val key = slotsArray.opt(i) as? String ?: continue
                        folder.items[i] = appsByKey[key]
                    }
                } else {
                    // Back-compat with the older compact "items" list (no gaps): place
                    // sequentially starting at slot 0, same as it always used to render.
                    val itemsArray = obj.optJSONArray("items") ?: JSONArray()
                    val items = (0 until itemsArray.length()).mapNotNull { idx -> appsByKey[itemsArray.optString(idx)] }
                    items.forEachIndexed { i, app -> if (i < MAX_FOLDER_ITEMS) folder.items[i] = app }
                }
                // An app that got uninstalled might leave a folder empty; drop it rather than
                // restoring an empty bubble the user could never have created in the first place.
                if (folder.isEmpty()) return null
                GridItem.FolderEntry(folder)
            }
            else -> null
        }
    }
}
