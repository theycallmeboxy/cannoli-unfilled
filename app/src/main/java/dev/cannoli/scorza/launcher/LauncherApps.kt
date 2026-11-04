package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The installed apps with a home-screen icon, as (label, package), sorted by label, without
 * Cannoli itself. On a TV, Android TV Settings comes first, as the virtual package ApkLauncher
 * opens. Used by Add To This Folder and by Kitchen's config editor suggestions.
 */
fun installedLauncherApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val resolveInfos = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    val apps = resolveInfos
        .mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            ri.loadLabel(pm).toString() to pkg
        }
        .distinctBy { it.second }
        .sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
    return if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
        listOf("Android TV Settings" to ApkLauncher.VIRTUAL_TV_SETTINGS_PACKAGE) + apps
    } else apps
}
