package dev.cannoli.scorza.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.StrictMode
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.LaunchMethod
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellLauncher: ShellLauncher,
) {

    var debugLog: (String) -> Unit = {}
        set(value) { field = value; shellLauncher.debugLog = value }

    companion object {
        const val VIRTUAL_TV_SETTINGS_PACKAGE = "cannoli.virtual.tv_settings"
    }

    fun launch(packageName: String): LaunchResult {
        val intent = if (packageName == VIRTUAL_TV_SETTINGS_PACKAGE) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult.AppNotInstalled(packageName)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return context.startActivityNoAnim(intent, "Failed to launch app")
    }

    /**
     * Opens an app with the custom intent its folders.json entry gives. There is no game file,
     * so only fixed-value extras apply; file extras in the entry are ignored.
     */
    fun launchConfigured(config: AppConfig): LaunchResult {
        val pkg = config.packageName
        if (!context.isPackageInstalled(pkg)) return LaunchResult.AppNotInstalled(pkg)
        val intent = Intent(config.action).apply {
            if (config.activity != null) component = android.content.ComponentName(pkg, config.activity)
            else setPackage(pkg)
            addFlags(config.intentFlags)
            for (extra in config.extras) when (extra.kind) {
                dev.cannoli.scorza.config.ExtraValueKind.STRING -> putExtra(extra.key, extra.value)
                dev.cannoli.scorza.config.ExtraValueKind.INT -> extra.value?.toIntOrNull()?.let { putExtra(extra.key, it) }
                dev.cannoli.scorza.config.ExtraValueKind.BOOL -> extra.value?.toBooleanStrictOrNull()?.let { putExtra(extra.key, it) }
                dev.cannoli.scorza.config.ExtraValueKind.STRING_ARRAY -> putExtra(extra.key, extra.values.orEmpty().toTypedArray())
                else -> debugLog("  -> extra ${extra.key} needs a game file, skipped")
            }
        }
        // MAIN with no activity named means "open the app", which needs its launcher activity.
        if (intent.component == null && intent.action == Intent.ACTION_MAIN) {
            context.packageManager.getLaunchIntentForPackage(pkg)?.component?.let { intent.component = it }
        }
        return context.startActivityNoAnim(intent, "Failed to launch app")
    }

    fun launchWithRom(
        packageName: String,
        romFile: File,
        config: AppConfig,
        vars: Map<String, String> = emptyMap(),
    ): LaunchResult {
        debugLog("ApkLauncher.launchWithRom pkg=$packageName rom=${romFile.absolutePath} method=${config.launchMethod}")
        if (!context.isPackageInstalled(packageName)) {
            debugLog("  -> package not installed")
            return LaunchResult.AppNotInstalled(packageName)
        }
        val resolved = try {
            EmulatorIntentBuilder.resolve(context, config, romFile, vars)
        } catch (e: IllegalArgumentException) {
            debugLog("  -> extra resolution failed: ${e.message}")
            return LaunchResult.Error(
                context.getString(dev.cannoli.scorza.R.string.launch_error_bad_game_file, romFile.name)
            )
        }
        return when (config.launchMethod) {
            LaunchMethod.INTENT -> dispatchIntent(resolved, config, romFile, packageName)
            LaunchMethod.SHELL  -> shellLauncher.launch(ShellCommandFormatter.format(resolved))
        }
    }

    private fun dispatchIntent(
        resolved: ResolvedIntent,
        config: AppConfig,
        romFile: File,
        packageName: String,
    ): LaunchResult {
        val intent = EmulatorIntentBuilder.toAndroidIntent(context, resolved, config)
        debugLog("  intent built: action=${intent.action} component=${intent.component?.flattenToShortString()}")
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            debugLog("  -> intent did not resolve; falling back to ACTION_VIEW + FileProvider")
            logExposedActivities(packageName)
            return launchViewWithFileProvider(packageName, romFile)
        }
        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        val previousVmPolicy = if (resolved.dataUri?.scheme == "file") {
            val current = StrictMode.getVmPolicy()
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            current
        } else null
        return try {
            context.startActivity(intent, opts)
            debugLog("  -> startActivity succeeded")
            LaunchResult.Success
        } catch (e: Exception) {
            debugLog("  -> startActivity failed: ${e.javaClass.simpleName}: ${e.message}")
            LaunchResult.Error(e.message ?: "Failed to launch emulator")
        } finally {
            previousVmPolicy?.let { StrictMode.setVmPolicy(it) }
        }
    }

    private fun logExposedActivities(packageName: String) {
        val probe = Intent().setPackage(packageName)
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.queryIntentActivities(probe, PackageManager.GET_RESOLVED_FILTER)
        if (resolved.isEmpty()) {
            debugLog("  exposed activities: <none discovered>")
            return
        }
        debugLog("  exposed activities for $packageName:")
        for (info in resolved) {
            val activity = info.activityInfo?.name ?: "?"
            val actions = info.filter?.let { f -> (0 until f.countActions()).map { f.getAction(it) } } ?: emptyList()
            debugLog("    - $activity actions=$actions")
        }
    }

    private fun launchViewWithFileProvider(packageName: String, romFile: File): LaunchResult {
        val uri = context.romFileProviderUri(romFile)

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        return try {
            context.startActivity(viewIntent, opts)
            debugLog("  -> FileProvider VIEW startActivity succeeded")
            LaunchResult.Success
        } catch (_: Exception) {
            debugLog("  -> FileProvider VIEW failed, retrying via getLaunchIntentForPackage")
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return LaunchResult.Error("No launch activity for $packageName")
            launchIntent.apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(launchIntent, opts)
                LaunchResult.Success
            } catch (e: Exception) {
                LaunchResult.Error(e.message ?: "Failed to launch emulator")
            }
        }
    }
}
