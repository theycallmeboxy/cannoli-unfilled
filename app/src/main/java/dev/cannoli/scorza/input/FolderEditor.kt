package dev.cannoli.scorza.input

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.config.FolderEntry
import dev.cannoli.scorza.config.FoldersConfig
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
 * The main menu's folder editing: new, rename, delete, move, add apps. Every edit goes through
 * [FoldersConfig], which writes folders.json, so the file stays the one source of truth and stays
 * editable by hand.
 */
@ActivityScoped
class FolderEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoScope private val ioScope: CoroutineScope,
    private val nav: NavigationController,
    private val foldersConfig: FoldersConfig,
    private val systemListViewModel: SystemListViewModel,
    private val launcherActions: LauncherActions,
    private val artworkLookup: dev.cannoli.scorza.util.ArtworkLookup,
    private val pathsProvider: dev.cannoli.scorza.di.CannoliPathsProvider,
) {
    private val currentPath: List<String> get() = systemListViewModel.state.value.folderPath

    /** The folders.json entry behind a main list row, or null for rows folders cannot hold. */
    fun entryFor(item: SystemListViewModel.ListItem): FolderEntry? = when (item) {
        is SystemListViewModel.ListItem.FolderItem ->
            foldersConfig.find(currentPath + item.name)?.let { FolderEntry.Folder(it) }
        is SystemListViewModel.ListItem.PlatformItem -> {
            // A merged row (MAME and FBN under one name) is placed by whichever tag the file uses.
            val tags = item.platform.allTags.map { it.uppercase() }
            val placed = tags.firstOrNull { foldersConfig.parentOf("platform:$it") != null }
            FolderEntry.Platform(placed ?: item.platform.tag.uppercase())
        }
        is SystemListViewModel.ListItem.GameItem -> {
            val app = (item.item as? dev.cannoli.scorza.model.ListItem.AppItem)?.app
            app?.let { a ->
                foldersConfig.allApps().firstOrNull { it.packageName == a.packageName }
                    ?: FolderEntry.App(a.packageName, a.displayName, null)
            }
        }
        else -> null
    }

    fun promptNewFolder() {
        nav.dialogState.value = DialogState.MenuFolderNameInput(parentPath = currentPath)
    }

    fun createFolder(parent: List<String>, name: String) = apply { foldersConfig.createFolder(parent, name) }

    fun renameFolder(name: String, newName: String) {
        val path = currentPath + name
        val target = currentPath + newName.trim()
        apply { foldersConfig.renameFolder(path, newName).also { if (it == null) moveArt(path, target) } }
    }

    fun confirmDelete(name: String) {
        nav.dialogState.value = DialogState.DeleteFolderConfirm(currentPath + name)
    }

    fun deleteFolder(path: List<String>) = apply { foldersConfig.deleteFolder(path) }

    /** Takes an app out of the folder being shown. */
    fun removeFromFolder(item: SystemListViewModel.ListItem) {
        val entry = entryFor(item) ?: return
        val path = currentPath
        apply { foldersConfig.remove(path, entry.key) }
    }

    fun openMovePicker(item: SystemListViewModel.ListItem, displayName: String) {
        val entry = entryFor(item) ?: return
        val own = (entry as? FolderEntry.Folder)?.node?.path
        val targets = buildList {
            add(LauncherScreen.FolderTarget(emptyList(), context.getString(dev.cannoli.ui.R.string.folder_target_main_menu), 0))
            for (path in foldersConfig.allFolderPaths()) {
                // A folder cannot go inside itself or anything under it.
                if (own != null && path.take(own.size) == own) continue
                add(LauncherScreen.FolderTarget(path, path.last(), path.size))
            }
        }
        val here = foldersConfig.parentOf(entry.key) ?: emptyList()
        nav.dialogState.value = DialogState.None
        nav.push(
            LauncherScreen.FolderPicker(
                title = context.getString(dev.cannoli.ui.R.string.folder_picker_title, displayName),
                entry = entry,
                targets = targets,
                selectedIndex = targets.indexOfFirst { it.path == here }.coerceAtLeast(0),
            )
        )
    }

    fun confirmMove(screen: LauncherScreen.FolderPicker) {
        val target = screen.targets.getOrNull(screen.selectedIndex) ?: return
        nav.pop()
        val entry = screen.entry
        apply {
            foldersConfig.move(entry, target.path).also { failure ->
                if (failure == null && entry is FolderEntry.Folder) {
                    moveArt(entry.node.path, target.path + entry.node.name)
                }
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

    /** Opens the installed-apps checklist for the folder being shown. */
    fun openAppPicker() {
        val path = currentPath
        val installed = installedLauncherApps()
        val present = foldersConfig.find(path)?.entries.orEmpty()
            .filterIsInstance<FolderEntry.App>().map { it.packageName }.toSet()
        val checked = installed.indices.filter { installed[it].second in present }.toSet()
        nav.dialogState.value = DialogState.None
        nav.push(
            LauncherScreen.AppPicker(
                folderPath = path,
                title = context.getString(
                    dev.cannoli.ui.R.string.app_picker_title,
                    path.lastOrNull() ?: context.getString(dev.cannoli.ui.R.string.folder_target_main_menu),
                ),
                apps = installed.map { it.first },
                packages = installed.map { it.second },
                checkedIndices = checked,
                initialChecked = checked,
            )
        )
    }

    fun confirmAppPicker(state: LauncherScreen.AppPicker) {
        nav.pop()
        if (state.checkedIndices == state.initialChecked) return
        val apps = state.checkedIndices.sorted().mapNotNull { idx ->
            val pkg = state.packages.getOrNull(idx) ?: return@mapNotNull null
            pkg to (state.apps.getOrNull(idx) ?: pkg)
        }
        apply { foldersConfig.setApps(state.folderPath, apps) }
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
    // what it shows is what folders.json now says.
    private fun apply(edit: () -> String?) {
        ioScope.launch {
            val failure = edit()
            withContext(Dispatchers.Main) {
                nav.dialogState.value = if (failure != null) DialogState.FolderEditError(failure) else DialogState.None
                launcherActions.rescanSystemList(scanDisk = false)
            }
        }
    }
}
