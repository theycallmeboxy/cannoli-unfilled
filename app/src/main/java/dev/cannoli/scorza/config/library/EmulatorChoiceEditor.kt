package dev.cannoli.scorza.config.library

import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-device emulator choices, written through [LibraryConfig.edit]: "Launch With" sets one
 * game's entry in overrides.json, and "Default Emulator" moves a choice to the top of its
 * system's list in systems.json. Choices are named as overrides.json names them, by
 * [EmulatorRef.choiceName].
 *
 * Each returns null once written, or the reason it was refused, and then the file is left as it
 * was.
 */
class EmulatorChoiceEditor(private val config: LibraryConfig) {

    /**
     * Makes the game at [romPath] (its key: the path under Roms/, or the absolute path in a system
     * whose folder is absolute) launch with [choice] from [system]'s list. The
     * system's default removes the game's override instead, so it follows the system again.
     */
    fun setGameChoice(system: GameSystem, romPath: String, choice: String): String? {
        if (system.emulators.none { it.choiceName == choice }) return "${system.name} has no emulator \"$choice\""
        val isDefault = system.emulators.first().choiceName == choice
        if (isDefault && !hasOverrideEntry(romPath)) return null
        return config.edit(LibraryFile.OVERRIDES) { json ->
            if (json.has(GAMES) && json.optJSONObject(GAMES) == null) {
                return@edit "${LibraryParser.OVERRIDES}: \"$GAMES\" is not an object, so it was left alone"
            }
            val games = json.optJSONObject(GAMES) ?: JSONObject().also { json.put(GAMES, it) }
            if (isDefault) games.remove(romPath) else games.put(romPath, choice)
            null
        }
    }

    /**
     * Moves [choice] to the top of system [systemId]'s `emulators` list in systems.json, which
     * makes it the default. The entry itself, comment keys included, moves unchanged.
     */
    fun makeDefault(systemId: String, choice: String): String? {
        val system = config.library.systems[systemId] ?: return "systems.json has no system \"$systemId\""
        if (system.emulators.firstOrNull()?.choiceName == choice) return null
        return config.edit(LibraryFile.SYSTEMS) { json ->
            val entry = json.optJSONObject(systemId) ?: return@edit "systems.json has no system \"$systemId\""
            val list = entry.optJSONArray(EMULATORS) ?: return@edit "$systemId lists no emulators"
            val items = (0 until list.length()).map { list.get(it) }
            val at = items.indexOfFirst { choiceNameOf(it) == choice }
            if (at < 0) return@edit "$systemId has no emulator \"$choice\""
            val reordered = JSONArray().put(items[at])
            items.forEachIndexed { i, item -> if (i != at) reordered.put(item) }
            entry.put(EMULATORS, reordered)
            null
        }
    }

    // The raw file, not the parsed overrides: an entry naming a choice the system no longer has is
    // dropped from the library but still sits in the file, and choosing the default clears it.
    private fun hasOverrideEntry(romPath: String): Boolean = try {
        JSONObject(config.currentText(LibraryFile.OVERRIDES)).optJSONObject(GAMES)?.has(romPath) == true
    } catch (_: org.json.JSONException) {
        true
    }

    private companion object {
        const val GAMES = "games"
        const val EMULATORS = "emulators"

        /** The name overrides.json would use for one item of a system's raw `emulators` list. */
        fun choiceNameOf(item: Any?): String? = when (item) {
            is String -> item.trim()
            is JSONObject -> item.optString("name", "").trim().ifEmpty { item.optString("emulator", "").trim() }
            else -> null
        }
    }
}
