package com.vitalauncher.app.model

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Queries the Android package manager for launchable apps, excluding this launcher itself. */
object AppRepository {

    suspend fun loadLaunchableApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ownPackage = context.packageName

        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        resolveInfos
            .filter { it.activityInfo.packageName != ownPackage }
            .map { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo
                val label = resolveInfo.loadLabel(pm).toString()
                val iconDrawable = resolveInfo.loadIcon(pm)
                val bitmap = iconDrawable.toBitmap(width = 192, height = 192).asImageBitmap()
                AppInfo(
                    packageName = activityInfo.packageName,
                    activityClassName = activityInfo.name,
                    label = label,
                    icon = bitmap,
                )
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
            .plus(loadPinnedShortcuts(context))
    }

    /** True while we're the default home app, the only state in which Android lets us read,
     * launch or unpin shortcuts. */
    fun hasShortcutHostPermission(context: Context): Boolean = try {
        context.getSystemService(LauncherApps::class.java)?.hasShortcutHostPermission() == true
    } catch (e: Exception) {
        false
    }

    /** Shortcuts other apps asked us to pin (see PinShortcutActivity). Empty if we aren't the
     * default launcher or the query fails. */
    private fun loadPinnedShortcuts(context: Context): List<AppInfo> {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
        if (!hasShortcutHostPermission(context)) return emptyList()
        return try {
            val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val shortcuts = launcherApps.getShortcuts(query, Process.myUserHandle()) ?: return emptyList()
            shortcuts
                .filter { it.isEnabled }
                .mapNotNull { info ->
                    val label = (info.shortLabel ?: info.longLabel)?.toString() ?: return@mapNotNull null
                    val drawable = launcherApps.getShortcutIconDrawable(info, 0)
                        ?: runCatching { context.packageManager.getApplicationIcon(info.`package`) }.getOrNull()
                        ?: return@mapNotNull null
                    AppInfo(
                        packageName = info.`package`,
                        activityClassName = "",
                        label = label,
                        icon = drawable.toBitmap(width = 192, height = 192).asImageBitmap(),
                        shortcutId = info.id,
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Releases a pinned shortcut so it no longer comes back after being removed from the grid. */
    fun unpinShortcut(context: Context, app: AppInfo) {
        val id = app.shortcutId ?: return
        try {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return
            val user = Process.myUserHandle()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val remaining = launcherApps.getShortcuts(query, user).orEmpty().map { it.id }.filter { it != id }
            launcherApps.pinShortcuts(app.packageName, remaining, user)
        } catch (e: Exception) {
            // Not the default launcher (or shortcut already gone) — nothing to release.
        }
    }

    fun launch(context: Context, app: AppInfo) {
        if (app.shortcutId != null) {
            try {
                context.getSystemService(LauncherApps::class.java)
                    ?.startShortcut(app.packageName, app.shortcutId, null, null, Process.myUserHandle())
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Can't open this shortcut", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = android.content.ComponentName(app.packageName, app.activityClassName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openAppInfo(context: Context, app: AppInfo) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", app.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
