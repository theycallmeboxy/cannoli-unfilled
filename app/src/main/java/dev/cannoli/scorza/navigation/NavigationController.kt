package dev.cannoli.scorza.navigation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.ui.screens.DialogState
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@ActivityScoped
class NavigationController @Inject constructor() {
    val screenStack = mutableStateListOf<LauncherScreen>(LauncherScreen.SystemList)
    val currentScreen: LauncherScreen get() = screenStack.last()
    val dialogState = MutableStateFlow<DialogState>(DialogState.None)

    var navigating = false
    var activeListState: LazyListState? = null
    var pendingRecentlyPlayedReorder = false
    var lastKeyRepeatCount: Int = 0

    var selectHeld = false
    var selectDown = false
    var capsBeforeSymbols = false
    // A game or app row on the main list whose context menu is open.
    var pendingMainListItem: dev.cannoli.scorza.model.ListItem? = null

    fun push(screen: LauncherScreen) { screenStack.add(screen) }

    fun pop() {
        if (screenStack.size > 1) screenStack.removeAt(screenStack.lastIndex)
    }

    fun replaceTop(screen: LauncherScreen) {
        if (screenStack.isNotEmpty()) screenStack[screenStack.lastIndex] = screen
    }
}
