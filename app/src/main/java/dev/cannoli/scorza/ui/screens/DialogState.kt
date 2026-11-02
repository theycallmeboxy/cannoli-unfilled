package dev.cannoli.scorza.ui.screens

import dev.cannoli.ui.components.KeyboardLayout
import dev.cannoli.ui.components.KeyboardState

data class ColorEntry(val key: String, @androidx.annotation.StringRes val labelRes: Int, val hex: String, val color: Long)

sealed interface DialogState {
    data object None : DialogState
    /** [emulatorName] reported its installed cores and [coreName] was not among them. */
    data class MissingCore(val coreName: String, val emulatorName: String) : DialogState
    data class MissingApp(val appName: String, val packageName: String) : DialogState
    data class LaunchError(val message: String) : DialogState
    /**
     * [unplaced] rows follow [options] under a dimmed "Not in the menu" heading, which the cursor
     * skips; [selectedOption] counts on from the options into them.
     */
    data class ContextMenu(
        val gameName: String,
        val selectedOption: Int = 0,
        val options: List<String>,
        val unplaced: List<dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.Unplaced> = emptyList(),
    ) : DialogState {
        /** The unplaced row under the cursor, or null while it is on an option. */
        val selectedUnplaced: dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.Unplaced?
            get() = unplaced.getOrNull(selectedOption - options.size)
    }
    data class BulkContextMenu(val gamePaths: List<String>, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class DeleteConfirm(val gameName: String, val bulkPaths: List<String>? = null) : DialogState
    data class RenameInput(
        val gameName: String,
        val searchScope: String? = null,
        @androidx.annotation.StringRes override val titleRes: Int? = null,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class NewCollectionInput(
        val gamePaths: List<String> = emptyList(),
        val parentId: Long? = null,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override val titleRes: Int get() = dev.cannoli.ui.R.string.keyboard_title_new_collection
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class CollectionRenameInput(
        val collectionId: Long,
        val oldDisplayName: String,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override val titleRes: Int get() = dev.cannoli.ui.R.string.keyboard_title_rename_collection
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class DeleteCollectionConfirm(val collectionId: Long, val displayName: String) : DialogState
    data class RenameResult(val success: Boolean, val message: String) : DialogState
    data class CollectionCreated(val collectionName: String) : DialogState
    data class ColorPicker(val settingKey: String, val title: String, val currentColor: Long, val selectedRow: Int = 0, val selectedCol: Int = 0) : DialogState
    data class HexColorInput(val settingKey: String, val title: String, val currentHex: String = "", val selectedIndex: Int = 0) : DialogState
    data class About(val statusMessage: String? = null) : DialogState
    data class Kitchen(val urls: List<String>, val selectedIndex: Int = 0, val pin: String, val requirePin: Boolean = true, val fromQuickMenu: Boolean = false) : DialogState
    data class RommPairing(
        val host: String = "",
        val message: String = "",
        val waitingApproval: Boolean = false,
        val qrBitmap: android.graphics.Bitmap? = null,
    ) : DialogState
    data class RommConnected(val host: String, val username: String? = null, val version: String? = null, val fromSettingsMenu: Boolean = false) : DialogState
    data class NewFolderInput(
        val parentPath: String,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override val titleRes: Int get() = dev.cannoli.ui.R.string.keyboard_title_new_folder
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    /** Names a new main-menu folder inside [parentPath] (empty is the main menu itself). */
    data class MenuFolderNameInput(
        val parentPath: List<String>,
        override val keyboard: KeyboardState = KeyboardState(),
    ) : DialogState, KeyboardHost {
        override val titleRes: Int get() = dev.cannoli.ui.R.string.keyboard_title_new_folder
        override fun withKeyboard(keyboard: KeyboardState) = copy(keyboard = keyboard)
    }
    data class DeleteFolderConfirm(val path: List<String>) : DialogState
    data class ConfigEditError(val message: String) : DialogState
    /** Offers to copy [systemName] from systems.template.json for the unclaimed ROM folder [folder]. */
    data class AdoptRomFolderConfirm(val folder: String, val systemName: String) : DialogState
    data class KeyboardHelp(val restore: DialogState, val layout: KeyboardLayout) : DialogState
    data object QuitConfirm : DialogState
    data object RestartRequired : DialogState
    /** [newRomDirectory] is empty when clearing the pick, which resolves back to the Cannoli root. */
    data class LibrarySwitchConfirm(val newRomDirectory: String) : DialogState
    data class IntentAuditResult(val message: String) : DialogState
    data class SystemFoldersRegenerated(val message: String) : DialogState
    data class QuickMenu(
        val rows: List<dev.cannoli.scorza.ui.quickmenu.QuickMenuRow>,
        val kitchenRunning: Boolean,
        val selectedIndex: Int = 0,
        val conflictCount: Int = 0,
        val syncErrorCount: Int = 0,
    ) : DialogState
    data class QuickInfo(
        val urls: List<String>,
        val kitchenRunning: Boolean,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class RommDownloads(val selectedIndex: Int = 0, val fromQuickMenu: Boolean = false) : DialogState
    data class RommArtResults(
        val results: dev.cannoli.scorza.romm.art.ArtFetchResults,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class RescanProgress(val progress: Float, val label: String) : DialogState
    data class RommActionsMenu(
        val selectedIndex: Int = 0,
        val hasDownloads: Boolean = false,
    ) : DialogState
    data class RommSettingsMenu(
        val selectedIndex: Int = 0,
        val concurrent: Int = 2,
        val artType: dev.cannoli.scorza.romm.RommArtType = dev.cannoli.scorza.romm.RommArtType.NONE,
    ) : DialogState
    data class RommAdvancedMenu(val selectedIndex: Int = 0) : DialogState
    data class RommSaveSyncMenu(
        val selectedIndex: Int = 0,
        val supported: Boolean = true,
        val enabled: Boolean = false,
        val backupCount: Int = 5,
        val pendingConflicts: Int = 0,
        val syncErrors: Int = 0,
        val hasBackups: Boolean = false,
    ) : DialogState
    data class RommConfirm(val action: RommConfirmAction, val downloadKey: String? = null, val fromQuickMenu: Boolean = false) : DialogState
    data class RommPlatformToggle(val items: List<RommPlatformToggleItem>, val selectedIndex: Int = 0) : DialogState
    data class RommCollectionToggle(val items: List<RommCollectionToggleItem>, val selectedIndex: Int = 0) : DialogState
    data class SyncHistory(val entries: List<SyncHistoryRow>, val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : DialogState
    data class SyncErrors(val errors: List<dev.cannoli.scorza.romm.sync.SyncFailure>, val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : DialogState
    data class RommSavesMenu(val title: String, val options: List<String>, val selectedIndex: Int = 0) : DialogState
    data class SaveBackupGames(val games: List<dev.cannoli.scorza.romm.sync.SaveBackupGame>, val selectedIndex: Int = 0) : DialogState
    data class SaveBackupList(val tag: String, val base: String, val displayName: String, val backups: List<dev.cannoli.scorza.romm.sync.SaveBackup>, val selectedIndex: Int = 0, val fromContextMenu: Boolean = false) : DialogState
    data class SaveBackupRestoreConfirm(val tag: String, val base: String, val displayName: String, val stamp: Long, val dateLabel: String, val fromContextMenu: Boolean = false) : DialogState
    data class ConflictsMenu(val rows: List<ConflictRow>, val selectedIndex: Int = 0, val fromSaveSyncMenu: Boolean = false) : DialogState
    data object SaveSyncChecking : DialogState
    data object ConflictsApplying : DialogState
    data class SaveSyncConflict(
        val conflict: dev.cannoli.scorza.romm.sync.PreLaunchOutcome.Conflict,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class SaveSyncStaleBlock(
        val stale: dev.cannoli.scorza.romm.sync.PreLaunchOutcome.KnownStaleBlock,
        val tag: String,
        val base: String,
        val selectedIndex: Int = 0,
    ) : DialogState
    data class RommVersionPicker(
        val gameName: String,
        val tag: String,
        val members: List<RommVariantEntry>,
        val selectedIndex: Int = 0,
    ) : DialogState
}

data class RommVariantEntry(val game: dev.cannoli.scorza.romm.RommGame, val label: String, val present: Boolean, val isPrimary: Boolean)

data class RommPlatformToggleItem(val tag: String, val displayName: String, val visible: Boolean)
data class RommCollectionToggleItem(val group: dev.cannoli.scorza.romm.RommCollectionGroup, val displayName: String, val visible: Boolean)

enum class ConflictChoice { KEEP_LOCAL, USE_SERVER, SKIP }
data class ConflictRow(
    val gameKey: String,
    val name: String,
    val choice: ConflictChoice = ConflictChoice.SKIP,
    val localMillis: Long? = null,
    val serverMillis: Long? = null,
)

enum class RommConfirmAction { REBUILD_CACHE, DISCONNECT, CANCEL_DOWNLOAD, CANCEL_ALL }

interface KeyboardHost {
    val keyboard: KeyboardState
    fun withKeyboard(keyboard: KeyboardState): DialogState
    val currentName: String get() = keyboard.text
    val cursorPos: Int get() = keyboard.cursorPos
    @get:androidx.annotation.StringRes val titleRes: Int? get() = null
}

fun DialogState.withMenuDelta(delta: Int): DialogState? = when (this) {
    is DialogState.ContextMenu -> {
        val size = options.size + unplaced.size
        if (size == 0) null
        else copy(selectedOption = (selectedOption + delta).mod(size))
    }
    is DialogState.BulkContextMenu -> {
        if (options.isEmpty()) null
        else copy(selectedOption = (selectedOption + delta).mod(options.size))
    }
    is DialogState.SaveSyncConflict -> copy(selectedIndex = (selectedIndex + delta).mod(2))
    is DialogState.SaveSyncStaleBlock -> copy(selectedIndex = (selectedIndex + delta).mod(2))
    else -> null
}
