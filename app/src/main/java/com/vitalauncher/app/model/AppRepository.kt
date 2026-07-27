package com.vitalauncher.app.model

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    }

    fun launch(context: Context, app: AppInfo) {
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
