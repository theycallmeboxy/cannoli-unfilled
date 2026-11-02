package dev.cannoli.scorza.input

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.DirectoryEditor
import dev.cannoli.scorza.config.library.DirectoryItem
import dev.cannoli.scorza.config.library.LibraryConfig
import dev.cannoli.scorza.config.library.key
import dev.cannoli.scorza.db.RomScanner
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The menu's editing: new, rename, delete, move, remove, add to a folder, and placing what the
 * menu doesn't show yet. Every edit goes through [DirectoryEditor], which writes directory.json,
 * so the file stays the one source of truth and stays editable by hand.
 */
@ActivityScoped
class FolderEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoScope private val ioScope: CoroutineScope,
    private val nav: NavigationController,
    private val libraryConfig: LibraryConfig,
    private val directoryEditor: DirectoryEditor,
    private val platformConfig: PlatformConfig,
    private val romScanner: RomScanner,
    private val systemListViewModel: SystemListViewModel,
    private val launcherActions: LauncherActions,
    private val artworkLookup: dev.cannoli.scorza.util.ArtworkLookup,
    private val pathsProvider: dev.cannoli.scorza.di.CannoliPathsProvider,
) {
    private val currentPath: List<String> get() = systemListViewModel.state.value.folderPath

    fun promptNewFolder() {
        nav.dialogState.value = DialogState.MenuFolderNameInput(parentPath = currentPath)
    }

    fun createFolder(parent: List<String>, name: String) = apply { directoryEditor.createFolder(parent, name) }

    fun renameFolder(name: String, newName: String) {
        val path = currentPath + name
        val target = currentPath + newName.trim()
        apply { directoryEditor.renameFolder(path, newName).also { if (it == null) moveArt(path, target) } }
    }

    fun confirmDelete(name: String) {
        nav.dialogState.value = DialogState.DeleteFolderConfirm(currentPath + name)
    }

    fun deleteFolder(path: List<String>) = apply { directoryEditor.deleteFolder(path) }

    /** Takes an app, shortcut or system out of the folder being shown. */
    fun removeFromFolder(item: SystemListViewModel.ListItem) {
        val key = systemListViewModel.directoryKey(item) ?: return
        val path = currentPath
        apply { directoryEditor.remove(path, key) }
    }

    fun openMovePicker(item: SystemListViewModel.ListItem, displayName: String) {
        val key = systemListViewModel.directoryKey(item) ?: return
        val own = (item as? SystemListViewModel.ListItem.FolderItem)?.let { currentPath + it.name }
        openPicker(
            title = context.getString(dev.cannoli.ui.R.string.folder_picker_title, displayName),
            key = key,
            from = currentPath,
            exclude = own,
        )
    }

    // The main menu first, then every folder as a tree. A folder cannot go inside itself or
    // anything under it, so [exclude] and its subfolders are left out.
    private fun openPicker(title: String, key: String, from: List<String>?, exclude: List<String>?) {
        val targets = buildList {
            add(LauncherScreen.FolderTarget(emptyList(), context.getString(dev.cannoli.ui.R.string.folder_target_main_menu), 0))
            for (path in libraryConfig.library.folderPaths()) {
                if (exclude != null && path.take(exclude.size) == exclude) continue
                add(LauncherScreen.FolderTarget(path, path.last(), path.size))
            }
        }
        val here = from ?: currentPath
        nav.dialogState.value = DialogState.None
        nav.push(
            LauncherScreen.FolderPicker(
                title = title,
                key = key,
                from = from,
                targets = targets,
                selectedIndex = targets.indexOfFirst { it.path == here }.coerceAtLeast(0),
            )
        )
    }

    fun confirmMove(screen: LauncherScreen.FolderPicker) {
        val target = screen.targets.getOrNull(screen.selectedIndex) ?: return
        nav.pop()
        val key = screen.key
        val from = screen.from
        apply {
            directoryEditor.move(key, from, target.path).also { failure ->
                val folder = key.removePrefix(FOLDER_PREFIX)
                if (failure == null && from != null && key.startsWith(FOLDER_PREFIX)) {
                    moveArt(from + folder, target.path + folder)
                }
            }
        }
    }

    /**
     * Acts on a row of the "Not in the menu" list: a system opens the folder picker to place it,
     * and a ROM folder no system claims offers to copy a system for it from the templates.
     */
    fun placeUnplaced(entry: SystemListViewModel.Unplaced) {
        when (entry) {
            is SystemListViewModel.Unplaced.System -> openPlacePicker(entry.id, entry.name)
            is SystemListViewModel.Unplaced.RomFolder -> ioScope.launch {
                val template = directoryEditor.templateSystemFor(entry.folder)
                withContext(Dispatchers.Main) {
                    nav.dialogState.value = if (template != null) {
                        DialogState.AdoptRomFolderConfirm(entry.folder, template)
                    } else {
                        DialogState.ConfigEditError(
                            context.getString(dev.cannoli.ui.R.string.dialog_no_template_for_folder, entry.folder)
                        )
                    }
                }
            }
        }
    }

    private fun openPlacePicker(systemId: String, name: String) = openPicker(
        title = context.getString(dev.cannoli.ui.R.string.place_picker_title, name),
        key = DirectoryItem.SystemItem(systemId).key,
        from = null,
        exclude = null,
    )

    /**
     * Copies the template system for the ROM folder [folder] into the config, scans its games,
     * then opens the picker to place it.
     */
    fun adoptRomFolder(folder: String) {
        nav.dialogState.value = DialogState.None
        ioScope.launch {
            val result = directoryEditor.adoptRomFolder(folder)
            if (result is DirectoryEditor.Adoption.Added) {
                platformConfig.systemFor(result.systemId)?.let { system ->
                    romScanner.scanPlatform(system.id.uppercase(), isArcade = system.arcade)
                }
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is DirectoryEditor.Adoption.Added -> openPlacePicker(result.systemId, result.name)
                    DirectoryEditor.Adoption.NoTemplate -> nav.dialogState.value = DialogState.ConfigEditError(
                        context.getString(dev.cannoli.ui.R.string.dialog_no_template_for_folder, folder)
                    )
                    is DirectoryEditor.Adoption.Failed -> nav.dialogState.value = DialogState.ConfigEditError(result.reason)
                }
                launcherActions.rescanSystemList(scanDisk = false)
            }
        }
    }

    // Folder art is filed by folder path (Art/Folders/A/B.png, with B's own children under
    // Art/Folders/A/B/), so a renamed or moved folder takes its art and its children's art along.
    private fun moveArt(from: List<String>, to: List<String>) {
        if (from == to) return
        val artRoot = java.io.File(dev.cannoli.scorza.config.CannoliPaths(pathsProvider.root).artDir, "Folders")
        val oldDir = java.io.File(artRoot, from.dropLast(1).joinToString("/"))
        val newDir = java.io.File(artRoot, to.dropLast(1).joinToString("/"))
        val oldBase = dev.cannoli.scorza.romm.download.sanitizeFsName(from.last())
        val newBase = dev.cannoli.scorza.romm.download.sanitizeFsName(to.last())
        oldDir.listFiles()?.filter { it.isFile && it.nameWithoutExtension == oldBase }?.forEach { file ->
            newDir.mkdirs()
            val ext = file.extension
            file.renameTo(java.io.File(newDir, if (ext.isEmpty()) newBase else "$newBase.$ext"))
        }
        val oldChildren = java.io.File(oldDir, oldBase)
        if (oldChildren.isDirectory) {
            newDir.mkdirs()
            oldChildren.renameTo(java.io.File(newDir, newBase))
        }
        artworkLookup.invalidateAll()
    }

    /** Opens "Add To This Folder" for the folder being shown: unplaced systems, then installed apps. */
    fun openAppPicker() {
        val path = currentPath
        val installed = installedLauncherApps()
        val systems = systemListViewModel.state.value.unplaced.filterIsInstance<SystemListViewModel.Unplaced.System>()
        val present = libraryConfig.library.itemsAt(path).orEmpty()
            .filterIsInstance<DirectoryItem.AppItem>().map { it.packageName }.toSet()
        val checked = installed.indices.filter { installed[it].second in present }.map { it + systems.size }.toSet()
        nav.dialogState.value = DialogState.None
        nav.push(
            LauncherScreen.AppPicker(
                folderPath = path,
                title = context.getString(
                    dev.cannoli.ui.R.string.folder_fill_title,
                    path.lastOrNull() ?: context.getString(dev.cannoli.ui.R.string.folder_target_main_menu),
                ),
                apps = installed.map { it.first },
                packages = installed.map { it.second },
                systems = systems,
                checkedIndices = checked,
                initialChecked = checked,
            )
        )
    }

    fun confirmAppPicker(state: LauncherScreen.AppPicker) {
        nav.pop()
        if (state.checkedIndices == state.initialChecked) return
        val offset = state.systems.size
        val systems = state.checkedIndices.sorted().mapNotNull { state.systems.getOrNull(it)?.id }
        val apps = state.checkedIndices.sorted().filter { it >= offset }.mapNotNull { idx ->
            val pkg = state.packages.getOrNull(idx - offset) ?: return@mapNotNull null
            pkg to (state.apps.getOrNull(idx - offset) ?: pkg)
        }
        apply { directoryEditor.fill(state.folderPath, apps, systems) }
    }

    private fun installedLauncherApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val resolveInfos = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val apps = resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                ri.loadLabel(pm).toString() to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
        return if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            listOf("Android TV Settings" to ApkLauncher.VIRTUAL_TV_SETTINGS_PACKAGE) + apps
        } else apps
    }

    // Edits touch the file, so they run off the main thread; the menu is rebuilt either way so
    // what it shows is what directory.json now says.
    private fun apply(edit: () -> String?) {
        ioScope.launch {
            val failure = edit()
            withContext(Dispatchers.Main) {
                nav.dialogState.value = if (failure != null) DialogState.ConfigEditError(failure) else DialogState.None
                launcherActions.rescanSystemList(scanDisk = false)
            }
        }
    }

    private companion object {
        const val FOLDER_PREFIX = "folder:"
    }
}
