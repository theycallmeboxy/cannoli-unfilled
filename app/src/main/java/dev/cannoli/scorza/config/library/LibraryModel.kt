package dev.cannoli.scorza.config.library

import dev.cannoli.scorza.config.AppConfig

/**
 * One entry of Config/emulators.json: a launch intent under a name the other files refer to.
 * Extra values may hold `{placeholders}`; see [Placeholders].
 */
data class Emulator(
    val name: String,
    val displayName: String?,
    val intent: AppConfig,
) {
    /** Every placeholder the intent's extras use, built-in ones included. */
    val placeholders: Set<String> get() = Placeholders.usedBy(intent)

    val label: String get() = displayName ?: name
}

/**
 * One item of a system's `emulators` list: the emulator's name, values for its own
 * placeholders (such as `core`), and an optional [label] for the picker and overrides.json.
 */
data class EmulatorRef(
    val emulator: String,
    val params: Map<String, String> = emptyMap(),
    val label: String? = null,
) {
    /** What overrides.json and the picker call this choice: its label, or else the emulator's name. */
    val choiceName: String get() = label ?: emulator

    /** True when overrides.json's [value] names this choice. */
    fun matches(value: String): Boolean = value == label || value == emulator
}

/** One entry of Config/systems.json. Named GameSystem so it never shadows java.lang.System. */
data class GameSystem(
    val id: String,
    val name: String,
    /** Its folder under Roms/. */
    val roms: String,
    /** Lower case, no dot. Null accepts every file but the global ignore list. */
    val extensions: Set<String>?,
    val arcade: Boolean,
    /** In order; the first is the default. */
    val emulators: List<EmulatorRef>,
)

/** One entry of Config/shortcuts.json: a custom intent shown like an app. It takes no game. */
data class Shortcut(
    val name: String,
    val displayName: String,
    val intent: AppConfig,
)

/** One item of Config/directory.json's tree. */
sealed interface DirectoryItem {
    data class Folder(val name: String, val items: List<DirectoryItem>) : DirectoryItem
    data class SystemItem(val id: String) : DirectoryItem
    data class ShortcutItem(val name: String) : DirectoryItem
    data class AppItem(val packageName: String, val name: String?) : DirectoryItem
}

/**
 * What a game launches with: the emulator, the system's choice that picked it, and [vars] to
 * pass to EmulatorIntentBuilder.resolve, which fills the built-in placeholders itself.
 */
data class LaunchPlan(
    val emulator: Emulator,
    val ref: EmulatorRef,
    val vars: Map<String, String>,
) {
    /** Placeholders nothing will fill. Empty for any plan built from a validated [Library]. */
    val unfilled: Set<String> get() = Placeholders.unfilled(emulator, vars)
}

/**
 * The five config files after cross-checking: every name in here resolves. Maps keep file order.
 */
data class Library(
    val emulators: Map<String, Emulator> = emptyMap(),
    val systems: Map<String, GameSystem> = emptyMap(),
    val shortcuts: Map<String, Shortcut> = emptyMap(),
    val directory: List<DirectoryItem> = emptyList(),
    /** ROM path under Roms/ to the choice name from that game's system. */
    val overrides: Map<String, String> = emptyMap(),
) {
    /** Every system ID placed somewhere in the directory. */
    fun placedSystemIds(): Set<String> = buildSet {
        fun walk(items: List<DirectoryItem>) {
            for (item in items) when (item) {
                is DirectoryItem.Folder -> walk(item.items)
                is DirectoryItem.SystemItem -> add(item.id)
                is DirectoryItem.ShortcutItem, is DirectoryItem.AppItem -> {}
            }
        }
        walk(directory)
    }

    /** The choice for [romPath] (under Roms/): its override when one still matches, else the default. */
    fun choiceFor(system: GameSystem, romPath: String): EmulatorRef? {
        val override = overrides[romPath]
        return override?.let { name -> system.emulators.firstOrNull { it.matches(name) } }
            ?: system.emulators.firstOrNull()
    }

    /** How [romPath] in [systemId] launches, or null when the system is unknown or has no emulator. */
    fun launchFor(systemId: String, romPath: String): LaunchPlan? {
        val system = systems[systemId] ?: return null
        val ref = choiceFor(system, romPath) ?: return null
        val emulator = emulators[ref.emulator] ?: return null
        return LaunchPlan(emulator, ref, Placeholders.varsFor(ref))
    }
}
