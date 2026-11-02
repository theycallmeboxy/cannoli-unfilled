package dev.cannoli.scorza.navigation

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.input.runtime.confirmButton
import dev.cannoli.scorza.input.runtime.labelSet
import dev.cannoli.scorza.ui.LocalViewportInsets
import dev.cannoli.scorza.ui.ViewportInsetsPx
import dev.cannoli.scorza.ui.components.CREDITS_ROOT_ROWS
import dev.cannoli.scorza.ui.components.CreditsCategory
import dev.cannoli.scorza.ui.components.CreditsCategoryOverlay
import dev.cannoli.scorza.ui.components.CreditsOverlay
import dev.cannoli.scorza.ui.components.creditsItemCount
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.effectiveViewportPadding
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.ControllerDetailScreen
import dev.cannoli.scorza.ui.screens.ControllersScreen
import dev.cannoli.scorza.ui.screens.EditButtonsScreen
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.DirectoryBrowserScreen
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.GuidePickerScreen
import dev.cannoli.scorza.ui.screens.GuideViewerScreen
import dev.cannoli.scorza.ui.screens.InputTesterScreen
import dev.cannoli.scorza.ui.screens.LoggingSettingsScreen
import dev.cannoli.scorza.ui.screens.KeyboardHost
import dev.cannoli.scorza.ui.screens.PortraitMarginOverlay
import dev.cannoli.scorza.ui.screens.SaveSlotsScreen
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.viewmodel.ControllersViewModel
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.InputTesterViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.List
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.LocalStatusBarLeftEdge
import dev.cannoli.ui.components.LocalStatusClockRightEdge
import dev.cannoli.ui.components.MessageOverlay
import dev.cannoli.ui.components.OsdHost
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.PillRowText
import dev.cannoli.ui.components.RommCacheSyncStatus
import dev.cannoli.ui.components.StatusBar
import dev.cannoli.ui.components.LocalListRhythm
import dev.cannoli.ui.components.LocalUntitledListRhythm
import dev.cannoli.ui.components.bottomBarHeight
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.pillLineHeightSp
import dev.cannoli.ui.components.pillNominalGap
import dev.cannoli.ui.components.pillScaleFor
import dev.cannoli.ui.components.pillVerticalPadding
import dev.cannoli.ui.components.screenInsets
import dev.cannoli.ui.components.screenTitleMetrics
import dev.cannoli.ui.components.solveListRhythm
import dev.cannoli.ui.theme.CannoliColors
import dev.cannoli.ui.theme.CannoliIcons
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliFont
import dev.cannoli.ui.theme.LocalPillScale
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.LocalScaleFactor
import dev.cannoli.ui.theme.Radius
import dev.cannoli.ui.theme.Spacing
import dev.cannoli.ui.theme.buildCannoliTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

enum class BrowsePurpose { SD_ROOT, ROM_DIRECTORY, SETUP }

enum class OnboardingPermission { STORAGE }

// RomM brand purple; the screen-edge border shown while browsing RomM.
private val ROMM_BORDER_COLOR = Color(0xFF553E98)
private val ROMM_BORDER_WIDTH = 2.dp

@Composable
private fun RommBorderFrame() {
    Box(modifier = Modifier.fillMaxSize().border(ROMM_BORDER_WIDTH, ROMM_BORDER_COLOR))
}

private const val CACHE_SYNC_INDICATOR_DELAY_MS = 1200L
private const val CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS = 800L

/**
 * Status-bar state for the RomM cache mirror, shown only while browsing RomM. A stale mirror
 * outranks an in-flight sync so a failing retry cannot blink the alert off and back on, and the
 * syncing glyph is withheld until the sync has run long enough to be worth mentioning. Once shown
 * it stays up for a minimum span, so a sync that finishes just past the threshold does not flash.
 */
@Composable
private fun rommCacheSyncIndicator(
    status: dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus,
    stale: Boolean,
    inRomm: Boolean,
): RommCacheSyncStatus {
    val syncing = inRomm && status == dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING
    var showSyncing by remember { mutableStateOf(false) }
    var shownAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(syncing) {
        if (syncing) {
            delay(CACHE_SYNC_INDICATOR_DELAY_MS)
            shownAt = SystemClock.elapsedRealtime()
            showSyncing = true
        } else if (showSyncing) {
            val held = SystemClock.elapsedRealtime() - shownAt
            if (held < CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS) delay(CACHE_SYNC_INDICATOR_MIN_VISIBLE_MS - held)
            showSyncing = false
        }
    }
    return when {
        !inRomm -> RommCacheSyncStatus.IDLE
        stale -> RommCacheSyncStatus.ERROR
        showSyncing -> RommCacheSyncStatus.SYNCING
        else -> RommCacheSyncStatus.IDLE
    }
}

sealed class LauncherScreen {
    interface ScrollableScreen {
        val selectedIndex: Int
        val scrollTarget: Int
        val itemCount: Int
        fun withScroll(selectedIndex: Int, scrollTarget: Int): LauncherScreen
    }

    data object SystemList : LauncherScreen()
    data object GameList : LauncherScreen()
    data object Settings : LauncherScreen()
    data object InputTester : LauncherScreen()
    data class SaveSlots(
        val gameKey: String,
        val tag: String,
        val base: String,
        val displayName: String,
        val romId: Int,
        val emulator: String?,
        val slots: List<dev.cannoli.scorza.romm.sync.SlotInfo>,
        val selectedIndex: Int = 0,
        val pendingDelete: Boolean = false,
    ) : LauncherScreen()
    data class GuidePicker(
        val files: List<dev.cannoli.scorza.guides.GuideFile>,
        val selectedIndex: Int = 0,
    ) : LauncherScreen()
    data class Guide(
        val filePath: String,
        val guideType: dev.cannoli.scorza.guides.GuideType,
        val page: Int = 0,
        val textZoom: Int = 1,
    ) : LauncherScreen()
    data class ColorList(val colors: List<ColorEntry>, override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = colors.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }

    /** A place a directory item can move to. An empty [path] is the main menu. */
    data class FolderTarget(val path: List<String>, val label: String, val depth: Int)

    /**
     * Picks where the directory.json item [key] goes. [from] is the folder it is in now, or null
     * for one the directory doesn't hold yet, such as an unplaced system.
     */
    data class FolderPicker(
        val title: String,
        val key: String,
        val from: List<String>?,
        val targets: List<FolderTarget>,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = targets.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }

    /**
     * Picks an emulator from system [systemId]'s list: for the game at [romPath] (under Roms/),
     * "Launch With", written to overrides.json; with no [romPath], the system's "Default
     * Emulator", moved to the top of its list in systems.json. [fromGameMenu] returns to the game
     * list's context menu when it closes.
     */
    data class EmulatorPicker(
        val title: String,
        val systemId: String,
        val romPath: String?,
        val choices: List<dev.cannoli.scorza.launcher.EmulatorChoice>,
        val fromGameMenu: Boolean = false,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = choices.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }

    /**
     * "Add To This Folder": [systems] not on the menu yet, then the installed [apps]. Indices in
     * [checkedIndices] count the systems first.
     */
    data class AppPicker(
        val folderPath: List<String>,
        val title: String,
        val apps: List<String>,
        val packages: List<String>,
        val systems: List<dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.Unplaced.System> = emptyList(),
        override val selectedIndex: Int = 0,
        val checkedIndices: Set<Int> = emptySet(),
        val initialChecked: Set<Int> = emptySet(),
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = systems.size + apps.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }

    data class ChildPicker(val parentId: Long, val collectionIds: List<Long>, val displayNames: List<String> = emptyList(), override val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = collectionIds.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Controllers(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class ControllerDetail(
        val mappingId: String,
        val androidDeviceId: Int? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = 5
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class EditButtons(
        val mappingId: String,
        val listeningCanonical: dev.cannoli.scorza.input.CanonicalButton? = null,
        val countdownMs: Int = 0,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.input.CanonicalButton.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class LoggingSettings(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.scorza.util.LoggingPrefs.Category.entries.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class Credits(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = CREDITS_ROOT_ROWS.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class CreditsSection(
        val category: CreditsCategory,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = creditsItemCount(category)
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommPlatformList(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGameList(
        val platform: dev.cannoli.scorza.romm.RommPlatform,
        val search: String = "",
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionGroups(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommVirtualTypes(
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionList(
        val group: dev.cannoli.scorza.romm.RommCollectionGroup,
        val virtualType: String? = null,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommCollectionGameList(
        val collection: dev.cannoli.scorza.romm.RommCollection,
        val search: String = "",
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGlobalSearch(
        val term: String,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
        override val itemCount: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommFirmwareList(
        val platform: dev.cannoli.scorza.romm.RommPlatform,
        val rows: List<dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel.RommFirmwareRow> = emptyList(),
        val checkedIds: Set<Int> = emptySet(),
        val loading: Boolean = true,
        val error: Boolean = false,
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0,
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = rows.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class RommGameDetail(
        val game: dev.cannoli.scorza.romm.RommGame,
        val localState: dev.cannoli.scorza.romm.LocalState,
        val platformName: String,
        val tag: String,
        val scrollStep: Int = 0,
        val groupKey: Int = game.groupKey,
        val versionCount: Int = 1,
    ) : LauncherScreen()
    // Debug builds only. Renders CannoliIcons.all so what you see is what the app draws, rather
    // than a second hand-maintained list that could disagree with it.
    data class IconGallery(override val selectedIndex: Int = 0, override val scrollTarget: Int = 0) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = dev.cannoli.ui.theme.CannoliIcons.all.size
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class DirectoryBrowser(
        val purpose: BrowsePurpose,
        val currentPath: String,
        val entries: List<String> = emptyList(),
        override val selectedIndex: Int = 0,
        override val scrollTarget: Int = 0
    ) : LauncherScreen(), ScrollableScreen {
        override val itemCount: Int get() = entries.size + if (currentPath != "/storage/") 1 else 0
        override fun withScroll(selectedIndex: Int, scrollTarget: Int) = copy(selectedIndex = selectedIndex, scrollTarget = scrollTarget)
    }
    data class OnboardingPermissions(
        val permissions: List<OnboardingPermission>,
        val granted: Set<OnboardingPermission>,
        val volumes: List<Pair<String, String>> = emptyList(),
        val volumeIndex: Int = 0,
        val customPath: String? = null,
        val selectedIndex: Int = 0,
        val existingInstallVolumeIndex: Int? = null,
    ) : LauncherScreen() {
        val allGranted: Boolean get() = granted.containsAll(permissions)
        val storageRowIndex: Int get() = permissions.size
        val continueRowIndex: Int get() = storageRowIndex + 1
        val focusableCount: Int
            get() = permissions.size + (if (allGranted) 1 else 0) + (if (continueEnabled) 1 else 0)
        val isStorageRowFocused: Boolean get() = allGranted && selectedIndex == storageRowIndex
        val isContinueRowFocused: Boolean get() = continueEnabled && selectedIndex == continueRowIndex
        val focusedPermission: OnboardingPermission?
            get() = if (selectedIndex in permissions.indices) permissions[selectedIndex] else null
        val isFocusedGranted: Boolean get() = focusedPermission?.let { it in granted } ?: false
        val selectedVolume: Pair<String, String>? get() = volumes.getOrNull(volumeIndex)
        val isCustomVolume: Boolean get() = selectedVolume?.first == "Custom"
        val continueEnabled: Boolean
            get() = allGranted && volumes.isNotEmpty() && (!isCustomVolume || customPath != null)
        val targetPath: String?
            get() {
                if (!continueEnabled) return null
                return if (isCustomVolume) customPath else selectedVolume!!.second + "Cannoli/"
            }
        fun moved(delta: Int) = copy(
            selectedIndex = (selectedIndex + delta).coerceIn(0, (focusableCount - 1).coerceAtLeast(0))
        )
        fun cycledVolume(delta: Int): OnboardingPermissions {
            if (volumes.size <= 1) return this
            val next = ((volumeIndex + delta) % volumes.size + volumes.size) % volumes.size
            return copy(volumeIndex = next, customPath = null)
        }
    }
}

@Composable
fun AppNavGraph(
    currentScreen: LauncherScreen,
    systemListViewModel: SystemListViewModel? = null,
    gameListViewModel: GameListViewModel? = null,
    inputTesterViewModel: InputTesterViewModel,
    onExitInputTester: () -> Unit = {},
    settingsViewModel: SettingsViewModel,
    controllersViewModel: ControllersViewModel,
    dialogState: StateFlow<DialogState>,
    onListStateChanged: ((androidx.compose.foundation.lazy.LazyListState?) -> Unit)? = null,
    osdController: dev.cannoli.ui.components.OsdController,
    activeMapping: dev.cannoli.scorza.input.DeviceMapping? = null,
    mappingRepository: dev.cannoli.scorza.input.repo.MappingRepository? = null,
    editButtonsController: dev.cannoli.scorza.input.EditButtonsController? = null,
    nav: dev.cannoli.scorza.navigation.NavigationController? = null,
    inputRouter: dev.cannoli.scorza.input.InputRouter? = null,
    rommBrowseViewModel: dev.cannoli.scorza.ui.viewmodel.RommBrowseViewModel? = null,
    rommImageLoader: coil.ImageLoader? = null,
    rommHost: String = "",
    rommArtType: dev.cannoli.scorza.romm.RommArtType = dev.cannoli.scorza.romm.RommArtType.NONE,
    rommDownloader: dev.cannoli.scorza.romm.download.RommDownloader? = null,
    rommArtFetcher: dev.cannoli.scorza.romm.art.RommArtFetcher? = null,
    saveSyncStatus: dev.cannoli.ui.components.SaveSyncStatus = dev.cannoli.ui.components.SaveSyncStatus.DISABLED,
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()

    val pillScale = pillScaleFor(appSettings.textSize.sp)
    val listFontSize = appSettings.textSize.sp.sp
    val listLineHeight = pillLineHeightSp(appSettings.textSize.sp).sp

    val labels = dev.cannoli.ui.ButtonStyle(
        activeMapping.labelSet(dev.cannoli.ui.ButtonLabelSet.PLUMBER),
        activeMapping.confirmButton(),
    )

    val cannoliColors = CannoliColors(
        highlight = appSettings.colorHighlight,
        text = appSettings.colorText,
        highlightText = appSettings.colorHighlightText,
        accent = appSettings.colorAccent,
        title = appSettings.colorTitle,
        background = appSettings.colorBackground,
        statusBar = appSettings.colorStatusBar
    )

    val statusBarLeftEdge = remember { mutableIntStateOf(Int.MAX_VALUE) }
    val statusClockRightEdge = remember { mutableIntStateOf(Int.MIN_VALUE) }

    val scaleFactor = appSettings.textSize.sp / 22f
    val cannoliTypography = buildCannoliTypography(baseSizeSp = appSettings.textSize.sp, fontFamily = LocalCannoliFont.current)

    val viewportInsets = ViewportInsetsPx(
        geometryWidthPct = appSettings.screenGeometryWidth,
        geometryHeightPct = appSettings.screenGeometryHeight,
        geometryXPct = appSettings.screenGeometryX,
        geometryYPct = appSettings.screenGeometryY,
        portraitMarginPx = appSettings.portraitMarginPx,
    )
    CompositionLocalProvider(
        LocalCannoliColors provides cannoliColors,
        LocalStatusBarLeftEdge provides statusBarLeftEdge,
        LocalStatusClockRightEdge provides statusClockRightEdge,
        LocalScaleFactor provides scaleFactor,
        LocalCannoliTypography provides cannoliTypography,
        LocalViewportInsets provides viewportInsets,
        LocalPillScale provides pillScale
    ) {
    // Must resolve inside the provider so they match what PillRow actually renders.
    val listVerticalPadding = pillVerticalPadding()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    BoxWithConstraints(modifier = Modifier.fillMaxSize().displayCutoutPadding().padding(effectiveViewportPadding())) {
    // Solved once here so the title spacer, the row spacing and the footer reservation all come from
    // the same division of the screen; every list screen lays out inside these same bounds.
    val insets = screenInsets()
    // Every fixed height below is laid out as whole pixels, so the solve has to round the same way
    // or its row count drifts from the one Compose actually places.
    val density = LocalDensity.current
    fun Dp.snap(): Dp = with(density) { roundToPx().toDp() }
    val available = (maxHeight - insets.calculateTopPadding() - insets.calculateBottomPadding()).snap()
    val titleMetrics = screenTitleMetrics(listFontSize, listLineHeight)
    val barHeight = bottomBarHeight().snap()
    val rowHeight = itemHeight.snap()
    val pixel = with(density) { 1.toDp() }
    val titleHeight = titleMetrics.height.snap()
    // What each end needs beyond the shared row spacing for the gaps to read alike: under the title,
    // the row's own below-ink space less the title's deeper descent; over the footer, the row's
    // above-ink leading, which the legend pill's solid edge has no counterpart for.
    val edge = pillNominalGap() / 2
    val topExtra = titleMetrics.rowBelowInk + edge - titleMetrics.titleBelowInk
    val bottomExtra = titleMetrics.rowAboveInk + edge
    val rhythm =
        solveListRhythm(available, titleHeight, barHeight, rowHeight, topExtra, bottomExtra, true, pixel)
    val untitledRhythm =
        solveListRhythm(available, titleHeight, barHeight, rowHeight, topExtra, bottomExtra, false, pixel)
    CompositionLocalProvider(
        LocalListRhythm provides rhythm,
        LocalUntitledListRhythm provides untitledRhythm
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            is LauncherScreen.SystemList -> {
                if (systemListViewModel == null) return@Box
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.systemListHandler) }
                SystemListScreen(
                    viewModel = systemListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    title = appSettings.title,
                    mainMenuQuit = appSettings.mainMenuQuit,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.GameList -> {
                if (gameListViewModel == null) return@Box
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.gameListHandler) }
                GameListScreen(
                    viewModel = gameListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    artWidth = appSettings.artWidth,
                    artScale = appSettings.artScale,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.InputTester -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.inputTesterHandler) }
                InputTesterScreen(
                    viewModel = inputTesterViewModel,
                    buttonStyle = labels,
                    onExit = onExitInputTester,
                )
            }
            is LauncherScreen.Settings -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.settingsHandler) }
                SettingsScreen(
                viewModel = settingsViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                onListStateChanged = onListStateChanged,
                buttonStyle = labels,
            )
            }
            is LauncherScreen.ColorList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_colors),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.colors,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, entry, isSelected ->
                        PillRowKeyValue(
                            label = stringResource(entry.labelRes),
                            value = entry.hex.uppercase(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            swatchColor = Color(entry.color.toInt())
                        )
                    }
                }
            }
            is LauncherScreen.CollectionPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    leftBottomItems = listOf(
                        labels.west to stringResource(R.string.label_new)
                    ),
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
                val d = dialog
                if (d is DialogState.CollectionCreated) {
                    MessageOverlay(message = stringResource(R.string.collection_created, d.collectionName), buttonStyle = labels)
                }
            }
            is LauncherScreen.ChildPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_child_collections),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    if (currentScreen.collectionIds.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_collections),
                            style = MaterialTheme.typography.bodyLarge,
                            color = cannoliColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collectionIds,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget,
                            onListStateChanged = onListStateChanged
                        ) { index, _, isSelected ->
                            PillRowText(
                                label = currentScreen.displayNames.getOrElse(index) { "" },
                                isSelected = isSelected,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
            }
            is LauncherScreen.FolderPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.targets,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, target, isSelected ->
                        // Indented by depth so the list reads as the folder tree.
                        PillRowText(
                            label = "    ".repeat((target.depth - 1).coerceAtLeast(0)) +
                                (if (target.depth > 0) "/ " else "") + target.label,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                        )
                    }
                }
            }
            is LauncherScreen.EmulatorPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val defaultText = stringResource(R.string.value_default)
                val currentText = stringResource(R.string.value_current)
                val appMissingText = stringResource(R.string.value_not_installed)
                val coreMissingText = stringResource(R.string.value_core_missing)
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf(labels.confirm to stringResource(R.string.label_select)),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.choices,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, choice, isSelected ->
                        // The first is the system's default; the game's own pick, when it has one,
                        // says Current. A missing app or core comes first, with the warning icon.
                        val warning = when (choice.mark) {
                            dev.cannoli.scorza.launcher.ChoiceMark.APP_MISSING -> appMissingText
                            dev.cannoli.scorza.launcher.ChoiceMark.CORE_MISSING -> coreMissingText
                            dev.cannoli.scorza.launcher.ChoiceMark.NONE -> null
                        }
                        val value = listOfNotNull(
                            warning,
                            currentText.takeIf { choice.isCurrent && !choice.isDefault },
                            defaultText.takeIf { choice.isDefault },
                        ).joinToString(" · ")
                        PillRowKeyValue(
                            label = choice.label,
                            value = value,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            valueIcon = if (warning != null) CannoliIcons.NotInstalled.glyph else null,
                        )
                    }
                }
            }
            is LauncherScreen.AppPicker -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    List(
                        items = currentScreen.systems + currentScreen.apps,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { index, row, isSelected ->
                        PillRowText(
                            label = if (row is dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.Unplaced) {
                                dev.cannoli.scorza.ui.screens.unplacedLabel(row)
                            } else row.toString(),
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            checkState = index in currentScreen.checkedIndices
                        )
                    }
                }
            }
            is LauncherScreen.IconGallery -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.title_icon_gallery),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList(),
                    buttonStyle = labels
                ) {
                    val sections = remember {
                        CannoliIcons.all.groupBy { it.category }
                            .map { (header, icons) -> ListSection(header = header, items = icons) }
                    }
                    SectionedList(
                        sections = sections,
                        selectedIndex = currentScreen.selectedIndex,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget,
                        onListStateChanged = onListStateChanged
                    ) { _, icon, isSelected ->
                        // The glyph sits immediately left of the name it claims to be, so a
                        // codepoint that resolves to the wrong glyph is visible at a glance.
                        PillRowKeyValue(
                            label = "${icon.constantName}: ${icon.purpose}",
                            value = icon.glyphName,
                            isSelected = isSelected,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            valueIcon = icon.glyph,
                        )
                    }
                }
            }
            is LauncherScreen.DirectoryBrowser -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingHandler) }
                DirectoryBrowserScreen(
                    currentPath = currentScreen.currentPath,
                    entries = currentScreen.entries,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    itemHeight = itemHeight,
                    isSelectRow = currentScreen.selectedIndex == 0,
                    showSelectOption = currentScreen.currentPath != "/storage/",
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels
                )
            }
            is LauncherScreen.Credits -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                CreditsOverlay(
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged
                )
            }
            is LauncherScreen.CreditsSection -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                CreditsCategoryOverlay(
                    category = currentScreen.category,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    onListStateChanged = onListStateChanged
                )
            }
            is LauncherScreen.Controllers -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllersHandler) }
                ControllersScreen(
                screen = currentScreen,
                viewModel = controllersViewModel,
                modifier = Modifier.fillMaxSize(),
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                buttonStyle = labels,
            )
            }
            is LauncherScreen.ControllerDetail -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.controllerDetailHandler) }
                val controllersState by controllersViewModel.state.collectAsState()
                val mapping = controllersState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: controllersState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                ControllerDetailScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.EditButtons -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.editButtonsHandler) }
                val editState by controllersViewModel.state.collectAsState()
                val mapping = editState.connected.firstOrNull { it.mapping.id == currentScreen.mappingId }?.mapping
                    ?: editState.savedMappings.firstOrNull { it.id == currentScreen.mappingId }
                    ?: mappingRepository?.findById(currentScreen.mappingId)
                if (editButtonsController != null && nav != null) {
                    androidx.compose.runtime.LaunchedEffect(currentScreen.listeningCanonical) {
                        if (currentScreen.listeningCanonical != null) {
                            val startedAt = System.currentTimeMillis()
                            while (currentScreen.listeningCanonical != null) {
                                kotlinx.coroutines.delay(50)
                                val finalized = editButtonsController.tickAndMaybeFinalize()
                                if (finalized != null || !editButtonsController.isListening) {
                                    val cs = nav.currentScreen
                                    if (cs is LauncherScreen.EditButtons) {
                                        nav.replaceTop(cs.copy(listeningCanonical = null, countdownMs = 0))
                                    }
                                    break
                                }
                                val cs = nav.currentScreen
                                if (cs is LauncherScreen.EditButtons && cs.listeningCanonical != null) {
                                    val elapsed = (System.currentTimeMillis() - startedAt).toInt()
                                    if (cs.countdownMs != elapsed) {
                                        nav.replaceTop(cs.copy(countdownMs = elapsed))
                                    }
                                }
                            }
                        }
                    }
                }
                EditButtonsScreen(
                    screen = currentScreen,
                    mapping = mapping,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.LoggingSettings -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.loggingSettingsHandler) }
                LoggingSettingsScreen(
                    screen = currentScreen,
                    modifier = Modifier.fillMaxSize(),
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.OnboardingPermissions -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.onboardingHandler) }
                dev.cannoli.scorza.ui.screens.OnboardingPermissionsScreen(
                    permissions = currentScreen.permissions,
                    granted = currentScreen.granted,
                    volumes = currentScreen.volumes,
                    volumeIndex = currentScreen.volumeIndex,
                    customPath = currentScreen.customPath,
                    selectedIndex = currentScreen.selectedIndex,
                    existingInstallVolumeIndex = currentScreen.existingInstallVolumeIndex,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.SaveSlots -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.saveSlotsHandler) }
                SaveSlotsScreen(
                    gameName = currentScreen.displayName,
                    slots = currentScreen.slots,
                    selectedIndex = currentScreen.selectedIndex,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                    pendingDelete = currentScreen.pendingDelete,
                )
            }
            is LauncherScreen.GuidePicker -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.guideHandler) }
                GuidePickerScreen(
                    files = currentScreen.files,
                    selectedIndex = currentScreen.selectedIndex,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.Guide -> {
                inputRouter?.let { dev.cannoli.scorza.input.screen.compose.ScreenInput(it.guideHandler) }
                val c = inputRouter?.guideHandler?.controller
                GuideViewerScreen(
                    filePath = currentScreen.filePath,
                    guideType = currentScreen.guideType,
                    page = currentScreen.page,
                    textZoom = currentScreen.textZoom,
                    initialScrollY = c?.guideInitialScroll?.intValue ?: 0,
                    initialScrollX = c?.guideInitialScrollX?.intValue ?: 0,
                    scrollDir = c?.guideScrollDir?.intValue ?: 0,
                    scrollXDir = c?.guideScrollXDir?.intValue ?: 0,
                    pageJump = c?.guidePageJump?.intValue ?: 0,
                    pageJumpDir = c?.guidePageJumpDir?.intValue ?: 0,
                    pageCount = c?.guidePageCount?.intValue ?: 0,
                    onScrollPosChanged = { y, x -> c?.onScrollChanged(y, x) },
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommPlatformList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val platforms = rommBrowseViewModel?.platforms?.collectAsState()?.value ?: emptyList()
                val collections = rommBrowseViewModel?.collections?.collectAsState()?.value ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    rommBrowseViewModel?.enterBrowse()
                }
                val showCollectionsRow = collections.isNotEmpty()
                val syncStatus = rommBrowseViewModel?.syncStatus?.collectAsState()?.value
                val syncProgress = rommBrowseViewModel?.syncProgress?.collectAsState()?.value
                var emptyMessage: String? = null
                var syncFraction: Float? = null
                if (platforms.isEmpty()) {
                    if (rommBrowseViewModel?.isServerUnsupported() == true) {
                        emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_server_too_old)
                    } else when (syncStatus) {
                        dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING ->
                            if (syncProgress != null && syncProgress.total > 0) {
                                val platformName = syncProgress.platform
                                emptyMessage = if (platformName != null)
                                    androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing_platform, platformName)
                                else androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                                syncFraction = syncProgress.completed.toFloat() / syncProgress.total
                            } else {
                                emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_syncing)
                            }
                        dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.ERROR ->
                            emptyMessage = androidx.compose.ui.res.stringResource(dev.cannoli.ui.R.string.romm_sync_error)
                        else -> {}
                    }
                }
                val effectiveItemCount = platforms.size + (if (showCollectionsRow) 1 else 0)
                androidx.compose.runtime.LaunchedEffect(effectiveItemCount) {
                    if (currentScreen.itemCount != effectiveItemCount) nav?.replaceTop(currentScreen.copy(itemCount = effectiveItemCount))
                }
                dev.cannoli.scorza.ui.screens.RommPlatformListScreen(
                    platforms = platforms,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    showCollectionsRow = showCollectionsRow,
                    collectionCount = collections.size,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                    emptyMessage = emptyMessage,
                    progress = syncFraction,
                    syncing = syncStatus == dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.SYNCING,
                )
            }
            is LauncherScreen.RommGameList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.games?.collectAsState()?.value
                // null (not loaded), a different platform's id, or a stale search term means the rows are not
                // ours yet, so we render a loading blank rather than flashing the previous list/art or "No results".
                val loading = loaded?.id != currentScreen.platform.id ||
                    loaded?.search != currentScreen.search.ifBlank { null }
                val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id, currentScreen.search) {
                    rommBrowseViewModel?.openPlatform(currentScreen.platform, currentScreen.search.ifBlank { null })
                }
                androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                    if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
                }
                val loader = rommImageLoader
                val queueItems = rommDownloader?.queue?.state?.collectAsState()?.value ?: emptyList()
                val doneForPlatform = queueItems.count {
                    it.tag == currentScreen.platform.cannoliTag &&
                        it.status == dev.cannoli.scorza.romm.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(doneForPlatform) {
                    if (doneForPlatform > 0) rommBrowseViewModel?.refreshLocalState()
                }
                val multiSelect = rommBrowseViewModel?.multiSelect?.collectAsState()?.value ?: false
                val checkedIds = rommBrowseViewModel?.checkedIds?.collectAsState()?.value ?: emptySet()
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameListScreen(
                        title = currentScreen.platform.displayName,
                        search = currentScreen.search,
                        games = games,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        multiSelect = multiSelect,
                        checkedIds = checkedIds,
                        showFirmware = currentScreen.platform.firmwareCount > 0,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                    )
                }
            }
            is LauncherScreen.RommCollectionGroups -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
                val counts = rommBrowseViewModel?.groupCounts?.collectAsState()?.value ?: emptyMap()
                val enabled = rommBrowseViewModel?.enabledGroups() ?: emptySet()
                val rows = listOf(
                    dev.cannoli.scorza.romm.RommCollectionGroup.USER to stringResource(dev.cannoli.ui.R.string.romm_collections_my),
                    dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL to stringResource(dev.cannoli.ui.R.string.romm_collections_virtual),
                    dev.cannoli.scorza.romm.RommCollectionGroup.SMART to stringResource(dev.cannoli.ui.R.string.romm_collections_smart),
                ).filter { it.first in enabled }
                 .map { dev.cannoli.scorza.ui.screens.RommGroupRow(it.first, it.second, counts[it.first] ?: 0) }
                androidx.compose.runtime.LaunchedEffect(rows.size) {
                    if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
                }
                dev.cannoli.scorza.ui.screens.RommCollectionGroupsScreen(
                    rows = rows,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommVirtualTypes -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { rommBrowseViewModel?.loadCollectionCounts() }
                val typeCounts = rommBrowseViewModel?.virtualTypeCounts?.collectAsState()?.value ?: emptyList()
                val rows = typeCounts.map { (type, count) ->
                    val label = dev.cannoli.scorza.romm.RommVirtualType.from(type)?.let { stringResource(it.labelRes) } ?: type
                    dev.cannoli.scorza.ui.screens.RommTypeRow(type, label, count)
                }
                androidx.compose.runtime.LaunchedEffect(rows.size) {
                    if (currentScreen.itemCount != rows.size) nav?.replaceTop(currentScreen.copy(itemCount = rows.size))
                }
                dev.cannoli.scorza.ui.screens.RommVirtualTypesScreen(
                    rows = rows,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommCollectionList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val key = currentScreen.group.name + (currentScreen.virtualType?.let { ":$it" } ?: "")
                val loaded = rommBrowseViewModel?.collectionList?.collectAsState()?.value
                val items = if (loaded?.id == key) loaded.rows else emptyList()
                androidx.compose.runtime.LaunchedEffect(key) {
                    rommBrowseViewModel?.openCollections(currentScreen.group, currentScreen.virtualType)
                }
                androidx.compose.runtime.LaunchedEffect(items.size) {
                    if (currentScreen.itemCount != items.size) nav?.replaceTop(currentScreen.copy(itemCount = items.size))
                }
                val title = currentScreen.virtualType
                    ?.let { vtype ->
                        val typeLabel = dev.cannoli.scorza.romm.RommVirtualType.from(vtype)?.let { t -> stringResource(t.labelRes) } ?: vtype
                        "${stringResource(dev.cannoli.ui.R.string.romm_collection_group_virtual)}: $typeLabel"
                    }
                    ?: stringResource(when (currentScreen.group) {
                        dev.cannoli.scorza.romm.RommCollectionGroup.USER -> dev.cannoli.ui.R.string.romm_collections_my
                        dev.cannoli.scorza.romm.RommCollectionGroup.SMART -> dev.cannoli.ui.R.string.romm_collections_smart
                        dev.cannoli.scorza.romm.RommCollectionGroup.VIRTUAL -> dev.cannoli.ui.R.string.romm_collections_virtual
                    })
                dev.cannoli.scorza.ui.screens.RommCollectionListScreen(
                    title = title,
                    collections = items,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onListStateChanged = onListStateChanged,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommCollectionGameList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.collectionGames?.collectAsState()?.value
                val loading = loaded?.id != currentScreen.collection.id ||
                    loaded?.search != currentScreen.search.ifBlank { null }
                val games = if (loading) emptyList() else loaded?.rows ?: emptyList()
                androidx.compose.runtime.LaunchedEffect(currentScreen.collection.id, currentScreen.search) {
                    rommBrowseViewModel?.openCollection(currentScreen.collection, currentScreen.search.ifBlank { null })
                }
                androidx.compose.runtime.LaunchedEffect(loading, games.size) {
                    if (!loading && currentScreen.itemCount != games.size) nav?.replaceTop(currentScreen.copy(itemCount = games.size))
                }
                val queueItems = rommDownloader?.queue?.state?.collectAsState()?.value ?: emptyList()
                val doneCount = queueItems.count {
                    it.status == dev.cannoli.scorza.romm.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(doneCount) {
                    if (doneCount > 0) rommBrowseViewModel?.refreshCollectionLocalState()
                }
                val multiSelect = rommBrowseViewModel?.multiSelect?.collectAsState()?.value ?: false
                val checkedIds = rommBrowseViewModel?.checkedIds?.collectAsState()?.value ?: emptySet()
                val loader = rommImageLoader
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommCollectionGameListScreen(
                        title = currentScreen.collection.name,
                        search = currentScreen.search,
                        games = games,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        multiSelect = multiSelect,
                        checkedIds = checkedIds,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                    )
                }
            }
            is LauncherScreen.RommGlobalSearch -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                val loaded = rommBrowseViewModel?.searchResults?.collectAsState()?.value
                val loading = loaded?.id != currentScreen.term
                val results = if (loading) emptyList() else loaded?.rows ?: emptyList()
                val allPlatforms = rommBrowseViewModel?.allPlatforms?.collectAsState()?.value ?: emptyList()
                val platformTagById = remember(allPlatforms) { allPlatforms.associate { it.id to it.cannoliTag.uppercase() } }
                androidx.compose.runtime.LaunchedEffect(currentScreen.term) {
                    rommBrowseViewModel?.loadGlobalSearch(dev.cannoli.scorza.romm.RommSearchQuery(currentScreen.term))
                }
                androidx.compose.runtime.LaunchedEffect(loading, results.size) {
                    if (!loading && currentScreen.itemCount != results.size) nav?.replaceTop(currentScreen.copy(itemCount = results.size))
                }
                val loader = rommImageLoader
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameListScreen(
                        title = stringResource(dev.cannoli.ui.R.string.romm_global_search_title),
                        search = currentScreen.term,
                        games = results,
                        loading = loading,
                        selectedIndex = currentScreen.selectedIndex,
                        scrollTarget = currentScreen.scrollTarget,
                        host = rommHost,
                        artWidth = appSettings.artWidth,
                        artType = rommArtType,
                        imageLoader = loader,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding,
                        onListStateChanged = onListStateChanged,
                        buttonStyle = labels,
                        platformLabelForGame = { g -> platformTagById[g.platformId] },
                    )
                }
            }
            is LauncherScreen.RommFirmwareList -> {
                if (inputRouter != null) {
                    val handler = remember { inputRouter.currentHandler() }
                    dev.cannoli.scorza.input.screen.compose.ScreenInput(handler)
                }
                androidx.compose.runtime.LaunchedEffect(currentScreen.platform.id) {
                    if (currentScreen.loading) {
                        val result = runCatching {
                            rommBrowseViewModel?.loadFirmware(currentScreen.platform.id, currentScreen.platform.cannoliTag) ?: emptyList()
                        }
                        nav?.replaceTop(currentScreen.copy(
                            rows = result.getOrDefault(emptyList()),
                            loading = false,
                            error = result.isFailure,
                        ))
                    }
                }
                dev.cannoli.scorza.ui.screens.RommFirmwareListScreen(
                    title = stringResource(dev.cannoli.ui.R.string.romm_firmware_screen_title, currentScreen.platform.displayName),
                    rows = currentScreen.rows,
                    checkedIds = currentScreen.checkedIds,
                    loading = currentScreen.loading,
                    error = currentScreen.error,
                    selectedIndex = currentScreen.selectedIndex,
                    scrollTarget = currentScreen.scrollTarget,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    buttonStyle = labels,
                )
            }
            is LauncherScreen.RommGameDetail -> {
                val loader = rommImageLoader
                val downloads = rommDownloader?.queue?.state?.collectAsState()?.value ?: emptyList()
                val downloaded = downloads.any {
                    it.rommId == currentScreen.game.id && it.status == dev.cannoli.scorza.romm.download.DownloadStatus.Done
                }
                androidx.compose.runtime.LaunchedEffect(downloaded) {
                    if (downloaded && currentScreen.localState != dev.cannoli.scorza.romm.LocalState.PRESENT) {
                        nav?.replaceTop(currentScreen.copy(localState = dev.cannoli.scorza.romm.LocalState.PRESENT))
                    }
                }
                if (loader != null) {
                    dev.cannoli.scorza.ui.screens.RommGameDetailScreen(
                        game = currentScreen.game,
                        platformName = currentScreen.platformName,
                        localState = currentScreen.localState,
                        host = rommHost,
                        artType = rommArtType,
                        imageLoader = loader,
                        scrollStep = currentScreen.scrollStep,
                        onScrollStepChanged = { nav?.replaceTop(currentScreen.copy(scrollStep = it)) },
                        memberCount = currentScreen.versionCount,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        buttonStyle = labels,
                    )
                }
            }
        }

        // Hoisted full-screen dialog rendering: every screen gets the keyboard / full-screen
        // overlays for free, so a new screen can never silently capture input with nothing drawn.
        val overlayDownloads = rommDownloader?.queue?.state?.collectAsState()?.value ?: emptyList()
        if (dialog is DialogState.RommDownloads && overlayDownloads.isEmpty()) {
            androidx.compose.runtime.LaunchedEffect(Unit) { nav?.dialogState?.value = DialogState.None }
        }
        val artState = rommArtFetcher?.state?.collectAsState()?.value
        androidx.compose.runtime.LaunchedEffect(artState) {
            val finished = artState as? dev.cannoli.scorza.romm.art.ArtFetchState.Finished
            if (finished != null && dialog !is DialogState.RommArtResults) {
                nav?.dialogState?.value = DialogState.RommArtResults(finished.results)
            }
        }
        // No allowlist gate here on purpose: DialogOverlay's own when decides what it draws and
        // falls through to nothing for states a screen renders itself. A second list to keep in
        // sync is how a dialog ends up set and consuming input while drawing nothing.
        DialogOverlay(
            dialogState = dialog,
            backgroundImagePath = appSettings.backgroundImagePath,
            backgroundTint = appSettings.backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding,
            downloads = overlayDownloads,
            buttonStyle = labels,
        )

        val systemListState = systemListViewModel?.state?.collectAsState()?.value
        val hideForDialog = dialog is DialogState.About
                || dialog is DialogState.Kitchen
                || dialog is KeyboardHost
        val hideForScreen = currentScreen is LauncherScreen.Credits
                || currentScreen is LauncherScreen.CreditsSection
                || currentScreen is LauncherScreen.DirectoryBrowser
                || currentScreen is LauncherScreen.InputTester
                || currentScreen is LauncherScreen.OnboardingPermissions
                || (currentScreen is LauncherScreen.SystemList && systemListState?.isLoading == true)
        val showKitchenIcon = dev.cannoli.scorza.server.KitchenManager.running.collectAsState().value
                && appSettings.showKitchen
        val artRunning = appSettings.showDownloads &&
                rommArtFetcher?.state?.collectAsState()?.value == dev.cannoli.scorza.romm.art.ArtFetchState.Running
        val activeDownloadCount = if (appSettings.showDownloads) {
            rommDownloader?.queue?.state?.collectAsState()?.value?.count {
                it.status == dev.cannoli.scorza.romm.download.DownloadStatus.Queued || it.status is dev.cannoli.scorza.romm.download.DownloadStatus.Downloading
            } ?: 0
        } else 0
        val inRomm = currentScreen is LauncherScreen.RommPlatformList ||
                currentScreen is LauncherScreen.RommGameList ||
                currentScreen is LauncherScreen.RommCollectionGroups ||
                currentScreen is LauncherScreen.RommVirtualTypes ||
                currentScreen is LauncherScreen.RommCollectionList ||
                currentScreen is LauncherScreen.RommCollectionGameList ||
                currentScreen is LauncherScreen.RommGlobalSearch ||
                currentScreen is LauncherScreen.RommFirmwareList ||
                currentScreen is LauncherScreen.RommGameDetail
        val cacheSyncStatus = rommCacheSyncIndicator(
            status = rommBrowseViewModel?.syncStatus?.collectAsState()?.value
                ?: dev.cannoli.scorza.romm.cache.RommSyncCoordinator.SyncStatus.IDLE,
            stale = rommBrowseViewModel?.syncStale?.collectAsState()?.value ?: false,
            inRomm = inRomm,
        )
        val hasContent = showKitchenIcon
                || activeDownloadCount > 0
                || artRunning
                || cacheSyncStatus != RommCacheSyncStatus.IDLE
                || appSettings.showWifi
                || appSettings.showBluetooth
                || appSettings.showVpn
                || appSettings.showClock
                || appSettings.batteryDisplay != dev.cannoli.scorza.settings.BatteryDisplay.HIDE
        // The center layout splits the bar: the clock at the top left, everything else at the top right.
        val splitClock = dev.cannoli.ui.theme.LocalCenteredLists.current && appSettings.showClock
        if (!hideForDialog && !hideForScreen && splitClock) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(screenInsets())
                    .onGloballyPositioned { coords ->
                        statusClockRightEdge.intValue = (coords.positionInWindow().x + coords.size.width).toInt()
                    }
            ) {
                dev.cannoli.ui.components.StatusClock(use24hTime = appSettings.use24h)
            }
        } else {
            androidx.compose.runtime.SideEffect { statusClockRightEdge.intValue = Int.MIN_VALUE }
        }
        if (!hideForDialog && !hideForScreen && hasContent) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(screenInsets())
                .onGloballyPositioned { coords ->
                    statusBarLeftEdge.intValue = coords.positionInWindow().x.toInt()
                }
        ) {
            StatusBar(
                kitchenRunning = showKitchenIcon,
                downloadCount = activeDownloadCount,
                downloadsActive = artRunning,
                showWifi = appSettings.showWifi,
                showBluetooth = appSettings.showBluetooth,
                showVpn = appSettings.showVpn,
                showClock = appSettings.showClock && !splitClock,
                showBattery = appSettings.batteryDisplay != dev.cannoli.scorza.settings.BatteryDisplay.HIDE,
                batteryIconOnly = appSettings.batteryDisplay == dev.cannoli.scorza.settings.BatteryDisplay.ICON,
                use24hTime = appSettings.use24h,
                saveSyncStatus = saveSyncStatus,
                rommCacheSyncStatus = cacheSyncStatus,
            )
        }
        }
        if (inRomm) RommBorderFrame()
    }
    val settingsState = settingsViewModel.state.collectAsState().value
    val onPortraitMarginRow = currentScreen is LauncherScreen.Settings
        && settingsState.activeCategory == "display"
        && settingsState.items.getOrNull(settingsState.selectedIndex)?.key == "portrait_margin"
    if (onPortraitMarginRow && appSettings.portraitMarginPx > 0) {
        PortraitMarginOverlay(marginPx = appSettings.portraitMarginPx)
    }
    val onScreenGeometryRow = currentScreen is LauncherScreen.Settings && settingsState.activeCategory == "screen_geometry"
    val geometryIsDefault = appSettings.screenGeometryWidth == 100 && appSettings.screenGeometryHeight == 100 &&
        appSettings.screenGeometryX == 0 && appSettings.screenGeometryY == 0
    if (onScreenGeometryRow && !geometryIsDefault) {
        Box(modifier = Modifier.fillMaxSize().border(2.dp, cannoliColors.accent))
    }
    OsdHost(controller = osdController)
    }
    }
    }
}
