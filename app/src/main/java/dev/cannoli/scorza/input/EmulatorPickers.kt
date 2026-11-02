package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.EmulatorChoiceEditor
import dev.cannoli.scorza.config.library.GameSystem
import dev.cannoli.scorza.config.library.LibraryConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.launcher.ChoiceMark
import dev.cannoli.scorza.launcher.EmulatorChoices
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.romm.sync.RomKeys
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "Launch With" on a game's menu and "Default Emulator" on a system's: both list the system's
 * emulators from systems.json with what's missing marked, and write the pick through
 * [EmulatorChoiceEditor]. The library is updated as the file is written, so the next launch and
 * the menu rows use the new choice.
 */
@ActivityScoped
class EmulatorPickers @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoScope private val ioScope: CoroutineScope,
    private val nav: NavigationController,
    private val libraryConfig: LibraryConfig,
    private val platformConfig: PlatformConfig,
    private val editor: EmulatorChoiceEditor,
    private val installedCoreService: InstalledCoreService,
    private val paths: CannoliPathsProvider,
) {
    /** The "Launch With" menu row for [rom], showing what it launches with, or null when its system has no emulator. */
    fun gameMenuRow(rom: Rom): String? {
        val system = systemWithEmulators(rom.platformTag) ?: return null
        val library = libraryConfig.library
        val choice = library.choiceFor(system, romPath(rom)) ?: return null
        return "$MENU_LAUNCH_WITH\t${library.choiceLabel(choice)}"
    }

    /**
     * The "Default Emulator" menu row for the system [tag], or null when it has no emulator. Its
     * value says when the default's app isn't installed or its core is missing.
     */
    fun systemMenuRow(tag: String): String? {
        val system = systemWithEmulators(tag) ?: return null
        val library = libraryConfig.library
        val mark = when (defaultMark(system)) {
            ChoiceMark.APP_MISSING -> " · " + context.getString(dev.cannoli.ui.R.string.value_not_installed)
            ChoiceMark.CORE_MISSING -> " · " + context.getString(dev.cannoli.ui.R.string.value_core_missing)
            ChoiceMark.NONE -> ""
        }
        return "$MENU_DEFAULT_EMULATOR\t${library.choiceLabel(system.emulators.first())}$mark"
    }

    /** What would stop [system]'s default emulator from launching. */
    fun defaultMark(system: GameSystem): ChoiceMark = EmulatorChoices.defaultMark(
        libraryConfig.library, system,
        isInstalled = context::isPackageInstalled,
        coreStatus = installedCoreService::coreStatus,
    )

    fun openForGame(rom: Rom, fromGameMenu: Boolean) {
        val system = systemWithEmulators(rom.platformTag) ?: return
        val path = romPath(rom)
        val current = libraryConfig.library.choiceFor(system, path)?.choiceName
        open(context.getString(dev.cannoli.ui.R.string.launch_with_title, rom.displayName), system, path, current, fromGameMenu)
    }

    fun openForSystem(tag: String) {
        val system = systemWithEmulators(tag) ?: return
        open(
            context.getString(dev.cannoli.ui.R.string.default_emulator_title, system.name),
            system, romPath = null, current = system.emulators.first().choiceName, fromGameMenu = false,
        )
    }

    /** Saves the highlighted choice, then closes the picker and calls [onClosed] once it is written. */
    fun confirm(screen: LauncherScreen.EmulatorPicker, onClosed: () -> Unit) {
        val choice = screen.choices.getOrNull(screen.selectedIndex) ?: return
        nav.pop()
        ioScope.launch {
            val system = libraryConfig.library.systems[screen.systemId]
            val failure = when {
                system == null -> "systems.json has no system \"${screen.systemId}\""
                screen.romPath != null -> editor.setGameChoice(system, screen.romPath, choice.name)
                else -> editor.makeDefault(system.id, choice.name)
            }
            withContext(Dispatchers.Main) {
                if (failure != null) nav.dialogState.value = DialogState.ConfigEditError(failure) else onClosed()
            }
        }
    }

    private fun open(title: String, system: GameSystem, romPath: String?, current: String?, fromGameMenu: Boolean) {
        val choices = EmulatorChoices.build(
            libraryConfig.library, system, current,
            isInstalled = context::isPackageInstalled,
            coreStatus = installedCoreService::coreStatus,
        )
        nav.dialogState.value = DialogState.None
        nav.push(
            LauncherScreen.EmulatorPicker(
                title = title,
                systemId = system.id,
                romPath = romPath,
                choices = choices,
                fromGameMenu = fromGameMenu,
                selectedIndex = choices.indexOfFirst { it.isCurrent }.coerceAtLeast(0),
            )
        )
    }

    private fun systemWithEmulators(tag: String): GameSystem? =
        platformConfig.systemFor(tag)?.takeIf { it.emulators.isNotEmpty() }

    // The same key the launcher looks overrides up by.
    private fun romPath(rom: Rom): String = RomKeys.relativeKey(rom.path, paths.romDir)
}
