package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.library.EmulatorRef
import dev.cannoli.scorza.config.library.GameSystem
import dev.cannoli.scorza.config.library.Library
import dev.cannoli.scorza.config.library.Placeholders

/** What a picker row says can't work yet, checked before a launch would fail. */
enum class ChoiceMark { NONE, APP_MISSING, CORE_MISSING }

/**
 * One row of the "Launch With" and "Default Emulator" pickers: a choice from a system's
 * `emulators` list, by [name] as overrides.json names it.
 */
data class EmulatorChoice(
    val name: String,
    val label: String,
    /** The system's first choice. */
    val isDefault: Boolean,
    /** What the game, or the system, launches with now. */
    val isCurrent: Boolean,
    val mark: ChoiceMark,
)

object EmulatorChoices {

    /**
     * [system]'s choices in order, with [current] (a choice name) marked. A choice whose app is
     * not installed is marked so, and otherwise one whose core its emulator reports missing; an
     * emulator that can't report its cores is never marked.
     */
    fun build(
        library: Library,
        system: GameSystem,
        current: String?,
        isInstalled: (String) -> Boolean,
        coreStatus: (packageName: String, core: String) -> CoreStatus,
    ): List<EmulatorChoice> = system.emulators.mapIndexed { index, ref ->
        EmulatorChoice(
            name = ref.choiceName,
            label = library.choiceLabel(ref),
            isDefault = index == 0,
            isCurrent = ref.choiceName == current,
            mark = markFor(library, ref, isInstalled, coreStatus),
        )
    }

    /** What would stop [system]'s default choice from launching, for the editing views to show. */
    fun defaultMark(
        library: Library,
        system: GameSystem,
        isInstalled: (String) -> Boolean,
        coreStatus: (packageName: String, core: String) -> CoreStatus,
    ): ChoiceMark = system.emulators.firstOrNull()?.let { markFor(library, it, isInstalled, coreStatus) } ?: ChoiceMark.NONE

    private fun markFor(
        library: Library,
        ref: EmulatorRef,
        isInstalled: (String) -> Boolean,
        coreStatus: (packageName: String, core: String) -> CoreStatus,
    ): ChoiceMark {
        val pkg = library.emulators[ref.emulator]?.intent?.packageName ?: return ChoiceMark.NONE
        val core = ref.params[Placeholders.CORE]
        return when {
            !isInstalled(pkg) -> ChoiceMark.APP_MISSING
            core != null && coreStatus(pkg, core) == CoreStatus.MISSING -> ChoiceMark.CORE_MISSING
            else -> ChoiceMark.NONE
        }
    }
}
