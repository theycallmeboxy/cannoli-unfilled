package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cannoli.scorza.config.AppConfig
import java.io.File

class RetroArchLauncher(
    private val context: Context,
    private val getRetroArchPackage: () -> String,
) {
    /**
     * Launches [romFile] with [coreId] in RetroArch. [template] is the `apps.retroarch` entry
     * from emulators.json; its package is replaced by the configured RetroArch package so the
     * Settings choice still applies. Without a template this falls back to the classic
     * RetroActivityFuture intent.
     */
    fun launchRetroArchIntent(
        romFile: File,
        coreId: String,
        template: AppConfig? = null,
        targetPackage: String? = null,
    ): LaunchResult {
        val pkg = targetPackage ?: getRetroArchPackage()
        if (!context.isPackageInstalled(pkg)) return LaunchResult.AppNotInstalled(pkg)

        val intent = if (template != null) {
            val config = template.copy(packageName = pkg)
            val resolved = try {
                EmulatorIntentBuilder.resolve(
                    context, config, romFile, mapOf(EmulatorIntentBuilder.VAR_CORE to coreId),
                )
            } catch (e: IllegalArgumentException) {
                return LaunchResult.Error(e.message ?: "Failed to launch RetroArch")
            }
            EmulatorIntentBuilder.toAndroidIntent(context, resolved, config)
        } else {
            Intent().apply {
                component = ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
                putExtra("LIBRETRO", "/data/data/$pkg/cores/${coreId}_android.so")
                putExtra("ROM", romFile.absolutePath)
                putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return context.startActivityNoAnim(intent, "Failed to launch RetroArch")
    }
}

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun Context.startActivityNoAnim(intent: Intent, fallbackMsg: String): LaunchResult = try {
    startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
    LaunchResult.Success
} catch (e: Exception) {
    LaunchResult.Error(e.message ?: fallbackMsg)
}

fun Context.romFileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
