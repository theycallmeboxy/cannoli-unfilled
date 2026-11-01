package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.pm.PackageManager
import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import java.io.File

class LaunchManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val retroArchLauncher: RetroArchLauncher,
    private val apkLauncher: ApkLauncher,
    private val launchState: LaunchState,
    private val installedCoreService: InstalledCoreService? = null,
    private val gameOverrides: dev.cannoli.scorza.db.GameOverrideStore? = null,
) {
    // Per-game overrides are keyed by rom_id, which the scanner keeps stable across renames,
    // moves and auto-organize, so an override follows its game instead of being orphaned.
    private fun overrideFor(rom: Rom): dev.cannoli.scorza.config.EmulatorChoice? =
        gameOverrides?.get(rom.id)

    init {
        apkLauncher.debugLog = ::debugLog
    }

    fun launchRom(rom: Rom): DialogState? {
        debugLog("launchRom entered: ${rom.platformTag} / ${rom.path.name}")
        if (launchState.launching) return null
        launchState.launching = true
        launchState.lastLaunched = rom
        val launchFile = rom.path

        val gameOverride = overrideFor(rom)
        if (gameOverride?.source == EmulatorSource.Standalone && gameOverride.appPackage != null) {
            val cfg = platformConfig.getAppConfig(rom.platformTag, gameOverride.appPackage)
            return launchResultDialog(
                launchStandalone(launchFile, cfg), rom.platformTag, rom.id
            )
        }
        val overrideRomId = if (gameOverride != null) rom.id else null

        val resolvedCore = gameOverride?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag)
        val source = pickSource(
            gameSource = gameOverride?.source,
            platformSource = platformConfig.getPlatformChoice(rom.platformTag)?.source,
            // configuredCores, not installedCores: a core present only in a RetroArch the
            // user is not using must not suppress the standalone fallback.
            raAvailable = {
                resolvedCore != null &&
                    installedCoreService?.configuredCores()?.any { it.value.contains(resolvedCore) } == true
            },
            standaloneAvailable = {
                platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager) != null
            },
        )
        val result = if (source == EmulatorSource.Standalone) {
            val cfg = platformConfig.getUserAppMapping(rom.platformTag)
                ?.let { platformConfig.getAppConfig(rom.platformTag, it) }
                ?: platformConfig.getFirstInstalledApp(rom.platformTag, context.packageManager)
                ?: platformConfig.getAppPackage(rom.platformTag)?.let { platformConfig.getAppConfig(rom.platformTag, it) }
            if (cfg != null) {
                launchStandalone(launchFile, cfg)
            } else {
                LaunchResult.CoreNotInstalled("unknown")
            }
        } else if (resolvedCore != null) {
            val raPackage = settings.retroArchPackage
            if (!context.isPackageInstalled(raPackage)) {
                val appName = try {
                    val info = context.packageManager.getApplicationInfo(raPackage, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: PackageManager.NameNotFoundException) { raPackage }
                return errorAndReset(DialogState.MissingApp(appName, raPackage, rom.platformTag, overrideRomId))
            }
            // The core-install check applies to any package that can report its
            // cores. It self-skips for one that cannot, since a missing report is
            // not evidence of a missing core.
            if (installedCoreService != null
                && installedCoreService.cacheReady
                && installedCoreService.canReport(raPackage)
                && !installedCoreService.hasCoreInPackage(resolvedCore, raPackage)) {
                val label = InstalledCoreService.getPackageLabel(raPackage)
                val coreName = platformConfig.getCoreDisplayName(resolvedCore)
                return errorAndReset(DialogState.MissingCore(coreName, label, rom.platformTag, overrideRomId))
            }
            val raConfig = "/storage/emulated/0/Android/data/$raPackage/files/retroarch.cfg"
            retroArchLauncher.launchRetroArchIntent(launchFile, resolvedCore, raConfig, raPackage)
        } else {
            // App-only platform (no core) that reached here has nothing installed to
            // run it, so name the app it wants instead of a core it never had.
            val appPkg = platformConfig.getAppPackage(rom.platformTag)
            if (appPkg != null && !context.isPackageInstalled(appPkg)) {
                LaunchResult.AppNotInstalled(appPkg)
            } else {
                LaunchResult.CoreNotInstalled("unknown")
            }
        }

        return launchResultDialog(result, rom.platformTag)
    }

    fun launchApp(app: App): DialogState? {
        debugLog("launchApp entered: ${app.type} / ${app.packageName}")
        if (launchState.launching) return null
        launchState.launching = true
        return launchResultDialog(apkLauncher.launch(app.packageName))
    }

    private fun errorAndReset(dialog: DialogState): DialogState {
        launchState.launching = false
        launchState.lastLaunched = null
        return dialog
    }

    private fun launchResultDialog(result: LaunchResult, platformTag: String? = null, romId: Long? = null): DialogState? {
        val dialog = toLaunchDialog(result, platformTag, romId)
        if (dialog != null) launchState.launching = false
        return dialog
    }

    private fun toLaunchDialog(result: LaunchResult, platformTag: String? = null, romId: Long? = null): DialogState? {
        return when (result) {
            is LaunchResult.CoreNotInstalled -> DialogState.MissingCore(result.coreName, platformTag = platformTag, romId = romId)
            is LaunchResult.AppNotInstalled -> {
                // getApplicationLabel cannot resolve a package that is not installed, and this
                // branch only fires when it is not, so use the curated label like MissingCore does.
                val appName = InstalledCoreService.getPackageLabel(result.packageName)
                DialogState.MissingApp(appName, result.packageName, platformTag, romId)
            }
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

    private fun launchStandalone(launchFile: File, cfg: AppConfig): LaunchResult =
        apkLauncher.launchWithRom(cfg.packageName, launchFile, cfg)

    companion object {
        // Null means no stored preference anywhere, so the caller falls through to RetroArch.
        // The availability probes are lazy because a stored choice decides the answer on its
        // own, and probing to reach a conclusion already known is wasted work.
        fun pickSource(
            gameSource: EmulatorSource?,
            platformSource: EmulatorSource?,
            raAvailable: () -> Boolean,
            standaloneAvailable: () -> Boolean,
        ): EmulatorSource? {
            val stored = gameSource ?: platformSource
            if (stored != null) return stored
            if (!raAvailable() && standaloneAvailable()) return EmulatorSource.Standalone
            return null
        }
    }
}
