package dev.cannoli.scorza.ui.viewmodel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.DirectoryEditor
import dev.cannoli.scorza.config.library.DirectoryItem
import dev.cannoli.scorza.config.library.GameSystem
import dev.cannoli.scorza.config.library.Library
import dev.cannoli.scorza.config.library.LibraryConfig
import dev.cannoli.scorza.config.library.key
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.ChoiceMark
import dev.cannoli.scorza.launcher.EmulatorChoices
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.isPackageInstalled
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.model.SHORTCUT_PACKAGE_PREFIX
import dev.cannoli.scorza.model.shortcutName
import dev.cannoli.scorza.scanner.RomDirectoryWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@ActivityScoped
class SystemListViewModel @Inject constructor(
    private val romsRepository: RomsRepository,
    private val romScanner: RomScanner,
    private val appsRepository: AppsRepository,
    private val collectionsRepository: CollectionsRepository,
    private val recentlyPlayedRepository: RecentlyPlayedRepository,
    private val platformConfig: PlatformConfig,
    private val cannoliPaths: CannoliPathsProvider,
    private val romDirectoryWatcher: RomDirectoryWatcher,
    private val scanScheduler: ScanScheduler,
    private val libraryConfig: LibraryConfig,
    private val directoryEditor: DirectoryEditor,
    private val installedCoreService: InstalledCoreService,
    private val artworkLookup: dev.cannoli.scorza.util.ArtworkLookup,
    @ApplicationContext private val context: Context,
) {
    private val romDirectory: File get() = cannoliPaths.romDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            scanScheduler.results.collectLatest { result ->
                if (isMissingPlatformRow(result.platformTag)) rebuildFromDb()
                else refreshCountsForVisibleList()
            }
        }
    }

    private fun isMissingPlatformRow(platformTag: String): Boolean {
        val config = lastScanConfig ?: return false
        if (folderPath.isNotEmpty()) return false
        val tag = platformTag.uppercase()
        return _state.value.items.none { item ->
            item is ListItem.PlatformItem && item.platform.allTags.any { it.uppercase() == tag }
        }
    }

    private fun rebuildFromDb() {
        val config = lastScanConfig ?: return
        scan(
            showRecentlyPlayed = config.showRecentlyPlayed,
            showFavorites = config.showFavorites,
            scanDisk = false,
        )
    }

    private fun refreshCountsForVisibleList() {
        scope.launch(Dispatchers.IO) {
            val counts = romsRepository.platformCounts().mapKeys { it.key.uppercase() }
            _state.update { current ->
                val newItems = current.items.map { item ->
                    if (item is ListItem.PlatformItem) {
                        val tag = item.platform.tag.uppercase()
                        val c = counts[tag] ?: item.platform.gameCount
                        if (c == item.platform.gameCount) item
                        else ListItem.PlatformItem(item.platform.copy(gameCount = c))
                    } else item
                }
                current.copy(items = newItems)
            }
        }
    }

    sealed class ListItem {
        data object RecentlyPlayedItem : ListItem()
        data object FavoritesItem : ListItem()
        data object CollectionsFolder : ListItem()
        data class PlatformItem(val platform: Platform) : ListItem()
        data class GameItem(val item: dev.cannoli.scorza.model.ListItem) : ListItem() {
            val displayName: String get() = when (val i = item) {
                is dev.cannoli.scorza.model.ListItem.RomItem -> i.rom.displayName
                is dev.cannoli.scorza.model.ListItem.AppItem -> i.app.displayName
                else -> ""
            }
            val artFile: java.io.File? get() = when (val i = item) {
                is dev.cannoli.scorza.model.ListItem.RomItem -> i.rom.artFile
                is dev.cannoli.scorza.model.ListItem.AppItem -> i.app.artFile
                else -> null
            }
            val tags: String? get() = (item as? dev.cannoli.scorza.model.ListItem.RomItem)?.rom?.tags
            val recentKey: String get() = when (val i = item) {
                is dev.cannoli.scorza.model.ListItem.RomItem -> i.rom.path.absolutePath
                is dev.cannoli.scorza.model.ListItem.AppItem -> "/apps/${i.app.type.name}/${i.app.packageName}"
                else -> ""
            }
        }
        /** A folder from directory.json. [count] is how many entries it holds directly. */
        data class FolderItem(val name: String, val count: Int, val artFile: java.io.File? = null) : ListItem()
        /** Heads the dimmed "Not in the menu" rows that reorder mode adds at the end. */
        data object UnplacedHeader : ListItem()
        data class UnplacedRow(val entry: Unplaced) : ListItem()
    }

    /**
     * Games the directory doesn't show, pointed out while editing so they are never lost: a
     * system from systems.json with games that directory.json doesn't place, or a folder under
     * Roms/ that holds files but that no system names as its ROM folder.
     */
    sealed interface Unplaced {
        /** [mark] says what would stop its default emulator from launching. */
        data class System(
            val id: String,
            val name: String,
            val games: Int,
            val mark: ChoiceMark = ChoiceMark.NONE,
        ) : Unplaced
        data class RomFolder(val folder: String, val files: Int) : Unplaced
    }

    data class State(
        val items: List<ListItem> = emptyList(),
        val platforms: List<Platform> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val isLoading: Boolean = true,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1,
        /** True when any row can show art, which reserves the art pane beside the list. */
        val hasGameItems: Boolean = false,
        /** The open folder, root first. Empty on the main menu. */
        val folderPath: List<String> = emptyList(),
        val unplaced: List<Unplaced> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var firstVisibleIndex: Int = 0
    private data class Saved(val item: ListItem?, val index: Int, val scroll: Int)
    private var savedPosition: Saved? = null

    // The folder being shown and, for each level above it, where the cursor was when the user
    // went in, so backing out lands on the folder they opened.
    private var folderPath: List<String> = emptyList()
    private val folderPositions = mutableListOf<Saved>()

    private data class ScanConfig(
        val showRecentlyPlayed: Boolean,
        val showFavorites: Boolean,
    )

    private var lastScanConfig: ScanConfig? = null

    fun savePosition(scrollIdx: Int = firstVisibleIndex) {
        val current = _state.value
        savedPosition = Saved(current.items.getOrNull(current.selectedIndex), current.selectedIndex, scrollIdx)
        _state.update { it.copy(scrollTarget = scrollIdx) }
    }

    fun scan(showRecentlyPlayed: Boolean = true, showFavorites: Boolean = true, scanDisk: Boolean = true, reconcileOrphans: Boolean = false, onProgress: ((tag: String, current: Int, total: Int) -> Unit)? = null, onReady: () -> Unit = {}) {
        val prev = _state.value
        val prevItemCount = prev.items.size
        val restored = savedPosition
        savedPosition = null
        val prevSelectedItem = restored?.item ?: prev.items.getOrNull(prev.selectedIndex)
        val prevSelectedIndex = restored?.index ?: prev.selectedIndex
        val prevFirstVisible = restored?.scroll ?: firstVisibleIndex
        lastScanConfig = ScanConfig(showRecentlyPlayed, showFavorites)

        scope.launch(Dispatchers.IO) {
            libraryConfig.reloadIfChanged()
            // A folder renamed or removed in directory.json while it was open: fall back to the
            // deepest level that still exists.
            val (library, openItems) = withContext(Dispatchers.Main) {
                val library = libraryConfig.library
                while (folderPath.isNotEmpty() && library.itemsAt(folderPath) == null) {
                    folderPath = folderPath.dropLast(1)
                    folderPositions.removeLastOrNull()
                }
                library to library.itemsAt(folderPath).orEmpty()
            }
            val shownPath = folderPath
            if (scanDisk) {
                scanAllPlatformDirs(reconcileOrphans) { tag, current, total ->
                    withContext(Dispatchers.Main) { onProgress?.invoke(tag, current, total) }
                }
            }
            val knownTagsInDb = romsRepository.knownPlatformTags()
            val watcherTags = knownTagsInDb
                .filter { !isReservedTag(it) && platformConfig.isKnownTag(it) }
            romDirectoryWatcher.start(romDirectory, watcherTags)
            val countsByTag = romsRepository.platformCounts().mapKeys { it.key.uppercase() }
            val knownTags = (knownTagsInDb + countsByTag.keys).distinct()
                .filter { !isReservedTag(it) }

            val allPlatforms = knownTags
                .filter { platformConfig.isKnownTag(it) }
                .map { tag ->
                    val count = countsByTag[tag] ?: 0
                    platformConfig.resolvePlatform(tag, count)
                }
            val platformsByTag = allPlatforms.associateBy { it.tag.uppercase() }

            val items = mutableListOf<ListItem>()
            val favoritesId = collectionsRepository.favoritesId()

            if (shownPath.isEmpty()) {
                if (showRecentlyPlayed && recentlyPlayedRepository.hasAny()) {
                    items.add(ListItem.RecentlyPlayedItem)
                }
                if (showFavorites && favoritesId != null) {
                    val hasFavorites = collectionsRepository.romIdsIn(favoritesId).isNotEmpty() ||
                        collectionsRepository.appIdsIn(favoritesId).isNotEmpty()
                    if (hasFavorites) items.add(ListItem.FavoritesItem)
                }
                if (collectionsRepository.topLevel().isNotEmpty()) {
                    items.add(ListItem.CollectionsFolder)
                }
            }
            // The directory's own order is the menu order, on the main menu and in every folder.
            items.addAll(directoryRows(openItems, shownPath, platformsByTag, library))
            val unplaced = unplacedSystems(library, countsByTag) + unclaimedRomFolders()

            val current = _state.value
            if (items == current.items && allPlatforms == current.platforms && current.folderPath == shownPath &&
                unplaced == current.unplaced
            ) {
                if (current.isLoading) {
                    _state.update { it.copy(isLoading = false) }
                }
                withContext(Dispatchers.Main) { onReady() }
                return@launch
            }

            val canRestore = (restored != null || items.size == prevItemCount) && prevItemCount > 0
            val (safeIndex, scrollTo) = if (canRestore && items.isNotEmpty()) {
                val maxIdx = items.lastIndex
                val remapped = prevSelectedItem?.let { items.indexOf(it).takeIf { idx -> idx >= 0 } }
                val idx = when {
                    remapped != null -> remapped
                    current.selectedIndex in items.indices -> current.selectedIndex
                    prevSelectedIndex in items.indices -> prevSelectedIndex
                    else -> maxIdx
                }
                idx to prevFirstVisible.coerceIn(0, maxIdx)
            } else {
                0 to 0
            }
            _state.value = State(
                items = items,
                platforms = allPlatforms,
                selectedIndex = safeIndex,
                scrollTarget = scrollTo,
                isLoading = false,
                hasGameItems = items.any { it is ListItem.GameItem || (it is ListItem.FolderItem && it.artFile != null) },
                folderPath = shownPath,
                unplaced = unplaced,
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    /** A folder's items in file order. Systems with no games stay hidden. */
    private fun directoryRows(
        entries: List<DirectoryItem>,
        path: List<String>,
        platforms: Map<String, Platform>,
        library: Library,
    ): List<ListItem> = entries.mapNotNull { entry ->
        when (entry) {
            is DirectoryItem.Folder -> folderItem(entry, path)
            is DirectoryItem.SystemItem -> platforms[entry.id.uppercase()]
                ?.takeIf { it.gameCount > 0 }
                ?.let { ListItem.PlatformItem(it) }
            is DirectoryItem.ShortcutItem -> library.shortcuts[entry.name]?.let { shortcut ->
                appItem(SHORTCUT_PACKAGE_PREFIX + shortcut.name, shortcut.displayName) { shortcut.displayName }
            }
            is DirectoryItem.AppItem -> appItem(entry.packageName, entry.name) { appLabel(entry.packageName) }
        }
    }.distinct()

    // Apps and shortcuts keep a row in the apps table, which is what favorites and recently
    // played point at.
    private fun appItem(packageName: String, name: String?, fallbackName: () -> String): ListItem.GameItem {
        val app = appsRepository.forDirectoryEntry(packageName, name, fallbackName)
        return ListItem.GameItem(dev.cannoli.scorza.model.ListItem.AppItem(app))
    }

    /** The directory.json key of a row, such as `system:GBA`, or null for rows it doesn't hold. */
    fun directoryKey(item: ListItem): String? = when (item) {
        is ListItem.FolderItem -> DirectoryItem.Folder(item.name, emptyList()).key
        is ListItem.PlatformItem ->
            DirectoryItem.SystemItem(platformConfig.systemFor(item.platform.tag)?.id ?: item.platform.tag).key
        is ListItem.GameItem -> (item.item as? dev.cannoli.scorza.model.ListItem.AppItem)?.app?.let { app ->
            app.shortcutName?.let { DirectoryItem.ShortcutItem(it).key }
                ?: DirectoryItem.AppItem(app.packageName, null).key
        }
        else -> null
    }

    // Art for a folder lives under Art/Folders, nested like the folders themselves:
    // Art/Folders/Nintendo.png for the top-level Nintendo, Art/Folders/Nintendo/Hacks.png inside it.
    private fun folderItem(folder: DirectoryItem.Folder, parent: List<String>): ListItem.FolderItem {
        val artTag = (listOf(FOLDER_ART_DIR) + parent).joinToString("/")
        val art = artworkLookup.findByName(artTag, dev.cannoli.scorza.romm.download.sanitizeFsName(folder.name))
        return ListItem.FolderItem(folder.name, folder.items.size, art)
    }

    private fun unplacedSystems(library: Library, counts: Map<String, Int>): List<Unplaced> {
        val placed = library.placedSystemIds().map { it.uppercase() }.toSet()
        return library.systems.values
            .distinctBy { it.id.uppercase() }
            .filter { it.id.uppercase() !in placed }
            .mapNotNull { system ->
                val games = counts[system.id.uppercase()] ?: 0
                if (games > 0) Unplaced.System(system.id, system.name, games, defaultMark(library, system)) else null
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun defaultMark(library: Library, system: GameSystem): ChoiceMark = EmulatorChoices.defaultMark(
        library, system,
        isInstalled = context::isPackageInstalled,
        coreStatus = installedCoreService::coreStatus,
    )

    // Counted again only when a folder's own listing changes, since the scan runs on every move
    // between folders.
    private val romFolderCounts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    private fun unclaimedRomFolders(): List<Unplaced> {
        val dirs = romDirectory.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs
            .filter { platformConfig.tagForFolder(it.name) == null }
            .mapNotNull { dir ->
                val mtime = dir.lastModified()
                val files = romFolderCounts[dir.name]?.takeIf { it.first == mtime }?.second
                    ?: dir.walkTopDown()
                        .onEnter { !it.name.startsWith(".") || it == dir }
                        .count { it.isFile && !it.name.startsWith(".") }
                        .also { romFolderCounts[dir.name] = mtime to it }
                if (files > 0) Unplaced.RomFolder(dir.name, files) else null
            }
            .sortedBy { it.folder.lowercase() }
    }

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        packageName
    }

    fun isInFolder(): Boolean = folderPath.isNotEmpty()

    /** Opens the folder [name] inside the one being shown. */
    fun enterFolder(name: String, onReady: () -> Unit = {}) {
        val current = _state.value
        val at = current.items.indexOfFirst { it is ListItem.FolderItem && it.name == name }
            .takeIf { it >= 0 } ?: current.selectedIndex
        folderPositions.add(Saved(current.items.getOrNull(at), at, firstVisibleIndex))
        folderPath = folderPath + name
        savedPosition = Saved(null, 0, 0)
        // A new level starts at the top, not at the parent's cursor index.
        _state.update { it.copy(selectedIndex = 0, scrollTarget = 0) }
        rebuild(onReady)
    }

    /** Goes up one folder level. False when already on the main menu. */
    fun exitFolder(onReady: () -> Unit = {}): Boolean {
        if (folderPath.isEmpty()) return false
        folderPath = folderPath.dropLast(1)
        savedPosition = folderPositions.removeLastOrNull()
        rebuild(onReady)
        return true
    }

    private fun rebuild(onReady: () -> Unit) {
        val config = lastScanConfig ?: ScanConfig(true, true)
        scan(
            showRecentlyPlayed = config.showRecentlyPlayed,
            showFavorites = config.showFavorites,
            scanDisk = false,
            onReady = onReady,
        )
    }

    /** [reconcileOrphans] folds in platforms that still hold rows but no longer have a folder,
     *  which the directory walk alone can never reach and only a scan of that tag clears. It is
     *  passed in by the actions that move the library, never inferred, so an unreadable listing on
     *  a slow card cannot quietly wipe a platform the user still has. */
    private suspend fun scanAllPlatformDirs(
        reconcileOrphans: Boolean = false,
        onProgress: (suspend (String, Int, Int) -> Unit)? = null,
    ) {
        if (!romDirectory.exists()) return
        val tagDirs = romDirectory.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return
        // A folder counts when some system in systems.json names it as its ROM folder.
        val onDisk = tagDirs.mapNotNull { platformConfig.tagForFolder(it.name) }
        val orphaned = if (reconcileOrphans) romsRepository.platformCounts().keys.map { it.uppercase() } else emptyList()
        val known = (onDisk + orphaned).distinct().filter { platformConfig.isKnownTag(it) }
        known.forEachIndexed { i, tag ->
            onProgress?.invoke(tag, i, known.size)
            romScanner.scanPlatform(tag, isArcade = platformConfig.isArcade(tag))
        }
        if (known.isNotEmpty()) {
            onProgress?.invoke(known.last(), known.size, known.size)
        }
    }


    fun moveSelection(delta: Int) {
        _state.update { current ->
            val size = current.items.size
            if (size == 0) return@update current
            val raw = current.selectedIndex + delta
            val target = ((raw % size) + size) % size
            current.copy(selectedIndex = target)
        }
    }

    fun setSelectedIndex(index: Int) {
        _state.update { it.copy(selectedIndex = index) }
    }

    fun getSelectedItem(): ListItem? {
        val current = _state.value
        return current.items.getOrNull(current.selectedIndex)
    }

    fun getSelectedPlatformTag(): String? {
        return (getSelectedItem() as? ListItem.PlatformItem)?.platform?.tag
    }

    fun getPlatformTags(): List<String> =
        _state.value.items.filterIsInstance<ListItem.PlatformItem>().map { it.platform.tag }

    fun getNavigableItems(): List<ListItem> =
        _state.value.items

    fun enterReorderMode() {
        _state.update { current ->
            val item = current.items.getOrNull(current.selectedIndex) ?: return@update current
            if (!item.isReorderable()) return@update current
            // What the directory leaves out is listed, dimmed, below what can be moved.
            val unplaced = if (current.unplaced.isEmpty()) emptyList()
            else listOf(ListItem.UnplacedHeader) + current.unplaced.map { ListItem.UnplacedRow(it) }
            current.copy(items = current.items + unplaced, reorderMode = true, reorderOriginalIndex = current.selectedIndex)
        }
    }

    fun isReorderMode(): Boolean = _state.value.reorderMode

    fun reorderMoveUp() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            val items = current.items.toMutableList()
            val prevSelectable = (idx - 1 downTo 0).firstOrNull { items[it].isReorderable() }
                ?: return@update current
            items[idx] = items[prevSelectable].also { items[prevSelectable] = items[idx] }
            current.copy(items = items, selectedIndex = prevSelectable)
        }
    }

    fun reorderMoveDown() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            val items = current.items.toMutableList()
            val nextSelectable = (idx + 1..items.lastIndex).firstOrNull { items[it].isReorderable() }
                ?: return@update current
            items[idx] = items[nextSelectable].also { items[nextSelectable] = items[idx] }
            current.copy(items = items, selectedIndex = nextSelectable)
        }
    }

    fun confirmReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        val path = folderPath
        val keys = current.items.mapNotNull(::directoryKey)
        scope.launch(Dispatchers.IO) {
            directoryEditor.reorder(path, keys)?.let { dev.cannoli.scorza.util.ErrorLog.write("menu reorder: $it") }
        }
        leaveReorderMode()
    }

    private fun leaveReorderMode() {
        _state.update { state ->
            state.copy(
                items = state.items.filterNot { it is ListItem.UnplacedHeader || it is ListItem.UnplacedRow },
                reorderMode = false,
                reorderOriginalIndex = -1,
            )
        }
    }

    fun cancelReorder(showRecentlyPlayed: Boolean = true, showFavorites: Boolean = true) {
        val current = _state.value
        if (!current.reorderMode) return
        // Put back the order the file still has.
        leaveReorderMode()
        scan(showRecentlyPlayed, showFavorites, scanDisk = false)
    }

    private fun ListItem.isReorderable(): Boolean = this is ListItem.PlatformItem || this is ListItem.FolderItem || this is ListItem.GameItem

    fun close() { scope.cancel() }

    companion object {
        const val FOLDER_ART_DIR = "Folders"

        // Older builds kept rows such as __TOOLS__ in the platforms table; none is a system.

        fun isReservedTag(tag: String): Boolean = tag.startsWith("__")
    }
}
