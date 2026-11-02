package dev.cannoli.scorza.launcher

import android.content.Context
import dev.cannoli.scorza.R
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.Placeholders
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.romm.sync.RomKeys
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import java.io.File

class LaunchManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val apkLauncher: ApkLauncher,
    private val launchState: LaunchState,
    private val romDir: () -> File,
    private val installedCoreService: InstalledCoreService? = null,
    private val foldersConfig: dev.cannoli.scorza.config.FoldersConfig? = null,
) {
    init {
        apkLauncher.debugLog = ::debugLog
    }

    /**
     * Launches [rom] with the emulator systems.json and overrides.json give it: the game's
     * override, else its system's first emulator, with the system's values for its placeholders.
     */
    fun launchRom(rom: Rom): DialogState? {
        debugLog("launchRom entered: ${rom.platformTag} / ${rom.path.name}")
        if (launchState.launching) return null
        launchState.launching = true
        launchState.lastLaunched = rom

        val plan = platformConfig.launchFor(rom.platformTag, RomKeys.relativeKey(rom.path, romDir()))
            ?: return errorAndReset(DialogState.LaunchError(
                context.getString(R.string.launch_error_no_emulator, platformConfig.getDisplayName(rom.platformTag))
            ))
        val emulator = plan.emulator
        val pkg = emulator.intent.packageName
        debugLog("  plan: ${plan.ref.choiceName} -> $pkg vars=${plan.vars}")
        if (!context.isPackageInstalled(pkg)) {
            return errorAndReset(DialogState.MissingApp(emulator.label, pkg))
        }
        // Only an emulator that answered the installed-cores query can block here; one that
        // cannot report is given the benefit of the doubt.
        val core = plan.vars[Placeholders.CORE]
        if (core != null && installedCoreService?.coreStatus(pkg, core) == CoreStatus.MISSING) {
            return errorAndReset(DialogState.MissingCore(core, emulator.label))
        }
        return launchResultDialog(apkLauncher.launchWithRom(pkg, rom.path, emulator.intent, plan.vars))
    }

    fun launchApp(app: App): DialogState? {
        debugLog("launchApp entered: ${app.type} / ${app.packageName}")
        if (launchState.launching) return null
        launchState.launching = true
        val custom = foldersConfig?.intentFor(app.packageName)
        return launchResultDialog(if (custom != null) apkLauncher.launchConfigured(custom) else apkLauncher.launch(app.packageName))
    }

    private fun errorAndReset(dialog: DialogState): DialogState {
        launchState.launching = false
        launchState.lastLaunched = null
        return dialog
    }

    private fun launchResultDialog(result: LaunchResult): DialogState? {
        val dialog = toLaunchDialog(result)
        if (dialog != null) launchState.launching = false
        return dialog
    }

    private fun toLaunchDialog(result: LaunchResult): DialogState? {
        return when (result) {
            // getApplicationLabel cannot resolve a package that is not installed, so the dialog
            // names the package itself.
            is LaunchResult.AppNotInstalled -> DialogState.MissingApp(result.packageName, result.packageName)
            is LaunchResult.Error -> DialogState.LaunchError(result.message)
            LaunchResult.Success -> null
        }
    }

    private val debugSink = dev.cannoli.scorza.util.LogSink(0)
    private val debugFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
    private var debugSinkRoot: String? = null

    private fun debugLog(message: String) {
        if (!dev.cannoli.scorza.util.LoggingPrefs.session) return
        try {
            val root = settings.sdCardRoot
            if (root != debugSinkRoot) {
                debugSinkRoot = root
                debugSink.open(File(CannoliPaths(root).logsDir, "launch_debug.log"))
            }
            val stamp = synchronized(debugFmt) { debugFmt.format(java.util.Date()) }
            dev.cannoli.scorza.util.LogWriter.write(debugSink, "$stamp $message\n")
        } catch (_: Exception) {}
    }
}
