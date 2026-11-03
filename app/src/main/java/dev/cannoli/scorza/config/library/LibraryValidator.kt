package dev.cannoli.scorza.config.library

import dev.cannoli.scorza.config.library.LibraryParser.DIRECTORY
import dev.cannoli.scorza.config.library.LibraryParser.EMULATORS
import dev.cannoli.scorza.config.library.LibraryParser.OVERRIDES
import dev.cannoli.scorza.config.library.LibraryParser.SHORTCUTS
import dev.cannoli.scorza.config.library.LibraryParser.SYSTEMS

/** A [Library] whose every reference resolves, and a message for each one that was dropped. */
data class Validated(val library: Library, val errors: List<String>)

/**
 * Checks the names the files use for each other. A reference to something missing is a bad entry:
 * it is dropped and reported, and everything else is kept.
 *
 * - a system's emulator must be in emulators.json, and the system must give a value for every
 *   placeholder that emulator uses beyond the built-in ones;
 * - the directory's systems and shortcuts must be in systems.json and shortcuts.json;
 * - an override must be under some system's ROM folder and name one of that system's choices;
 * - a RomM slug belongs to one system: the first in the file keeps it.
 */
object LibraryValidator {

    fun validate(
        emulators: Map<String, Emulator>,
        systems: Map<String, GameSystem>,
        shortcuts: Map<String, Shortcut>,
        directory: List<DirectoryItem>,
        overrides: Map<String, String>,
    ): Validated {
        val errors = mutableListOf<String>()
        val goodSystems = systems.mapValuesTo(LinkedHashMap()) { (id, system) ->
            system.copy(emulators = system.emulators.filter { ref -> checkRef(id, ref, emulators, errors) })
        }
        claimRommSlugs(goodSystems, errors)
        val goodDirectory = checkItems(directory, emptyList(), goodSystems, shortcuts, errors)
        val goodOverrides = LinkedHashMap<String, String>()
        for ((path, choice) in overrides) {
            val owners = goodSystems.values.filter { path.startsWith(it.roms + "/") }
            when {
                owners.isEmpty() ->
                    errors.add("$OVERRIDES: \"$path\" skipped: no system in $SYSTEMS uses that ROM folder")
                owners.none { system -> system.emulators.any { it.matches(choice) } } ->
                    errors.add(
                        "$OVERRIDES: \"$path\" skipped: \"$choice\" is not in the emulators of " +
                            owners.joinToString { it.id }
                    )
                else -> goodOverrides[path] = choice
            }
        }
        return Validated(Library(emulators, goodSystems, shortcuts, goodDirectory, goodOverrides), errors)
    }

    /** Drops each RomM slug a system shares with one earlier in the file. */
    private fun claimRommSlugs(systems: MutableMap<String, GameSystem>, errors: MutableList<String>) {
        val owners = HashMap<String, String>()
        for (entry in systems.entries) {
            val system = entry.value
            val kept = system.romm.filter { slug ->
                val owner = owners.putIfAbsent(slug, system.id) ?: return@filter true
                errors.add("$SYSTEMS: ${system.id}.romm \"$slug\" skipped: $owner already uses it")
                false
            }
            if (kept.size != system.romm.size) entry.setValue(system.copy(romm = kept))
        }
    }

    private fun checkRef(
        systemId: String,
        ref: EmulatorRef,
        emulators: Map<String, Emulator>,
        errors: MutableList<String>,
    ): Boolean {
        val where = "$SYSTEMS: $systemId.emulators \"${ref.choiceName}\" skipped"
        val emulator = emulators[ref.emulator]
        if (emulator == null) {
            errors.add("$where: no emulator named \"${ref.emulator}\" in $EMULATORS")
            return false
        }
        val missing = Placeholders.unfilled(emulator, Placeholders.varsFor(ref))
        if (missing.isNotEmpty()) {
            errors.add(
                "$where: ${ref.emulator} uses ${missing.joinToString { "{$it}" }}, so the entry needs " +
                    missing.joinToString(" and ") { "\"$it\"" }
            )
            return false
        }
        return true
    }

    private fun checkItems(
        items: List<DirectoryItem>,
        path: List<String>,
        systems: Map<String, GameSystem>,
        shortcuts: Map<String, Shortcut>,
        errors: MutableList<String>,
    ): List<DirectoryItem> {
        val where = if (path.isEmpty()) "the main menu" else path.joinToString(" / ")
        return items.mapNotNull { item ->
            when (item) {
                is DirectoryItem.Folder ->
                    item.copy(items = checkItems(item.items, path + item.name, systems, shortcuts, errors))
                is DirectoryItem.SystemItem -> item.takeIf { it.id in systems }
                    ?: null.also { errors.add("$DIRECTORY: system \"${item.id}\" in $where skipped: not in $SYSTEMS") }
                is DirectoryItem.ShortcutItem -> item.takeIf { it.name in shortcuts }
                    ?: null.also { errors.add("$DIRECTORY: shortcut \"${item.name}\" in $where skipped: not in $SHORTCUTS") }
                is DirectoryItem.AppItem -> item
            }
        }
    }
}
