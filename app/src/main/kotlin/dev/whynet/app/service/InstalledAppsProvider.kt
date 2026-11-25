package dev.whynet.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class LaunchableAppInfo(
    val packageName: String,
    val label: String,
)

object InstalledAppsProvider {

    fun loadLaunchableApps(context: Context): List<LaunchableAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        val selfPkg = context.packageName

        return activities
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == selfPkg) return@mapNotNull null
                val label = ri.loadLabel(pm).toString().trim().ifBlank { pkg }
                LaunchableAppInfo(packageName = pkg, label = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
