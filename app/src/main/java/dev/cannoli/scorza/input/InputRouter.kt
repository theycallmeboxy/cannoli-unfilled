package dev.cannoli.scorza.input

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.input.screen.ControllerDetailInputHandler
import dev.cannoli.scorza.input.screen.ControllersInputHandler
import dev.cannoli.scorza.input.screen.EditButtonsInputHandler
import dev.cannoli.scorza.input.screen.GameListInputHandler
import dev.cannoli.scorza.input.screen.GuideInputHandler
import dev.cannoli.scorza.input.screen.LoggingSettingsInputHandler
import dev.cannoli.scorza.input.screen.InputTesterInputHandler
import dev.cannoli.scorza.input.screen.ScrollListInputHandler
import dev.cannoli.scorza.input.screen.SettingsInputHandler
import dev.cannoli.scorza.input.screen.OnboardingInputHandler
import dev.cannoli.scorza.input.screen.SaveSlotsInputHandler
import dev.cannoli.scorza.input.screen.SystemListInputHandler
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.CREDITS_ROOT_ROWS
import dev.cannoli.scorza.ui.components.CreditsRootRow
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.input.runtime.InputDispatcher
import dev.cannoli.scorza.util.NaturalSort
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.ui.components.KeyboardState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class InputRouter @Inject constructor(
    private val nav: NavigationController,
    private val dialogHandler: DialogInputHandler,
    val systemListHandler: SystemListInputHandler,
    val gameListHandler: GameListInputHandler,
    val settingsHandler: SettingsInputHandler,
    val onboardingHandler: OnboardingInputHandler,
    val inputTesterHandler: InputTesterInputHandler,
    val saveSlotsHandler: SaveSlotsInputHandler,
    val guideHandler: GuideInputHandler,
    val controllerDetailHandler: ControllerDetailInputHandler,
    val controllersHandler: ControllersInputHandler,
    val editButtonsHandler: EditButtonsInputHandler,
    val loggingSettingsHandler: LoggingSettingsInputHandler,
    private val scrollListFactory: ScrollListInputHandler.Factory,
    private val launcherActions: LauncherActions,
    private val folderEditor: FolderEditor,
    private val emulatorPickers: EmulatorPickers,
    private val screenInputRegistry: dev.cannoli.scorza.input.runtime.ScreenInputRegistry,
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel,
    private val rommDownloader: dev.cannoli.scorza.romm.download.RommDownloader,
    private val osdController: dev.cannoli.ui.components.OsdController,
    @IoScope private val ioScope: CoroutineScope,
) {
    fun wire(dispatcher: InputDispatcher) {
        gameListHandler.buildContextOptions = dialogHandler::buildGameContextOptions
        saveSlotsHandler.onBackToContextMenu = { dialogHandler.openRommSavesMenu(MENU_SAVE_SLOTS) }
        dialogHandler.openGuides = guideHandler::startGuides

        // Launcher overrides onSelectUp because the select-hold cancel + nav-flag reset is
        // specific to launcher state; the generic helper cannot know about it.
        // Resolve dedicated screen handlers synchronously from nav state so input is not lost in
        // the recompose gap when a screen is pushed off the input cadence (e.g. the save-state
        // picker from a hold timer). Scrollable screens keep their registry-backed instances. The
        // IGM wires without a resolver, which resets this so its registry-top dispatch is restored.
        dispatcher.wireToRegistry(
            dialogHandler = dialogHandler,
            onSelectUpOverride = { onSelectUp() },
            screenResolver = { activeScreenHandler() },
        )
    }

    private fun activeScreenHandler(): ScreenInputHandler =
        dedicatedHandlerFor(nav.currentScreen) ?: screenInputRegistry.top

    private fun dedicatedHandlerFor(screen: LauncherScreen): ScreenInputHandler? = when (screen) {
        is LauncherScreen.SystemList -> systemListHandler
        is LauncherScreen.GameList -> gameListHandler
        is LauncherScreen.Settings -> settingsHandler
        is LauncherScreen.InputTester -> inputTesterHandler
        is LauncherScreen.SaveSlots -> saveSlotsHandler
        is LauncherScreen.GuidePicker -> guideHandler
        is LauncherScreen.Guide -> guideHandler
        is LauncherScreen.Controllers -> controllersHandler
        is LauncherScreen.ControllerDetail -> controllerDetailHandler
        is LauncherScreen.EditButtons -> editButtonsHandler
        is LauncherScreen.LoggingSettings -> loggingSettingsHandler
        is LauncherScreen.OnboardingPermissions -> onboardingHandler
        is LauncherScreen.DirectoryBrowser -> onboardingHandler
        is LauncherScreen.RommGameDetail -> object : ScreenInputHandler {
            override fun onWest() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                if (!dev.cannoli.scorza.romm.download.RommManual.isAvailable(s.game)) return
                rommDownloader.enqueue(listOf(dev.cannoli.scorza.romm.download.RommDownloadItem(
                    rommId = s.game.id, game = s.game, tag = s.tag,
                    kind = dev.cannoli.scorza.romm.download.RommDownloadKind.MANUAL)))
                dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
                osdController.show(context.getString(dev.cannoli.ui.R.string.romm_osd_manual_queued))
            }
            override fun onBack() { nav.pop() }
            override fun onUp() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                nav.replaceTop(s.copy(scrollStep = (s.scrollStep - 1).coerceAtLeast(0)))
            }
            override fun onDown() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                nav.replaceTop(s.copy(scrollStep = s.scrollStep + 1))
            }
            override fun onNorth() {
                val s = nav.currentScreen as? LauncherScreen.RommGameDetail ?: return
                if (s.versionCount > 1) {
                    ioScope.launch {
                        val members = rommBrowseViewModel.groupMembers(s.groupKey)
                        val presentIds = rommBrowseViewModel.presentIdsForTag(s.tag, members)
                        val entries = members.map { g ->
                            dev.cannoli.scorza.ui.screens.RommVariantEntry(
                                game = g,
                                label = g.fsName.substringBeforeLast('.'),
                                present = g.id in presentIds,
                                isPrimary = g.id == s.game.id,
                            )
                        }
                        val sorted = entries.sortedWith(
                            compareByDescending<dev.cannoli.scorza.ui.screens.RommVariantEntry> { it.isPrimary }
                                .then(compareBy(NaturalSort) { it.label })
                        )
                        withContext(Dispatchers.Main) {
                            nav.dialogState.value = dev.cannoli.scorza.ui.screens.DialogState.RommVersionPicker(
                                gameName = s.game.name, tag = s.tag, members = sorted)
                        }
                    }
                    return
                }
                if (s.localState != dev.cannoli.scorza.romm.LocalState.REMOTE) return
                rommDownloader.enqueue(listOf(dev.cannoli.scorza.romm.download.RommDownloadItem(
                    rommId = s.game.id, game = s.game, tag = s.tag,
                    kind = dev.cannoli.scorza.romm.download.RommDownloadKind.ROM)))
                dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
                osdController.show(context.getString(dev.cannoli.ui.R.string.romm_osd_download_queued))
            }
        }
        else -> null
    }

    fun onSelectUp() {
        dialogHandler.cancelSelectHold()
        gameListHandler.cancelSelectHoldTimer()

        val dialogConsumed = dialogHandler.onSelectUp()
        if (!dialogConsumed) activeScreenHandler().onSelectUp()

        nav.selectDown = false
        nav.selectHeld = false
    }

    /**
     * Returns the appropriate handler for a ScrollableScreen instance. Used by [AppNavGraph] to
     * push a handler via ScreenInput for screens that don't have a dedicated handler class --
     * the handler is generated inline from the [scrollable] factory each time the screen is
     * composed. Other screens (with dedicated handler classes like [SystemListInputHandler])
     * push their handler directly via this router's public fields, bypassing this method.
     */
    fun currentHandler(): ScreenInputHandler = when (val screen = nav.currentScreen) {
        is LauncherScreen.ScrollableScreen -> scrollableHandlerFor(screen)
        else -> object : ScreenInputHandler {}
    }

    /**
     * Per-screen handler factory for everything that implements [LauncherScreen.ScrollableScreen].
     *
     * The actual logic lives in the named *Handler() methods below; each one is a thin wrapper
     * around [scrollable], which buries the [nav.currentScreen] cast and the default move/back
     * boilerplate so each screen only has to spell out the parts that differ.
     */
    private fun scrollableHandlerFor(screen: LauncherScreen.ScrollableScreen): ScreenInputHandler = when (screen) {
        is LauncherScreen.ColorList         -> colorListHandler()
        is LauncherScreen.CollectionPicker  -> collectionPickerHandler()
        is LauncherScreen.ChildPicker       -> childPickerHandler()
        is LauncherScreen.FolderPicker      -> scrollable<LauncherScreen.FolderPicker>(
            onConfirm = { folderEditor.confirmMove(this) },
        )
        is LauncherScreen.EmulatorPicker    -> scrollable<LauncherScreen.EmulatorPicker>(
            onConfirm = { emulatorPickers.confirm(this) { if (fromGameMenu) dialogHandler.restoreContextMenu() } },
            onBack = {
                nav.pop()
                if (fromGameMenu) dialogHandler.restoreContextMenu()
            },
        )
        is LauncherScreen.AppPicker         -> scrollable<LauncherScreen.AppPicker>(
            onConfirm = {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            },
            onBack = { folderEditor.confirmAppPicker(this) },
        )
        is LauncherScreen.Credits           -> creditsHandler()
        is LauncherScreen.CreditsSection    -> creditsSectionHandler()
        is LauncherScreen.IconGallery       -> scrollable<LauncherScreen.IconGallery>()
        is LauncherScreen.RommPlatformList      -> rommPlatformListHandler()
        is LauncherScreen.RommGameList          -> rommGameListHandler()
        is LauncherScreen.RommGlobalSearch      -> rommGlobalSearchHandler()
        is LauncherScreen.RommFirmwareList      -> rommFirmwareListHandler()
        is LauncherScreen.RommCollectionGroups  -> rommCollectionGroupsHandler()
        is LauncherScreen.RommVirtualTypes      -> rommVirtualTypesHandler()
        is LauncherScreen.RommCollectionList    -> rommCollectionListHandler()
        is LauncherScreen.RommCollectionGameList -> rommCollectionGameListHandler()
        else -> object : ScreenInputHandler {}
    }

    private inline fun <reified T> scrollable(
        crossinline onConfirm: T.() -> Unit = {},
        crossinline onBack: T.() -> Unit = { nav.pop() },
        crossinline onMove: T.(Int) -> LauncherScreen = { idx -> withScroll(selectedIndex = idx, scrollTarget = scrollTarget) },
        noinline onStart: (T.() -> Unit)? = null,
        noinline onWest: (T.() -> Unit)? = null,
        noinline onNorth: (T.() -> Unit)? = null,
        noinline onLeft: (T.() -> Unit)? = null,
        noinline onRight: (T.() -> Unit)? = null,
        noinline onSelect: (T.() -> Unit)? = null,
        noinline onR1: (T.() -> Unit)? = null,
    ): ScrollListInputHandler where T : LauncherScreen, T : LauncherScreen.ScrollableScreen {
        val current: () -> T? = { nav.currentScreen as? T }
        return scrollListFactory.create(
            itemCount = { current()?.itemCount ?: 0 },
            selectedIndex = { current()?.selectedIndex ?: 0 },
            onMove = { idx -> current()?.let { nav.replaceTop(it.onMove(idx)) } ?: Unit },
            onConfirm = { current()?.onConfirm() ?: Unit },
            onBack = { current()?.onBack() ?: nav.pop() },
            onStart = onStart?.let { fn -> { current()?.fn() ?: Unit } },
            onWest = onWest?.let { fn -> { current()?.fn() ?: Unit } },
            onNorth = onNorth?.let { fn -> { current()?.fn() ?: Unit } },
            onLeft = onLeft?.let { fn -> { current()?.fn() ?: Unit } },
            onRight = onRight?.let { fn -> { current()?.fn() ?: Unit } },
            onSelect = onSelect?.let { fn -> { current()?.fn() ?: Unit } },
            onR1 = onR1?.let { fn -> { current()?.fn() ?: Unit } },
        )
    }

    private fun colorListHandler() = scrollable<LauncherScreen.ColorList>(
        onConfirm = { colors.getOrNull(selectedIndex)?.let { launcherActions.openColorPicker(it.key) } },
    )

    private fun collectionPickerHandler() = scrollable<LauncherScreen.CollectionPicker>(
        onConfirm = {
            if (collectionIds.isNotEmpty()) {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            }
        },
        onBack = { dialogHandler.onCollectionPickerConfirm(this) },
        onWest = { nav.dialogState.value = DialogState.NewCollectionInput(gamePaths = gamePaths) },
    )

    private fun childPickerHandler() = scrollable<LauncherScreen.ChildPicker>(
        onConfirm = {
            if (collectionIds.isNotEmpty()) {
                val newChecked = if (selectedIndex in checkedIndices) checkedIndices - selectedIndex
                                 else checkedIndices + selectedIndex
                nav.replaceTop(copy(checkedIndices = newChecked))
            }
        },
        onBack = { dialogHandler.onChildPickerConfirm(this) },
    )

    private fun creditsHandler() = scrollable<LauncherScreen.Credits>(
        onConfirm = {
            val row = CREDITS_ROOT_ROWS.getOrNull(selectedIndex)
            if (row is CreditsRootRow.Category) {
                nav.push(LauncherScreen.CreditsSection(row.category))
            }
        },
    )

    private fun creditsSectionHandler() = scrollable<LauncherScreen.CreditsSection>()

    private fun rommPlatformListHandler() = scrollable<LauncherScreen.RommPlatformList>(
        onConfirm = {
            val showCollectionsRow = rommBrowseViewModel.hasAnyCollections()
            if (showCollectionsRow && selectedIndex == 0) {
                when (val target = rommBrowseViewModel.collectionEntryTarget()) {
                    is dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.CollectionEntry.Landing ->
                        nav.push(LauncherScreen.RommCollectionGroups())
                    is dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.CollectionEntry.VirtualTypes ->
                        nav.push(LauncherScreen.RommVirtualTypes())
                    is dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.CollectionEntry.Group ->
                        nav.push(LauncherScreen.RommCollectionList(group = target.group))
                }
            } else {
                val offset = if (showCollectionsRow) 1 else 0
                val platform = rommBrowseViewModel.platforms.value.getOrNull(selectedIndex - offset) ?: return@scrollable
                ioScope.launch {
                    rommBrowseViewModel.openPlatform(platform)
                    withContext(Dispatchers.Main) { nav.push(LauncherScreen.RommGameList(platform = platform)) }
                }
            }
        },
        onBack = {
            rommDownloader.clearFinished()
            nav.pop()
            launcherActions.refreshLauncherLists()
        },
        onR1 = {
            nav.dialogState.value = DialogState.RenameInput(
                gameName = "romm_global_search",
            )
        },
    )

    private fun rommCollectionGroupsHandler() = scrollable<LauncherScreen.RommCollectionGroups>(
        onConfirm = {
            val order = listOf(
                dev.cannoli.scorza.romm.RommCollectionGroup.USER,
                dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL,
                dev.cannoli.scorza.romm.RommCollectionGroup.SMART,
            )
            val enabled = order.filter { it in rommBrowseViewModel.enabledGroups() }
            val group = enabled.getOrNull(selectedIndex) ?: return@scrollable
            if (group == dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL) nav.push(LauncherScreen.RommVirtualTypes())
            else nav.push(LauncherScreen.RommCollectionList(group = group))
        },
        onBack = { nav.pop() },
    )

    private fun rommVirtualTypesHandler() = scrollable<LauncherScreen.RommVirtualTypes>(
        onConfirm = {
            val type = rommBrowseViewModel.virtualTypeCounts.value.getOrNull(selectedIndex)?.first ?: return@scrollable
            nav.push(LauncherScreen.RommCollectionList(group = dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL, virtualType = type))
        },
        onBack = { nav.pop() },
    )

    private fun rommCollectionListHandler() = scrollable<LauncherScreen.RommCollectionList>(
        onConfirm = {
            val key = group.name + (virtualType?.let { ":$it" } ?: "")
            val loaded = rommBrowseViewModel.collectionList.value
            if (loaded?.id != key) return@scrollable
            val collection = loaded.rows.getOrNull(selectedIndex) ?: return@scrollable
            ioScope.launch {
                rommBrowseViewModel.openCollection(collection)
                withContext(Dispatchers.Main) { nav.push(LauncherScreen.RommCollectionGameList(collection = collection)) }
            }
        },
        onBack = { nav.pop() },
    )

    private fun rommCollectionGameListHandler() = scrollable<LauncherScreen.RommCollectionGameList>(
        onConfirm = {
            val row = rommBrowseViewModel.collectionGames.value?.rows?.getOrNull(selectedIndex) ?: return@scrollable
            if (rommBrowseViewModel.isMultiSelect()) {
                rommBrowseViewModel.toggleChecked(row.game.id)
            } else {
                nav.push(LauncherScreen.RommGameDetail(
                    game = row.game,
                    localState = row.localState,
                    platformName = row.platform.displayName,
                    tag = row.platform.cannoliTag,
                    groupKey = row.groupKey,
                    versionCount = row.versionCount,
                ))
            }
        },
        onBack = {
            if (rommBrowseViewModel.isMultiSelect()) rommBrowseViewModel.cancelMultiSelect()
            else if (search.isNotEmpty()) nav.replaceTop(copy(search = "", selectedIndex = 0, scrollTarget = 0))
            else nav.pop()
        },
        onStart = {
            if (!rommBrowseViewModel.isMultiSelect()) return@scrollable
            val ids = rommBrowseViewModel.confirmMultiSelect()
            val rows = (rommBrowseViewModel.collectionGames.value?.rows ?: emptyList())
                .filter { it.game.id in ids }
            if (rows.isEmpty()) return@scrollable
            rommDownloader.enqueue(rows.map {
                dev.cannoli.scorza.romm.download.RommDownloadItem(rommId = it.game.id, game = it.game, tag = it.platform.cannoliTag)
            })
            dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
            osdController.show(context.resources.getQuantityString(
                dev.cannoli.ui.R.plurals.romm_osd_downloads_queued, rows.size, rows.size))
        },
        onR1 = {
            nav.dialogState.value = DialogState.RenameInput(
                gameName = "romm_collection_search",
                searchScope = collection.name,
                keyboard = KeyboardState(text = search, cursorPos = search.length),
            )
        },
        onSelect = {
            if (rommBrowseViewModel.isMultiSelect()) rommBrowseViewModel.cancelMultiSelect()
            else rommBrowseViewModel.enterMultiSelect(
                dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.MultiSelectSource.COLLECTION,
                rommBrowseViewModel.collectionGames.value?.rows?.getOrNull(selectedIndex)?.game?.id)
        },
    )

    private fun rommGameListHandler() = scrollable<LauncherScreen.RommGameList>(
        onConfirm = {
            val row = rommBrowseViewModel.games.value?.rows?.getOrNull(selectedIndex) ?: return@scrollable
            if (rommBrowseViewModel.isMultiSelect()) {
                rommBrowseViewModel.toggleChecked(row.game.id)
            } else {
                nav.push(LauncherScreen.RommGameDetail(
                    game = row.game,
                    localState = row.localState,
                    platformName = platform.displayName,
                    tag = platform.cannoliTag,
                    groupKey = row.groupKey,
                    versionCount = row.versionCount,
                ))
            }
        },
        onBack = {
            if (rommBrowseViewModel.isMultiSelect()) rommBrowseViewModel.cancelMultiSelect()
            else if (search.isNotEmpty()) nav.replaceTop(copy(search = "", selectedIndex = 0, scrollTarget = 0))
            else nav.pop()
        },
        onStart = {
            if (!rommBrowseViewModel.isMultiSelect()) return@scrollable
            val ids = rommBrowseViewModel.confirmMultiSelect()
            val games = (rommBrowseViewModel.games.value?.rows ?: emptyList())
                .filter { it.game.id in ids }.map { it.game }
            if (games.isEmpty()) return@scrollable
            rommDownloader.enqueue(games.map {
                dev.cannoli.scorza.romm.download.RommDownloadItem(rommId = it.id, game = it, tag = platform.cannoliTag)
            })
            dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
            osdController.show(context.resources.getQuantityString(
                dev.cannoli.ui.R.plurals.romm_osd_downloads_queued, games.size, games.size))
        },
        onWest = { if (platform.firmwareCount > 0) nav.push(LauncherScreen.RommFirmwareList(platform = platform)) },
        onR1 = {
            nav.dialogState.value = DialogState.RenameInput(
                gameName = "romm_search",
                searchScope = platform.displayName,
                keyboard = KeyboardState(text = search, cursorPos = search.length),
            )
        },
        onSelect = {
            if (rommBrowseViewModel.isMultiSelect()) rommBrowseViewModel.cancelMultiSelect()
            else rommBrowseViewModel.enterMultiSelect(
                dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.MultiSelectSource.PLATFORM,
                rommBrowseViewModel.games.value?.rows?.getOrNull(selectedIndex)?.game?.id)
        },
    )

    private fun rommGlobalSearchHandler() = scrollable<LauncherScreen.RommGlobalSearch>(
        onConfirm = {
            val row = rommBrowseViewModel.searchResults.value?.rows?.getOrNull(selectedIndex) ?: return@scrollable
            val platform = rommBrowseViewModel.allPlatforms.value.firstOrNull { it.id == row.game.platformId }
            nav.push(LauncherScreen.RommGameDetail(
                game = row.game,
                localState = row.localState,
                platformName = platform?.displayName ?: "",
                tag = platform?.cannoliTag ?: "",
                groupKey = row.groupKey,
                versionCount = row.versionCount,
            ))
        },
    )

    private fun rommFirmwareListHandler() = scrollable<LauncherScreen.RommFirmwareList>(
        onConfirm = {
            val row = rows.getOrNull(selectedIndex) ?: return@scrollable
            val id = row.firmware.id
            nav.replaceTop(copy(checkedIds = if (id in checkedIds) checkedIds - id else checkedIds + id))
        },
        onBack = { nav.pop() },
        onStart = {
            val chosen = rows.filter { it.firmware.id in checkedIds }
            if (chosen.isEmpty()) return@scrollable
            rommDownloader.enqueue(chosen.map {
                dev.cannoli.scorza.romm.download.RommDownloadItem(
                    rommId = it.firmware.id,
                    tag = platform.cannoliTag,
                    kind = dev.cannoli.scorza.romm.download.RommDownloadKind.FIRMWARE,
                    firmware = it.firmware,
                )
            })
            dev.cannoli.scorza.romm.download.RommDownloadManager.ensureStarted(context)
            nav.pop()
        },
    )
}
