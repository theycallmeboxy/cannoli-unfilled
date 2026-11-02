package dev.cannoli.scorza.ui.viewmodel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.FolderEntry
import dev.cannoli.scorza.config.FolderNode
import dev.cannoli.scorza.config.FoldersConfig
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.RecentlyPlayedRepository
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.scanner.RomDirectoryWatcher
import dev.cannoli.scorza.settings.ContentMode
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
    private val foldersConfig: FoldersConfig,
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
        if (config.contentMode != ContentMode.PLATFORMS || folderPath.isNotEmpty()) return false
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
            contentMode = config.contentMode,
            fghCollectionId = config.fghCollectionId,
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
        data class CollectionItem(val id: Long, val name: String, val count: Int) : ListItem()
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
        /** A folder from folders.json. [count] is how many entries it holds directly. */
        data class FolderItem(val name: String, val count: Int) : ListItem()
    }

    data class State(
        val items: List<ListItem> = emptyList(),
        val platforms: List<Platform> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val isLoading: Boolean = true,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1,
        val hasGameItems: Boolean = false,
        /** The open folder, root first. Empty on the main menu. */
        val folderPath: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var firstVisibleIndex: Int = 0
    private data class Saved(val item: ListItem?, val index: Int, val scroll: Int)
    private var savedPosition: Saved? = null
    private var currentFghCollectionId: Long? = null

    // The folder being shown and, for each level above it, where the cursor was when the user
    // went in, so backing out lands on the folder they opened.
    private var folderPath: List<String> = emptyList()
    private val folderPositions = mutableListOf<Saved>()

    private data class ScanConfig(
        val showRecentlyPlayed: Boolean,
        val showFavorites: Boolean,
        val contentMode: ContentMode,
        val fghCollectionId: Long?,
    )

    private var lastScanConfig: ScanConfig? = null

    fun savePosition(scrollIdx: Int = firstVisibleIndex) {
        val current = _state.value
        savedPosition = Saved(current.items.getOrNull(current.selectedIndex), current.selectedIndex, scrollIdx)
        _state.update { it.copy(scrollTarget = scrollIdx) }
    }

    fun scan(showRecentlyPlayed: Boolean = true, showFavorites: Boolean = true, contentMode: ContentMode = ContentMode.PLATFORMS, fghCollectionId: Long? = null, scanDisk: Boolean = true, reconcileOrphans: Boolean = false, onProgress: ((tag: String, current: Int, total: Int) -> Unit)? = null, onReady: () -> Unit = {}) {
        val prev = _state.value
        val prevItemCount = prev.items.size
        val restored = savedPosition
        savedPosition = null
        val prevSelectedItem = restored?.item ?: prev.items.getOrNull(prev.selectedIndex)
        val prevSelectedIndex = restored?.index ?: prev.selectedIndex
        val prevFirstVisible = restored?.scroll ?: firstVisibleIndex
        currentFghCollectionId = fghCollectionId
        lastScanConfig = ScanConfig(showRecentlyPlayed, showFavorites, contentMode, fghCollectionId)

        scope.launch(Dispatchers.IO) {
            foldersConfig.reloadIfChanged()
            // A folder renamed or removed in folders.json while it was open: fall back to the
            // deepest level that still exists.
            val openFolder = withContext(Dispatchers.Main) {
                while (folderPath.isNotEmpty() && foldersConfig.find(folderPath) == null) {
                    folderPath = folderPath.dropLast(1)
                    folderPositions.removeLastOrNull()
                }
                foldersConfig.find(folderPath)
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

            val groupedPlatforms = allPlatforms.groupBy { it.displayName }.map { (_, group) ->
                if (group.size == 1) group[0]
                else {
                    val primary = group.maxBy { it.gameCount }
                    primary.copy(
                        gameCount = group.sumOf { it.gameCount },
                        tags = group.map { it.tag }
                    )
                }
            }

            val items = mutableListOf<ListItem>()
            val favoritesId = collectionsRepository.favoritesId()
            val inFolders = foldersConfig.platformTagsInFolders()

            if (openFolder != null) {
                items.addAll(folderItems(openFolder, groupedPlatforms))
            } else if (contentMode == ContentMode.FIVE_GAME_HANDHELD) {
                val fghId = fghCollectionId?.takeIf { collectionsRepository.byId(it) != null }
                currentFghCollectionId = fghId
                if (fghId != null) {
                    collectionsRepository.romIdsIn(fghId)
                        .mapNotNull { romsRepository.gameById(it) }
                        .forEach { rom -> items.add(ListItem.GameItem(dev.cannoli.scorza.model.ListItem.RomItem(rom))) }
                    collectionsRepository.appIdsIn(fghId)
                        .mapNotNull { appsRepository.byId(it) }
                        .forEach { app -> items.add(ListItem.GameItem(dev.cannoli.scorza.model.ListItem.AppItem(app))) }
                }
            } else {
                currentFghCollectionId = null
                if (showRecentlyPlayed && recentlyPlayedRepository.hasAny()) {
                    items.add(ListItem.RecentlyPlayedItem)
                }
                if (showFavorites && favoritesId != null) {
                    val hasFavorites = collectionsRepository.romIdsIn(favoritesId).isNotEmpty() ||
                        collectionsRepository.appIdsIn(favoritesId).isNotEmpty()
                    if (hasFavorites) items.add(ListItem.FavoritesItem)
                }
                if (contentMode == ContentMode.PLATFORMS) {
                    val hasTopLevelStandard = collectionsRepository.topLevel().isNotEmpty()
                    if (hasTopLevelStandard) {
                        items.add(ListItem.CollectionsFolder)
                    }
                }
            }

            val reorderableItems = mutableListOf<ListItem>()
            if (openFolder == null) when (contentMode) {
                ContentMode.PLATFORMS -> {
                    groupedPlatforms
                        .filter { it.gameCount > 0 && it.allTags.none { tag -> tag.uppercase() in inFolders } }
                        .sortedBy { it.displayName }
                        .forEach { reorderableItems.add(ListItem.PlatformItem(it)) }
                }
                ContentMode.COLLECTIONS -> {
                    collectionsRepository.topLevel().forEach { row ->
                        val count = collectionsRepository.romIdsIn(row.id).size + collectionsRepository.appIdsIn(row.id).size
                        reorderableItems.add(ListItem.CollectionItem(row.id, row.displayName, count))
                    }
                }
                ContentMode.FIVE_GAME_HANDHELD -> {}
            }
            if (openFolder == null && contentMode != ContentMode.FIVE_GAME_HANDHELD) {
                foldersConfig.roots.forEach { reorderableItems.add(ListItem.FolderItem(it.name, it.entries.size)) }
            }
            if (reorderableItems.isNotEmpty()) {
                val ordered = applyCustomOrder(reorderableItems, romsRepository.knownPlatformTags())
                items.addAll(ordered)
            }

            val current = _state.value
            if (items == current.items && groupedPlatforms == current.platforms && current.folderPath == shownPath) {
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
                platforms = groupedPlatforms,
                selectedIndex = safeIndex,
                scrollTarget = scrollTo,
                isLoading = false,
                hasGameItems = items.any { it is ListItem.GameItem },
                folderPath = shownPath,
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    /** A folder's entries in file order. Platforms with no games stay hidden, as on the main menu. */
    private fun folderItems(node: FolderNode, platforms: List<Platform>): List<ListItem> =
        node.entries.mapNotNull { entry ->
            when (entry) {
                is FolderEntry.Folder -> ListItem.FolderItem(entry.node.name, entry.node.entries.size)
                is FolderEntry.Platform -> platforms
                    .firstOrNull { p -> p.allTags.any { it.uppercase() == entry.tag } }
                    ?.takeIf { it.gameCount > 0 }
                    ?.let { ListItem.PlatformItem(it) }
                is FolderEntry.App -> {
                    val app = appsRepository.forFolderEntry(entry.packageName, entry.name) { appLabel(entry.packageName) }
                    ListItem.GameItem(dev.cannoli.scorza.model.ListItem.AppItem(app))
                }
            }
        }.distinct()

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
        val config = lastScanConfig ?: ScanConfig(true, true, ContentMode.PLATFORMS, null)
        scan(
            showRecentlyPlayed = config.showRecentlyPlayed,
            showFavorites = config.showFavorites,
            contentMode = config.contentMode,
            fghCollectionId = config.fghCollectionId,
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
        val onDisk = tagDirs.map { it.name.uppercase() }
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
        // A folder's order is the order folders.json lists it in.
        if (folderPath.isNotEmpty()) return
        _state.update { current ->
            val item = current.items.getOrNull(current.selectedIndex) ?: return@update current
            if (!item.isReorderable()) return@update current
            current.copy(reorderMode = true, reorderOriginalIndex = current.selectedIndex)
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
        val gameItems = current.items.filterIsInstance<ListItem.GameItem>()
        val collectionItems = current.items.filterIsInstance<ListItem.CollectionItem>()
        if (gameItems.isNotEmpty()) {
            val fghId = currentFghCollectionId
            if (fghId != null) {
                val refs = gameItems.mapNotNull { gameItem ->
                    when (val inner = gameItem.item) {
                        is dev.cannoli.scorza.model.ListItem.RomItem -> dev.cannoli.scorza.db.LibraryRef.Rom(inner.rom.id)
                        is dev.cannoli.scorza.model.ListItem.AppItem -> dev.cannoli.scorza.db.LibraryRef.App(inner.app.id)
                        else -> null
                    }
                }
                scope.launch(Dispatchers.IO) {
                    collectionsRepository.setMemberOrder(fghId, refs)
                }
            }
        } else if (collectionItems.isNotEmpty()) {
            val orderedIds = collectionItems.map { it.id }
            scope.launch(Dispatchers.IO) {
                collectionsRepository.setCollectionOrder(orderedIds)
            }
        } else {
            val tags = current.items.mapNotNull { it.orderTag() }
            if (tags.isNotEmpty()) {
                tags.filter { isReservedTag(it) }.forEach { ensureReservedTag(it) }
                scope.launch(Dispatchers.IO) {
                    romsRepository.setPlatformOrder(tags)
                }
            }
        }
        _state.update { it.copy(reorderMode = false, reorderOriginalIndex = -1) }
    }

    private fun ensureReservedTag(tag: String) {
        romScanner.ensureReservedPlatformTag(tag)
    }

    fun cancelReorder(showRecentlyPlayed: Boolean = true, showFavorites: Boolean = true, contentMode: ContentMode = ContentMode.PLATFORMS, fghCollectionId: Long? = null) {
        val current = _state.value
        if (!current.reorderMode) return
        scan(showRecentlyPlayed, showFavorites, contentMode, fghCollectionId)
    }

    private fun ListItem.isReorderable(): Boolean = this is ListItem.PlatformItem || this is ListItem.FolderItem || this is ListItem.CollectionItem || this is ListItem.GameItem

    private fun ListItem.orderTag(): String? = when (this) {
        is ListItem.PlatformItem -> platform.tag
        is ListItem.CollectionItem -> name
        is ListItem.FolderItem -> FOLDER_TAG_PREFIX + name
        is ListItem.GameItem -> null
        else -> null
    }

    private fun applyCustomOrder(items: List<ListItem>, order: List<String>): List<ListItem> {
        if (order.isEmpty()) return items
        val byTag = items.associateBy { it.orderTag() }
        val ordered = mutableListOf<ListItem>()
        for (tag in order) {
            byTag[tag]?.let { ordered.add(it) }
        }
        val remaining = items.filter { it.orderTag() !in order }
        return ordered + remaining
    }

    fun close() { scope.cancel() }

    companion object {
        // Folders share the platforms table's sort order under reserved tags. Older builds used
        // __TOOLS__ and __PORTS__ the same way, which is why every reserved tag starts with __.
        const val FOLDER_TAG_PREFIX = "__FOLDER__/"

        fun isReservedTag(tag: String): Boolean = tag.startsWith("__")
    }
}
