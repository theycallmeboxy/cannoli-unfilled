package dev.cannoli.scorza.input

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.LibraryRef
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.romm.sync.PreLaunchOutcome
import dev.cannoli.scorza.romm.sync.RomKeys
import dev.cannoli.scorza.romm.sync.SaveSyncService
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.server.KitchenManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.ui.components.COLOR_GRID_COLS
import dev.cannoli.ui.theme.COLOR_PRESETS
import dev.cannoli.ui.theme.colorToArgbLong
import dev.cannoli.ui.theme.hexToColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class LauncherActions @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val romsRepository: RomsRepository,
    private val appsRepository: AppsRepository,
    private val launchManager: LaunchManager,
    private val platformConfig: PlatformConfig,
    private val artworkLookup: ArtworkLookup,
    private val arcadeTitleLookup: ArcadeTitleLookup,
    private val nav: NavigationController,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val saveSyncService: SaveSyncService,
    private val pathsProvider: CannoliPathsProvider,
) {

    private var pendingLaunch: (() -> DialogState?)? = null
    private var pendingRecentPath: String? = null
    private var pendingRecentReorder: Boolean = false

    fun proceedPendingLaunch() {
        val p = pendingLaunch
        val recentPath = pendingRecentPath
        val reorder = pendingRecentReorder
        pendingLaunch = null
        pendingRecentPath = null
        pendingRecentReorder = false
        p?.invoke()?.let { nav.dialogState.value = it }
        if (recentPath != null) {
            recordRecentlyPlayedByPath(recentPath)
            if (reorder) nav.pendingRecentlyPlayedReorder = true
        }
    }

    fun cancelPendingLaunch() {
        pendingLaunch = null
        pendingRecentPath = null
        pendingRecentReorder = false
    }

    fun recordPendingRecent(path: String, reorder: Boolean) {
        pendingRecentPath = path
        pendingRecentReorder = reorder
    }

    fun rescanSystemList(
        scanDisk: Boolean = true,
        reconcileOrphans: Boolean = false,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        systemListViewModel.scan(
            showRecentlyPlayed = settings.showRecentlyPlayed,
            showFavorites = settings.showFavorites,
            scanDisk = scanDisk,
            reconcileOrphans = reconcileOrphans,
            onProgress = onProgress,
            onReady = {
                onComplete?.invoke()
            }
        )
    }

    /** Rescans behind the progress overlay. A disk scan can run for a long time on a slow card, so
     *  anything that triggers one from the UI shows this rather than leaving the launcher looking
     *  hung with no indication that work is happening. */
    fun rescanWithProgress(reconcileOrphans: Boolean = false, onComplete: (() -> Unit)? = null) {
        nav.dialogState.value = DialogState.RescanProgress(
            0f, context.getString(dev.cannoli.scorza.R.string.boot_preparing),
        )
        rescanSystemList(
            scanDisk = true,
            reconcileOrphans = reconcileOrphans,
            onProgress = { tag, current, total ->
                nav.dialogState.value = DialogState.RescanProgress(
                    current.toFloat() / total.coerceAtLeast(1), tag,
                )
            },
            onComplete = {
                nav.dialogState.value = DialogState.None
                onComplete?.invoke()
            },
        )
    }

    fun refreshLauncherLists() {
        if (systemListViewModel.state.value.isLoading) return
        rescanSystemList()
        if (nav.currentScreen is LauncherScreen.GameList) {
            gameListViewModel.reload()
        }
    }

    fun invalidateAllLibraryCaches() {
        artworkLookup.invalidateAll()
        arcadeTitleLookup.invalidateAll()
    }

    /** Asks before writing the setting, so declining leaves the library exactly as it was. Pass an
     *  empty [newRomDirectory] to clear the pick back to the Cannoli root. */
    fun confirmRomDirectoryChange(newRomDirectory: String) {
        nav.dialogState.value = DialogState.LibrarySwitchConfirm(newRomDirectory)
    }

    /** Everything that resolves the ROM directory reads it on demand, and the watcher rebinds when
     *  the scan restarts it, so the change only needs the caches dropped, the new place scaffolded
     *  and the library reconciled against it. Reconciling is what prunes the entries the new
     *  location cannot account for, and this is the only path that does it. */
    fun applyRomDirectoryChange(newRomDirectory: String) {
        settings.romDirectory = newRomDirectory
        invalidateAllLibraryCaches()
        settingsViewModel.refreshActiveCategory()
        // Straight from the confirmation onto the scan screen. Clearing the dialog and waiting for
        // the scaffold to finish first flashes the settings list back up in between.
        nav.dialogState.value = DialogState.RescanProgress(
            0f, context.getString(dev.cannoli.scorza.R.string.boot_preparing),
        )
        ioScope.launch {
            val romDir = pathsProvider.romDir
            if (dev.cannoli.scorza.util.DirectoryLayout.romDirNeedsScaffold(romDir)) {
                dev.cannoli.scorza.util.DirectoryLayout.scaffoldRomFolders(romDir, platformConfig.romFolders())
            }
            withContext(Dispatchers.Main) {
                // The settings screens below were built against the old library, so the scan lands
                // the user back on the main list rather than where they started.
                rescanWithProgress(reconcileOrphans = true) {
                    nav.screenStack.clear()
                    nav.screenStack.add(LauncherScreen.SystemList)
                    settingsViewModel.resetToCategoryList()
                }
            }
        }
    }


    fun launchSelected(item: ListItem): DialogState? {
        return when (item) {
            is ListItem.RomItem -> launchSelectedRom(item.rom)
            is ListItem.AppItem -> launchManager.launchApp(item.app)
            else -> null
        }
    }

    private fun launchSelectedRom(rom: Rom): DialogState? {
        val gameKey = RomKeys.relativeKey(rom.path, pathsProvider.romDir)
        val romId = saveSyncService.isSyncableGame(gameKey)
        if (romId == null) {
            return launchManager.launchRom(rom)
        }
        val tag = rom.platformTag
        val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)
        val emulator = RomKeys.emulatorNameFor(platformConfig, tag, gameKey)
        pendingLaunch = { launchManager.launchRom(rom) }
        nav.dialogState.value = DialogState.SaveSyncChecking
        ioScope.launch {
            val outcome = saveSyncService.syncBeforeLaunch(tag, base, gameKey, emulator)
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is PreLaunchOutcome.Proceed -> {
                        nav.dialogState.value = DialogState.None
                        proceedPendingLaunch()
                    }
                    is PreLaunchOutcome.Conflict -> nav.dialogState.value = DialogState.SaveSyncConflict(outcome)
                    is PreLaunchOutcome.KnownStaleBlock -> nav.dialogState.value = DialogState.SaveSyncStaleBlock(outcome, tag, base)
                }
            }
        }
        return null
    }

    fun recordRecentlyPlayedByPath(path: String) {
        ioScope.launch {
            resolvePathToRef(path)?.let { recentlyPlayedRepository.record(it) }
        }
    }

    fun openColorPicker(settingKey: String) {
        val hex = settingsViewModel.getColorHex(settingKey)
        val color = hexToColor(hex) ?: androidx.compose.ui.graphics.Color.White
        val argb = colorToArgbLong(color)
        val idx = COLOR_PRESETS.indexOfFirst { it.color == argb }
        val row = if (idx >= 0) idx / COLOR_GRID_COLS else 0
        val col = if (idx >= 0) idx % COLOR_GRID_COLS else 0
        nav.dialogState.value = DialogState.ColorPicker(
            settingKey = settingKey,
            title = colorSettingTitle(settingKey),
            currentColor = argb,
            selectedRow = row,
            selectedCol = col
        )
    }

    fun openKitchen(fromQuickMenu: Boolean = false) {
        val km = KitchenManager
        if (!km.isRunning) km.start(context, settings.kitchenCodeBypass)
        else km.setCodeBypass(settings.kitchenCodeBypass)
        nav.dialogState.value = DialogState.Kitchen(
            urls = km.getUrls(hasActiveVpn()),
            pin = km.pin,
            requirePin = !settings.kitchenCodeBypass,
            fromQuickMenu = fromQuickMenu
        )
    }

    private fun resolvePathToRef(path: String): LibraryRef? {
        return if (path.startsWith("/apps/")) {
            val parts = path.removePrefix("/apps/").split("/", limit = 2)
            if (parts.size == 2) {
                val type = runCatching { AppType.valueOf(parts[0]) }.getOrNull()
                type?.let { appsRepository.byPackage(it, parts[1]) }?.let { LibraryRef.App(it.id) }
            } else null
        } else {
            romsRepository.gameByPath(path)?.let { LibraryRef.Rom(it.id) }
        }
    }

    private fun hasActiveVpn(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun colorSettingTitle(settingKey: String): String {
        val labelRes = when (settingKey) {
            "color_accent" -> dev.cannoli.scorza.R.string.setting_color_accent
            "color_highlight" -> dev.cannoli.scorza.R.string.setting_color_highlight
            "color_highlight_text" -> dev.cannoli.scorza.R.string.setting_color_highlight_text
            "color_text" -> dev.cannoli.scorza.R.string.setting_color_text
            "color_title" -> dev.cannoli.scorza.R.string.setting_color_title
            else -> return ""
        }
        return context.getString(labelRes)
    }
}
