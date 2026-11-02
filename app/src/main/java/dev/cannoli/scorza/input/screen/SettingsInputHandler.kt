package dev.cannoli.scorza.input.screen

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.InputTesterController
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.PageJump
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.launcher.IntentAuditor
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.ui.components.KeyboardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class SettingsInputHandler @Inject constructor(
    private val nav: NavigationController,
    @IoScope private val ioScope: CoroutineScope,
    private val settings: SettingsRepository,
    private val platformConfig: PlatformConfig,
    private val setupCoordinator: SetupCoordinator,
    private val inputTesterController: InputTesterController,
    private val intentAuditor: IntentAuditor,
    private val settingsViewModel: SettingsViewModel,
    private val launcherActions: LauncherActions,
    private val activityActions: ActivityActions,
    @ApplicationContext private val context: Context,
    private val rommStore: dev.cannoli.scorza.romm.RommConnectionStore,
    private val cannoliPaths: CannoliPathsProvider,
) : ScreenInputHandler {

    override fun onUp() {
        settingsViewModel.moveSelection(-1)
    }

    override fun onDown() {
        settingsViewModel.moveSelection(1)
    }

    override fun onLeft() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.cycleSelected(-1, repeatCount = nav.lastKeyRepeatCount)
        } else {
            pageJump(-1)
        }
    }

    override fun onRight() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.cycleSelected(1, repeatCount = nav.lastKeyRepeatCount)
        } else {
            pageJump(1)
        }
    }

    override fun onConfirm() {
        if (!settingsViewModel.state.value.inSubList) {
            val cat = settingsViewModel.state.value.categories.getOrNull(settingsViewModel.state.value.categoryIndex)
            when {
                cat?.key == "about" -> nav.dialogState.value = DialogState.About()
                else -> settingsViewModel.enterCategory()
            }
            return
        }

        when (val key = settingsViewModel.enterSelected()) {
            "integrations_romm" -> {
                if (rommStore.token.isNullOrEmpty()) settingsViewModel.enterSubCategory("romm", dev.cannoli.scorza.R.string.settings_romm)
                else nav.dialogState.value = DialogState.RommConnected(
                    host = rommStore.host, username = rommStore.username, version = rommStore.serverVersion)
            }
            "status_bar" -> settingsViewModel.enterSubCategory("status_bar", dev.cannoli.scorza.R.string.settings_status_bar)
            "sd_root" -> pushDirectoryBrowser(BrowsePurpose.SD_ROOT, settings.sdCardRoot)
            "rom_directory" -> {
                val startPath = settings.romDirectory.ifEmpty { settings.sdCardRoot }
                pushDirectoryBrowser(BrowsePurpose.ROM_DIRECTORY, startPath)
            }
            "colors" -> nav.push(LauncherScreen.ColorList(colors = settingsViewModel.getColorEntries()))
            "controllers" -> nav.push(LauncherScreen.Controllers())
            "screen_geometry" -> settingsViewModel.enterSubCategory("screen_geometry", dev.cannoli.scorza.R.string.setting_screen_geometry)
            "logging" -> nav.push(LauncherScreen.LoggingSettings())
            "audit_emulator_intents" -> runIntentAudit()
            "icon_gallery" -> nav.push(LauncherScreen.IconGallery())
            "input_tester" -> {
                inputTesterController.enter()
                nav.push(LauncherScreen.InputTester)
            }
            "set_default_launcher" -> context.startActivity(
                Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "regenerate_system_folders" -> {
                val romDir = cannoliPaths.romDir
                val folders = platformConfig.romFolders()
                ioScope.launch {
                    val created = dev.cannoli.scorza.util.DirectoryLayout.scaffoldRomFolders(romDir, folders)
                    val msg = if (created == 0) {
                        context.getString(dev.cannoli.scorza.R.string.regenerate_system_folders_none)
                    } else {
                        context.resources.getQuantityString(
                            dev.cannoli.scorza.R.plurals.regenerate_system_folders_result, created, created
                        )
                    }
                    withContext(Dispatchers.Main) {
                        nav.dialogState.value = DialogState.SystemFoldersRegenerated(msg)
                    }
                }
            }
            "romm_host" -> {
                val current = rommStore.host
                nav.dialogState.value = DialogState.RenameInput(gameName = "romm_host", keyboard = KeyboardState(text = current, cursorPos = current.length))
            }
            "romm_pair" -> activityActions.startRommPairing(rommStore.host)
            "romm_pair_code" -> {
                nav.dialogState.value = DialogState.RenameInput(gameName = "romm_pair_code", keyboard = KeyboardState())
            }
            null -> {}
            else -> {
                when {
                    key.startsWith("color_") -> {
                        val entries = settingsViewModel.getColorEntries()
                        val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                        nav.push(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                        launcherActions.openColorPicker(key)
                    }
                    else -> {
                        val displayValue = settingsViewModel.getSelectedItemDisplayValue()
                        nav.dialogState.value = DialogState.RenameInput(
                            gameName = key,
                            keyboard = KeyboardState(text = displayValue, cursorPos = displayValue.length)
                        )
                    }
                }
            }
        }
    }

    override fun onBack() {
        if (settingsViewModel.state.value.inSubList) {
            settingsViewModel.save()
            settingsViewModel.exitSubList()
            launcherActions.rescanSystemList()
        } else {
            settingsViewModel.cancel()
            nav.pop()
        }
    }

    override fun onNorth() {
        val item = settingsViewModel.getSelectedItem()
        if (item?.key == "rom_directory" && settings.romDirectory.isNotEmpty()) {
            launcherActions.confirmRomDirectoryChange("")
            return
        }
        if (settingsViewModel.state.value.activeCategory == "screen_geometry") {
            settingsViewModel.resetScreenGeometry()
        }
    }

    private fun pageJump(direction: Int) {
        val state = settingsViewModel.state.value
        val newIdx = PageJump.compute(direction, state.categories.size, state.categoryIndex, nav.activeListState)
        if (newIdx != state.categoryIndex) settingsViewModel.setCategoryIndex(newIdx)
    }

    private fun pushDirectoryBrowser(purpose: BrowsePurpose, startPath: String) {
        val entries = setupCoordinator.listDirectories(startPath)
        nav.push(LauncherScreen.DirectoryBrowser(
            purpose = purpose,
            currentPath = startPath,
            entries = entries
        ))
    }

    private fun runIntentAudit() {
        ioScope.launch {
            val message = try {
                val result = intentAuditor.runAudit()
                "Audited ${result.totalInstalled} installed emulators; ${result.totalFailed} intents failed to resolve.\n\nReport: ${result.reportFile.absolutePath}"
            } catch (e: Exception) {
                "Audit failed: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                nav.dialogState.value = DialogState.IntentAuditResult(message)
            }
        }
    }
}
