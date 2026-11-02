package dev.cannoli.scorza.boot

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.R
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.db.importer.ImportProgress
import dev.cannoli.scorza.db.importer.ImportResult
import dev.cannoli.scorza.db.importer.Importer
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.util.KitchenLog
import dev.cannoli.scorza.util.ScanLog
import dev.cannoli.scorza.util.StorageLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

sealed interface BootResult {
    data object Success : BootResult
    data class Failure(val message: String) : BootResult
}

@ActivityScoped
class BootInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val libraryConfig: dev.cannoli.scorza.config.library.LibraryConfig,
    private val cannoliDatabase: CannoliDatabase,
    private val scanScheduler: ScanScheduler,
    private val cannoliPaths: CannoliPathsProvider,
    @IoScope private val ioScope: CoroutineScope,
    private val installedCoreService: InstalledCoreService,
    private val settingsViewModel: SettingsViewModel,
    private val nav: NavigationController,
    private val launcherActions: LauncherActions,
    private val setupCoordinator: SetupCoordinator,
) {

    suspend fun run(onPhase: (BootPhase, Float, String) -> Unit): BootResult {
        val root = cannoliPaths.root
        val romDir = cannoliPaths.romDir
        val importPhase = if (hasLegacyData(root)) BootPhase.IMPORT else BootPhase.INITIAL_SCAN
        onPhase(BootPhase.LIBRARY_REFRESH, 0f, context.getString(R.string.boot_preparing))

        ScanLog.init(root.absolutePath)
        dev.cannoli.scorza.util.InputLog.init(root.absolutePath)
        KitchenLog.init(root.absolutePath)
        StorageLog.init(root.absolutePath)
        dev.cannoli.scorza.util.RommLog.init(root.absolutePath)
        dev.cannoli.scorza.util.ErrorLog.init(root.absolutePath)
        setupCoordinator.logStorageDiagnostics()
        // emulators.json, systems.json and the rest. A new install gets them empty, plus the
        // reference templates to copy from. Problems are logged and shown once the menu is up.
        libraryConfig.load()
        ioScope.launch { dev.cannoli.scorza.util.DirectoryLayout.ensure(root, romDir, context.assets, platformConfig) }

        val importer = Importer(
            context = context,
            cannoliRoot = root,
            romDirectory = romDir,
            db = cannoliDatabase,
            platformConfig = platformConfig,
            scanScheduler = scanScheduler,
            onProgress = ImportProgress { progress, label ->
                onPhase(importPhase, progress, label)
            },
            scanDisk = settings.scanLibraryAutomatically,
        )

        val result = withContext(Dispatchers.IO) { importer.run() }

        if (result is ImportResult.Failure) {
            ScanLog.write("ERROR import returned Failure: ${result.cause.message}")
            return BootResult.Failure(result.cause.message ?: context.getString(R.string.boot_import_failed))
        }

        ioScope.launch {
            installedCoreService.queryAllPackages()
        }

        withContext(Dispatchers.Main) {
            settingsViewModel.reinitialize(root, context.packageManager, context.packageName)

            nav.screenStack.clear()
            nav.screenStack.add(LauncherScreen.SystemList)
        }

        val shouldScanDisk = settings.scanLibraryAutomatically
        return suspendCancellableCoroutine { cont ->
            launcherActions.rescanSystemList(
                scanDisk = shouldScanDisk,
                onProgress = { tag, current, total ->
                    onPhase(BootPhase.LIBRARY_REFRESH, current.toFloat() / total.coerceAtLeast(1), tag)
                },
                onComplete = { cont.resume(BootResult.Success) },
            )
        }
    }

    private fun hasLegacyData(root: File): Boolean {
        val p = CannoliPaths(root)
        return p.collectionsDir.exists() ||
            p.raGameIdsFile.exists() ||
            p.raGameIdsLegacyFile.exists() ||
            p.recentlyPlayedFile.exists() ||
            p.configOrdering.exists() ||
            p.toolsDir.exists() ||
            p.portsDir.exists() ||
            p.platformCacheFile.exists() ||
            p.gameCacheFile.exists()
    }
}
