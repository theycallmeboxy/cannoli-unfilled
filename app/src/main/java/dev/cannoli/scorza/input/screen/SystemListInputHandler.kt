package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.input.FolderEditor
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.MENU_ADD_APPS
import dev.cannoli.scorza.input.MENU_DELETE
import dev.cannoli.scorza.input.MENU_MOVE_TO_FOLDER
import dev.cannoli.scorza.input.MENU_NEW_FOLDER
import dev.cannoli.scorza.input.MENU_REMOVE_FROM_FOLDER
import dev.cannoli.scorza.input.MENU_ADD_FAVORITE
import dev.cannoli.scorza.input.MENU_DELETE_GAME
import dev.cannoli.scorza.input.MENU_DOWNLOAD_ART
import dev.cannoli.scorza.input.MENU_EMULATOR_OVERRIDE
import dev.cannoli.scorza.input.MENU_MANAGE_COLLECTIONS
import dev.cannoli.scorza.input.MENU_REMOVE_FAVORITE
import dev.cannoli.scorza.input.MENU_RENAME
import dev.cannoli.scorza.input.PageJump
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import javax.inject.Inject

@ActivityScoped
class SystemListInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val settings: SettingsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val platformConfig: PlatformConfig,
    private val systemListViewModel: SystemListViewModel,
    private val gameListViewModel: GameListViewModel,
    private val settingsViewModel: SettingsViewModel,
    private val launcherActions: LauncherActions,
    private val folderEditor: FolderEditor,
    private val rommConnectionStore: dev.cannoli.scorza.romm.RommConnectionStore,
) : ScreenInputHandler {

    private var selectDown = false

    override fun onUp() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveUp()
        else systemListViewModel.moveSelection(-1)
    }

    override fun onDown() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveDown()
        else systemListViewModel.moveSelection(1)
    }

    override fun onLeft() {
        if (!systemListViewModel.isReorderMode()) pageJump(-1)
    }

    override fun onRight() {
        if (!systemListViewModel.isReorderMode()) pageJump(1)
    }

    override fun onL1() {}

    override fun onR1() {
        if (systemListViewModel.isReorderMode()) return
        nav.dialogState.value = DialogState.RenameInput(
            gameName = "launcher_global_search",
        )
    }

    override fun onConfirm() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else onSystemListConfirm()
    }

    override fun onBack() {
        if (systemListViewModel.isReorderMode()) {
            systemListViewModel.cancelReorder(
                showRecentlyPlayed = settings.showRecentlyPlayed,
                showFavorites = settings.showFavorites,
            )
        } else if (systemListViewModel.isInFolder()) {
            if (nav.navigating) return
            nav.navigating = true
            systemListViewModel.exitFolder { nav.navigating = false }
        } else if (settings.mainMenuQuit) {
            nav.dialogState.value = DialogState.QuitConfirm
        }
    }

    override fun onStart() {
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else onSystemListContextMenu()
    }

    override fun onSelect() {
        if (selectDown) return
        selectDown = true
        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
        else systemListViewModel.enterReorderMode()
    }

    override fun onSelectUp() {
        selectDown = false
    }

    override fun onNorth() {
        systemListViewModel.savePosition()
        settingsViewModel.load()
        nav.screenStack.add(LauncherScreen.Settings)
    }

    override fun onWest() {
        if (systemListViewModel.state.value.items.isEmpty() && !systemListViewModel.isInFolder()) {
            launcherActions.openKitchen()
        } else if (!systemListViewModel.isReorderMode()) {
            folderEditor.promptNewFolder()
        }
    }

    private fun pageJump(direction: Int) {
        val state = systemListViewModel.state.value
        val newIdx = PageJump.compute(direction, state.items.size, state.selectedIndex, nav.activeListState)
        if (newIdx != state.selectedIndex) systemListViewModel.setSelectedIndex(newIdx)
    }

    private fun onSystemListConfirm() {
        if (nav.navigating) return
        systemListViewModel.savePosition()
        when (val item = systemListViewModel.getSelectedItem()) {
            is SystemListViewModel.ListItem.RecentlyPlayedItem -> {
                nav.navigating = true
                gameListViewModel.loadRecentlyPlayed {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.FavoritesItem -> {
                nav.navigating = true
                gameListViewModel.loadFavorites {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                nav.navigating = true
                gameListViewModel.loadCollectionsList {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                nav.navigating = true
                gameListViewModel.loadPlatform(item.platform.tag, item.platform.allTags) {
                    nav.screenStack.add(LauncherScreen.GameList)
                    nav.navigating = false
                }
            }
            is SystemListViewModel.ListItem.GameItem -> {
                val recentKey = item.recentKey
                val errorDialog = launcherActions.launchSelected(item.item)
                if (errorDialog != null) {
                    nav.dialogState.value = errorDialog
                } else if (nav.dialogState.value is DialogState.SaveSyncChecking) {
                    launcherActions.recordPendingRecent(recentKey, false)
                } else {
                    launcherActions.recordRecentlyPlayedByPath(recentKey)
                }
            }
            is SystemListViewModel.ListItem.FolderItem -> {
                nav.navigating = true
                systemListViewModel.enterFolder(item.name) { nav.navigating = false }
            }
            else -> {}
        }
    }

    private fun onSystemListContextMenu() {
        val item = systemListViewModel.getSelectedItem()
        if (item == null) {
            // An empty folder has no row to open a menu on, but can still be filled.
            val path = systemListViewModel.state.value.folderPath
            if (path.isNotEmpty()) {
                nav.dialogState.value = DialogState.ContextMenu(gameName = path.last(), options = listOf(MENU_ADD_APPS, MENU_NEW_FOLDER))
            }
            return
        }
        if (item is SystemListViewModel.ListItem.GameItem) {
            val ref = resolveItemRef(item)
            val isFav = ref?.let { r ->
                when (r) {
                    is dev.cannoli.scorza.db.LibraryRef.Rom -> collectionsRepository.isRomFavorited(r.id)
                    is dev.cannoli.scorza.db.LibraryRef.App -> collectionsRepository.isAppFavorited(r.id)
                }
            } == true
            nav.pendingMainListItem = item.item
            val menuName = item.displayName
            val options = buildList {
                add(if (isFav) MENU_REMOVE_FAVORITE else MENU_ADD_FAVORITE)
                add(MENU_MANAGE_COLLECTIONS)
                if (item.item is ListItem.RomItem) {
                    add(MENU_EMULATOR_OVERRIDE)
                    add(MENU_DELETE_GAME)
                } else {
                    // An app on the main list is a folders.json entry.
                    add(MENU_MOVE_TO_FOLDER)
                    add(MENU_REMOVE_FROM_FOLDER)
                }
            }
            nav.dialogState.value = DialogState.ContextMenu(gameName = menuName, options = options)
            return
        }
        val name = when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> item.platform.displayName
            is SystemListViewModel.ListItem.FolderItem -> item.name
            else -> return
        }
        val options = buildList {
            add(MENU_RENAME)
            if (item is SystemListViewModel.ListItem.FolderItem) {
                add(MENU_ADD_APPS)
                add(MENU_MOVE_TO_FOLDER)
                add(MENU_NEW_FOLDER)
                add(MENU_DELETE)
            } else {
                add(MENU_MOVE_TO_FOLDER)
                add(MENU_NEW_FOLDER)
                if (rommConnectionStore.isConfigured) add(MENU_DOWNLOAD_ART)
            }
        }
        nav.dialogState.value = DialogState.ContextMenu(
            gameName = name,
            options = options
        )
    }

    private fun resolveItemRef(item: SystemListViewModel.ListItem.GameItem): dev.cannoli.scorza.db.LibraryRef? {
        return when (val inner = item.item) {
            is ListItem.RomItem -> dev.cannoli.scorza.db.LibraryRef.Rom(inner.rom.id)
            is ListItem.AppItem -> dev.cannoli.scorza.db.LibraryRef.App(inner.app.id)
            else -> null
        }
    }

}
