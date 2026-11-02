package dev.cannoli.scorza.config.library

import org.json.JSONArray
import org.json.JSONObject

/**
 * The menu's folder edits, written to Config/directory.json through [LibraryConfig.edit]: new,
 * rename, delete, move, remove, reorder and fill. Items are named by their [key] among their
 * siblings, and folders by their path of names from the main menu down; an empty path is the main
 * menu itself.
 *
 * Every edit returns null once written, or the reason it was refused, and then the file is left
 * as it was.
 */
class DirectoryEditor(private val config: LibraryConfig) {

    fun createFolder(parent: List<String>, name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "A folder needs a name"
        return editAt(parent) { entries ->
            if (entries.hasKey(folderKey(clean))) return@editAt Refused("A folder named \"$clean\" is already here")
            entries + JSONObject().put(FOLDER, clean).put(ITEMS, JSONArray())
        }
    }

    fun renameFolder(path: List<String>, newName: String): String? {
        val clean = newName.trim()
        if (clean.isEmpty()) return "A folder needs a name"
        if (path.isEmpty()) return "Nothing to rename"
        if (clean == path.last()) return null
        return editAt(path.dropLast(1)) { entries ->
            if (entries.hasKey(folderKey(clean))) return@editAt Refused("A folder named \"$clean\" is already here")
            val folder = entries.find(folderKey(path.last())) ?: return@editAt Refused(GONE)
            folder.put(FOLDER, clean)
            entries
        }
    }

    /**
     * Removes the folder at [path] and puts what it held in its place in the parent. A subfolder
     * whose name is taken there gets a number, and an app or shortcut already there is not doubled.
     */
    fun deleteFolder(path: List<String>): String? {
        if (path.isEmpty()) return "Nothing to delete"
        return editAt(path.dropLast(1)) { entries ->
            val at = entries.indexOfFirst { keyOf(it) == folderKey(path.last()) }
            if (at < 0) return@editAt Refused(GONE)
            val doomed = entries[at] as JSONObject
            val rest = entries.toMutableList().apply { removeAt(at) }
            val moved = mutableListOf<Any?>()
            for (entry in doomed.entries()) {
                val key = keyOf(entry)
                when {
                    entry is JSONObject && key?.startsWith(FOLDER_PREFIX) == true ->
                        moved.add(entry.put(FOLDER, uniqueName(key.removePrefix(FOLDER_PREFIX), rest + moved)))
                    key != null && (rest + moved).hasKey(key) -> {}
                    else -> moved.add(entry)
                }
            }
            rest.apply { addAll(at, moved) }
        }
    }

    /**
     * Moves the item [key] from the folder at [from] to the end of the folder at [target]. A null
     * [from] adds an item that is not in the directory yet: a system not placed anywhere, an app
     * or a shortcut. An app or shortcut already in [target] is just taken out of [from].
     */
    fun move(key: String, from: List<String>?, target: List<String>): String? {
        if (key.startsWith(FOLDER_PREFIX) && from != null) {
            val own = from + key.removePrefix(FOLDER_PREFIX)
            if (target.take(own.size) == own) return "A folder cannot go inside itself"
        }
        if (from == target) return null
        return config.edit(LibraryFile.DIRECTORY) { root ->
            ensureItems(root)
            val to = containerAt(root, target) ?: return@edit GONE
            val item: JSONObject
            if (from != null) {
                val source = containerAt(root, from) ?: return@edit GONE
                val entries = source.entries()
                item = entries.find(key) ?: return@edit GONE
                if (key.startsWith(FOLDER_PREFIX) && to.entries().hasKey(key)) {
                    return@edit "A folder named \"${key.removePrefix(FOLDER_PREFIX)}\" is already there"
                }
                source.setEntries(entries.filterNot { it === item })
            } else {
                item = newItem(key) ?: return@edit "That can't be added here"
                if (key.startsWith(SYSTEM_PREFIX) && key in placedSystems(root)) {
                    return@edit "That system is already in the menu"
                }
            }
            val targetEntries = to.entries()
            if (!targetEntries.hasKey(key)) to.setEntries(targetEntries + item)
            null
        }
    }

    /** Takes [key] out of the folder at [path]. A system taken out is no longer on the menu. */
    fun remove(path: List<String>, key: String): String? =
        editAt(path) { entries -> entries.filterNot { keyOf(it) == key } }

    /** Puts the folder at [path] in [orderedKeys] order. Items not named keep their order after them. */
    fun reorder(path: List<String>, orderedKeys: List<String>): String? = editAt(path) { entries ->
        val byKey = entries.filterIsInstance<JSONObject>().associateBy { keyOf(it) }
        val named = orderedKeys.distinct().mapNotNull { byKey[it] }
        named + entries.filterNot { e -> named.any { it === e } }
    }

    /**
     * What "Add To This Folder" saves: the apps in the folder at [path] become exactly [apps]
     * (package to display name), and each of [systems] not placed anywhere yet is added. Items
     * already there keep their place and their entry as written; new ones go at the end, systems
     * first.
     */
    fun fill(path: List<String>, apps: List<Pair<String, String>>, systems: List<String>): String? =
        config.edit(LibraryFile.DIRECTORY) { root ->
            ensureItems(root)
            val placed = placedSystems(root)
            val folder = containerAt(root, path) ?: return@edit GONE
            val wanted = apps.map { APP_PREFIX + it.first }.toSet()
            val kept = folder.entries().filter { keyOf(it)?.startsWith(APP_PREFIX) != true || keyOf(it) in wanted }
            val added = systems.distinct().filter { SYSTEM_PREFIX + it !in placed }
                .map { JSONObject().put(SYSTEM, it) } +
                apps.filter { !kept.hasKey(APP_PREFIX + it.first) }
                    .map { (pkg, name) -> JSONObject().put(APP, pkg).put(NAME, name) }
            folder.setEntries(kept + added)
            null
        }

    /** What [adoptRomFolder] did. */
    sealed interface Adoption {
        /** [systemId] was copied into systems.json, with any of its emulators that were missing. */
        data class Added(val systemId: String, val name: String) : Adoption
        /** systems.template.json has no system for that folder. */
        data object NoTemplate : Adoption
        data class Failed(val reason: String) : Adoption
    }

    /**
     * Copies the system for the ROM folder [folder] from systems.template.json into systems.json,
     * and the emulators it lists from emulators.template.json into emulators.json unless they are
     * there already. The template system is the one whose ID, or else whose `roms`, is the folder
     * name, ignoring case; the copy's `roms` is set to the folder as it is spelled on disk.
     */
    fun adoptRomFolder(folder: String): Adoption {
        val systems = templateJson(SYSTEMS_TEMPLATE) ?: return Adoption.Failed("$SYSTEMS_TEMPLATE is missing")
        val id = templateIdFor(systems, folder) ?: return Adoption.NoTemplate
        val entry = JSONObject(systems.getJSONObject(id).toString())
        if (entry.optString(ROMS, id) != folder) entry.put(ROMS, folder)
        val wanted = entry.optJSONArray(EMULATORS)?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                when (val ref = list.opt(i)) {
                    is String -> ref.trim()
                    is JSONObject -> ref.optString("emulator", "").trim()
                    else -> null
                }?.ifEmpty { null }
            }
        }.orEmpty().distinct()
        val emulators = templateJson(EMULATORS_TEMPLATE) ?: return Adoption.Failed("$EMULATORS_TEMPLATE is missing")
        config.edit(LibraryFile.EMULATORS) { real ->
            for (name in wanted) {
                if (real.has(name)) continue
                emulators.optJSONObject(name)?.let { real.put(name, JSONObject(it.toString())) }
            }
            null
        }?.let { return Adoption.Failed(it) }
        config.edit(LibraryFile.SYSTEMS) { real ->
            if (real.has(id)) return@edit "systems.json already has $id"
            real.put(id, entry)
            null
        }?.let { return Adoption.Failed(it) }
        return Adoption.Added(id, entry.optString(NAME, "").trim().ifEmpty { id })
    }

    /** The display name of the template system [adoptRomFolder] would copy for [folder], if any. */
    fun templateSystemFor(folder: String): String? {
        val systems = templateJson(SYSTEMS_TEMPLATE) ?: return null
        val id = templateIdFor(systems, folder) ?: return null
        return systems.getJSONObject(id).optString(NAME, "").trim().ifEmpty { id }
    }

    private fun templateIdFor(systems: JSONObject, folder: String): String? {
        val ids = systems.keys().asSequence()
            .filter { !LibraryParser.isComment(it) && systems.optJSONObject(it) != null }
            .toList()
        return ids.firstOrNull { it.equals(folder, ignoreCase = true) }
            ?: ids.firstOrNull { systems.getJSONObject(it).optString(ROMS).equals(folder, ignoreCase = true) }
    }

    private fun templateJson(name: String): JSONObject? = config.templateText(name)?.let {
        try {
            JSONObject(it)
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private class Refused(val reason: String)

    private fun editAt(path: List<String>, change: (List<Any?>) -> Any): String? =
        config.edit(LibraryFile.DIRECTORY) { root ->
            ensureItems(root)
            val folder = containerAt(root, path) ?: return@edit GONE
            when (val result = change(folder.entries())) {
                is Refused -> result.reason
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    folder.setEntries(result as List<Any?>)
                    null
                }
            }
        }

    companion object {
        private const val ITEMS = "items"
        private const val FOLDER = "folder"
        private const val SYSTEM = "system"
        private const val APP = "app"
        private const val NAME = "name"
        private const val ROMS = "roms"
        private const val EMULATORS = "emulators"
        private const val FOLDER_PREFIX = "folder:"
        private const val SYSTEM_PREFIX = "system:"
        private const val APP_PREFIX = "app:"
        private const val GONE = "That folder is gone"
        const val SYSTEMS_TEMPLATE = "systems.template.json"
        const val EMULATORS_TEMPLATE = "emulators.template.json"

        private val KINDS = listOf(FOLDER, SYSTEM, "shortcut", APP)

        private fun folderKey(name: String) = FOLDER_PREFIX + name

        /** The item's [key] as the file spells it, or null for an entry that isn't an item. */
        private fun keyOf(entry: Any?): String? {
            val obj = entry as? JSONObject ?: return null
            val kind = KINDS.firstOrNull { obj.has(it) } ?: return null
            val value = (obj.opt(kind) as? String)?.trim()?.ifEmpty { null } ?: return null
            return "$kind:$value"
        }

        private fun newItem(key: String): JSONObject? {
            val kind = key.substringBefore(':')
            val value = key.substringAfter(':', "")
            if (kind == FOLDER || kind !in KINDS || value.isEmpty()) return null
            return JSONObject().put(kind, value)
        }

        private fun ensureItems(root: JSONObject) {
            if (root.optJSONArray(ITEMS) == null) root.put(ITEMS, JSONArray())
        }

        private fun containerAt(root: JSONObject, path: List<String>): JSONObject? {
            var node = root
            for (name in path) {
                node = node.entries().find(folderKey(name)) ?: return null
            }
            return node
        }

        private fun placedSystems(root: JSONObject): Set<String> = buildSet {
            fun walk(node: JSONObject) {
                for (entry in node.entries()) {
                    val key = keyOf(entry) ?: continue
                    if (key.startsWith(SYSTEM_PREFIX)) add(key)
                    if (key.startsWith(FOLDER_PREFIX)) walk(entry as JSONObject)
                }
            }
            walk(root)
        }

        private fun JSONObject.entries(): List<Any?> =
            optJSONArray(ITEMS)?.let { arr -> (0 until arr.length()).map { arr.opt(it) } }.orEmpty()

        private fun JSONObject.setEntries(entries: List<Any?>) {
            put(ITEMS, JSONArray().apply { entries.forEach { put(it) } })
        }

        private fun List<Any?>.find(key: String): JSONObject? = firstOrNull { keyOf(it) == key } as? JSONObject

        private fun List<Any?>.hasKey(key: String) = any { keyOf(it) == key }

        private fun uniqueName(name: String, siblings: List<Any?>): String {
            if (!siblings.hasKey(folderKey(name))) return name
            var n = 2
            while (siblings.hasKey(folderKey("$name ($n)"))) n++
            return "$name ($n)"
        }
    }
}
