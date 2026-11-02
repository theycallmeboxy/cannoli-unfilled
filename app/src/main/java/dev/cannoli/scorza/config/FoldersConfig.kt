package dev.cannoli.scorza.config

import android.content.Intent
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** One folder of the main menu, from Config/folders.json. [path] is the names from the root down. */
data class FolderNode(
    val name: String,
    val path: List<String>,
    val entries: List<FolderEntry>,
)

sealed interface FolderEntry {
    /** What identifies the entry among its siblings, for edits that name one. */
    val key: String

    data class Folder(val node: FolderNode) : FolderEntry {
        override val key: String get() = "folder:${node.name}"
    }

    data class Platform(val tag: String) : FolderEntry {
        override val key: String get() = "platform:$tag"
    }

    /**
     * An Android app. [intent] is null for a plain launch through the app's launcher activity;
     * an entry that names an activity, action or extras launches with exactly that intent.
     */
    data class App(val packageName: String, val name: String?, val intent: AppConfig?) : FolderEntry {
        override val key: String get() = "app:$packageName"

        // The entry exactly as the file had it, so saving an edit elsewhere in the tree writes a
        // hand-written intent back unchanged. Outside the constructor so it is not part of equality.
        internal var source: JSONObject? = null
    }
}

/**
 * The user's folder tree, read from Config/folders.json. Folders hold platforms, apps and other
 * folders, nested to any depth, in the order the file lists them:
 *
 * ```
 * { "folders": [
 *   { "name": "Tools", "items": [ { "app": "com.android.settings" } ] },
 *   { "name": "Nintendo", "items": [
 *       { "platform": "GBA" },
 *       { "folder": { "name": "Hacks", "items": [ { "platform": "GBAHACKS" } ] } } ] }
 * ] }
 * ```
 *
 * The top-level list holds folders and may also hold apps, which then show on the main menu.
 *
 * A bad entry is skipped and reported in [errors] rather than discarding the file, so one typo
 * cannot hide every folder. The edit functions write the tree back, keeping the previous file
 * as folders.json.bak, and return null on success or the reason the edit was refused.
 */
class FoldersConfig(private val fileProvider: () -> File) {

    /** The main menu level: top-level folders and any apps listed beside them. */
    var root: FolderNode = FolderNode("", emptyList(), emptyList())
        private set

    val roots: List<FolderNode> get() = root.entries.filterIsInstance<FolderEntry.Folder>().map { it.node }

    /** Apps listed at the top level, which sit on the main menu itself. */
    val rootApps: List<FolderEntry.App> get() = root.entries.filterIsInstance<FolderEntry.App>()

    var errors: List<String> = emptyList()
        private set

    private var loadedMtime = 0L

    /** Writes [seed] to folders.json when the file is missing, then reads it. */
    @Synchronized
    fun load(seed: () -> String = { EMPTY }) {
        val file = fileProvider()
        if (!file.exists()) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(seed())
            } catch (e: java.io.IOException) {
                dev.cannoli.scorza.util.ErrorLog.write("folders.json seed failed: ${e.message}")
            }
        }
        read(file)
    }

    /** Re-reads folders.json when it changed on disk since the last read. True if it did. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val file = fileProvider()
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (mtime == loadedMtime) return false
        read(file)
        return true
    }

    private fun read(file: File) {
        loadedMtime = if (file.exists()) file.lastModified() else 0L
        val parsed = if (!file.exists()) Parsed(emptyRoot(), emptyList()) else try {
            parse(file.readText(), file.name)
        } catch (e: java.io.IOException) {
            Parsed(emptyRoot(), listOf("${file.name}: unreadable (${e.message})"))
        } catch (e: JSONException) {
            Parsed(emptyRoot(), listOf("${file.name}: not valid JSON (${e.message})"))
        }
        root = parsed.root
        errors = parsed.errors
        for (error in errors) dev.cannoli.scorza.util.ErrorLog.write(error)
    }

    /** The folder at [path], or null when no folder has that path any more. Empty is the root. */
    @Synchronized
    fun find(path: List<String>): FolderNode? {
        if (path.isEmpty()) return null
        var node = root
        for (name in path) {
            node = node.entries.filterIsInstance<FolderEntry.Folder>().firstOrNull { it.node.name == name }?.node
                ?: return null
        }
        return node
    }

    /** Every platform tag placed in some folder. Those are left off the top level of the menu. */
    @Synchronized
    fun platformTagsInFolders(): Set<String> = buildSet {
        fun walk(node: FolderNode) {
            for (entry in node.entries) when (entry) {
                is FolderEntry.Platform -> add(entry.tag)
                is FolderEntry.Folder -> walk(entry.node)
                is FolderEntry.App -> {}
            }
        }
        roots.forEach(::walk)
    }

    /** Every app entry in the tree, depth first. */
    @Synchronized
    fun allApps(): List<FolderEntry.App> = buildList {
        fun walk(node: FolderNode) {
            for (entry in node.entries) when (entry) {
                is FolderEntry.App -> add(entry)
                is FolderEntry.Folder -> walk(entry.node)
                is FolderEntry.Platform -> {}
            }
        }
        walk(root)
    }

    /** Every folder path, depth first, for a "move to" picker. */
    @Synchronized
    fun allFolderPaths(): List<List<String>> = buildList {
        fun walk(node: FolderNode) {
            for (entry in node.entries) if (entry is FolderEntry.Folder) {
                add(entry.node.path)
                walk(entry.node)
            }
        }
        walk(root)
    }

    /** The folder holding [entryKey], or null when it is in none (a platform on the main menu). */
    @Synchronized
    fun parentOf(entryKey: String): List<String>? {
        fun walk(node: FolderNode): List<String>? {
            for (entry in node.entries) {
                if (entry.key == entryKey) return node.path
                if (entry is FolderEntry.Folder) walk(entry.node)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    /** The custom launch intent for [packageName], when its folder entry gives one. */
    fun intentFor(packageName: String): AppConfig? =
        allApps().firstOrNull { it.packageName == packageName && it.intent != null }?.intent

    // ---- Edits ----

    @Synchronized
    fun createFolder(parent: List<String>, name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "A folder needs a name"
        return edit(parent) { entries ->
            if (entries.hasFolder(clean)) return@edit Refused("A folder named \"$clean\" is already here")
            entries + FolderEntry.Folder(FolderNode(clean, parent + clean, emptyList()))
        }
    }

    @Synchronized
    fun renameFolder(path: List<String>, newName: String): String? {
        val clean = newName.trim()
        if (clean.isEmpty()) return "A folder needs a name"
        if (path.isEmpty()) return "Nothing to rename"
        if (clean == path.last()) return null
        return edit(path.dropLast(1)) { entries ->
            if (entries.hasFolder(clean)) return@edit Refused("A folder named \"$clean\" is already here")
            entries.map {
                if (it is FolderEntry.Folder && it.node.name == path.last()) {
                    FolderEntry.Folder(it.node.copy(name = clean))
                } else it
            }
        }
    }

    /**
     * Removes the folder at [path] and moves what it held into its parent, where it was. A
     * subfolder whose name is taken there gets a number. Platforms leaving a top-level folder go
     * back to the main menu, which needs no entry.
     */
    @Synchronized
    fun deleteFolder(path: List<String>): String? {
        if (path.isEmpty()) return "Nothing to delete"
        val parent = path.dropLast(1)
        return edit(parent) { entries ->
            val at = entries.indexOfFirst { it is FolderEntry.Folder && it.node.name == path.last() }
            if (at < 0) return@edit Refused("That folder is gone")
            val doomed = (entries[at] as FolderEntry.Folder).node
            val rest = entries.toMutableList().apply { removeAt(at) }
            val moved = mutableListOf<FolderEntry>()
            for (entry in doomed.entries) {
                when {
                    entry is FolderEntry.Platform && parent.isEmpty() -> {}
                    entry is FolderEntry.Folder -> {
                        val name = uniqueName(entry.node.name, rest + moved)
                        moved.add(FolderEntry.Folder(entry.node.copy(name = name)))
                    }
                    rest.any { it.key == entry.key } -> {}
                    else -> moved.add(entry)
                }
            }
            rest.apply { addAll(at, moved) }
        }
    }

    /**
     * Moves [entry] from wherever it is now to the end of the folder at [target]; an empty
     * [target] is the main menu. A platform on the main menu belongs to no folder, so moving one
     * there only takes it out of the folder it was in.
     */
    @Synchronized
    fun move(entry: FolderEntry, target: List<String>): String? {
        if (entry is FolderEntry.Folder && target.take(entry.node.path.size) == entry.node.path) {
            return "A folder cannot go inside itself"
        }
        if (target.isNotEmpty() && find(target) == null) return "That folder is gone"
        val from = parentOf(entry.key)
        if (from == target && !(entry is FolderEntry.Platform && from.isEmpty())) return null
        val current = from?.let { path -> (if (path.isEmpty()) root else find(path))?.entries?.firstOrNull { it.key == entry.key } }
            ?: entry
        if (current is FolderEntry.Folder &&
            (if (target.isEmpty()) root else find(target))!!.entries.hasFolder(current.node.name)
        ) {
            return "A folder named \"${current.node.name}\" is already there"
        }
        var tree = root
        if (from != null) tree = editTree(tree, from) { it.filterNot { e -> e.key == entry.key } }
        if (!(current is FolderEntry.Platform && target.isEmpty())) {
            tree = editTree(tree, target) { it + current }
        }
        return save(tree)
    }

    /** Takes [entryKey] out of the folder at [path]. */
    @Synchronized
    fun remove(path: List<String>, entryKey: String): String? =
        edit(path) { entries -> entries.filterNot { it.key == entryKey } }

    /** Puts the folder at [path] in [orderedKeys] order. Entries not named keep their place at the end. */
    @Synchronized
    fun reorder(path: List<String>, orderedKeys: List<String>): String? = edit(path) { entries ->
        val byKey = entries.associateBy { it.key }
        orderedKeys.mapNotNull { byKey[it] } + entries.filter { it.key !in orderedKeys }
    }

    /**
     * Makes the apps in the folder at [path] exactly [apps] (package to display name). Apps already
     * there keep their place and their entry as written; new ones go at the end.
     */
    @Synchronized
    fun setApps(path: List<String>, apps: List<Pair<String, String>>): String? = edit(path) { entries ->
        val wanted = apps.map { it.first }.toSet()
        val kept = entries.filter { it !is FolderEntry.App || it.packageName in wanted }
        val present = kept.filterIsInstance<FolderEntry.App>().map { it.packageName }.toSet()
        kept + apps.filter { it.first !in present }.map { (pkg, name) -> FolderEntry.App(pkg, name, null) }
    }

    /**
     * Replaces the whole file with [text] once it parses. Returns whether it was written and the
     * problems found; text that is not JSON is refused and the file is left as it was.
     */
    @Synchronized
    fun replaceText(text: String): Pair<Boolean, List<String>> {
        val parsed = try {
            parse(text, FILE_NAME)
        } catch (e: JSONException) {
            return false to listOf("not valid JSON: ${e.message}")
        }
        val failure = writeFile(text)
        if (failure != null) return false to listOf(failure)
        root = parsed.root
        errors = parsed.errors
        return true to parsed.errors
    }

    /** The file as it is on disk, for an editor to start from. */
    @Synchronized
    fun currentText(): String {
        val file = fileProvider()
        return if (file.exists()) file.readText() else serialize(root)
    }

    private class Refused(val reason: String)

    private fun edit(path: List<String>, change: (List<FolderEntry>) -> Any): String? {
        if (path.isNotEmpty() && find(path) == null) return "That folder is gone"
        var refused: String? = null
        val tree = editTree(root, path) { entries ->
            when (val result = change(entries)) {
                is Refused -> { refused = result.reason; entries }
                else -> @Suppress("UNCHECKED_CAST") (result as List<FolderEntry>)
            }
        }
        return refused ?: save(tree)
    }

    private fun save(tree: FolderNode): String? {
        val normalized = withPaths(tree, emptyList())
        val failure = writeFile(serialize(normalized))
        if (failure == null) root = normalized
        return failure
    }

    private fun writeFile(text: String): String? {
        val file = fileProvider()
        return try {
            file.parentFile?.mkdirs()
            if (file.exists()) file.copyTo(File(file.parentFile, "$FILE_NAME.bak"), overwrite = true)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return "Could not save $FILE_NAME"
            }
            loadedMtime = file.lastModified()
            null
        } catch (e: java.io.IOException) {
            dev.cannoli.scorza.util.ErrorLog.write("$FILE_NAME write failed: ${e.message}")
            "Could not save $FILE_NAME"
        }
    }

    internal data class Parsed(val root: FolderNode, val errors: List<String>) {
        val roots: List<FolderNode> get() = root.entries.filterIsInstance<FolderEntry.Folder>().map { it.node }
    }

    companion object {
        const val FILE_NAME = "folders.json"
        const val EMPTY = "{\n  \"folders\": []\n}\n"

        private fun emptyRoot() = FolderNode("", emptyList(), emptyList())

        private fun List<FolderEntry>.hasFolder(name: String) =
            any { it is FolderEntry.Folder && it.node.name == name }

        private fun uniqueName(name: String, siblings: List<FolderEntry>): String {
            if (!siblings.hasFolder(name)) return name
            var n = 2
            while (siblings.hasFolder("$name ($n)")) n++
            return "$name ($n)"
        }

        /** Applies [change] to the entries of the folder at [path] inside [node]. */
        private fun editTree(node: FolderNode, path: List<String>, change: (List<FolderEntry>) -> List<FolderEntry>): FolderNode {
            if (path.isEmpty()) return node.copy(entries = change(node.entries))
            return node.copy(entries = node.entries.map { entry ->
                if (entry is FolderEntry.Folder && entry.node.name == path.first()) {
                    FolderEntry.Folder(editTree(entry.node, path.drop(1), change))
                } else entry
            })
        }

        /** Recomputes every folder's path after a rename or a move. */
        private fun withPaths(node: FolderNode, path: List<String>): FolderNode = node.copy(
            path = path,
            entries = node.entries.map { entry ->
                if (entry is FolderEntry.Folder) FolderEntry.Folder(withPaths(entry.node, path + entry.node.name)) else entry
            },
        )

        internal fun serialize(root: FolderNode): String {
            val folders = JSONArray()
            for (entry in root.entries) {
                folders.put(if (entry is FolderEntry.Folder) folderJson(entry.node) else entryJson(entry))
            }
            return JSONObject().put("folders", folders).toString(2) + "\n"
        }

        private fun folderJson(node: FolderNode): JSONObject {
            val items = JSONArray()
            for (entry in node.entries) items.put(entryJson(entry))
            return JSONObject().put("name", node.name).put("items", items)
        }

        private fun entryJson(entry: FolderEntry): JSONObject = when (entry) {
            is FolderEntry.Folder -> JSONObject().put("folder", folderJson(entry.node))
            is FolderEntry.Platform -> JSONObject().put("platform", entry.tag)
            is FolderEntry.App -> entry.source ?: JSONObject().put("app", entry.packageName).apply {
                if (entry.name != null) put("name", entry.name)
            }
        }

        /**
         * The first folders.json for an install that predates it: one folder per legacy
         * Tools/Ports list that has apps, in that list's order, so nothing on the menu moves.
         * Each list is (folder name, apps as display name to package).
         */
        fun seedFromLegacy(lists: List<Pair<String, List<Pair<String, String>>>>): String {
            val folders = JSONArray()
            for ((folderName, apps) in lists) {
                if (apps.isEmpty()) continue
                val items = JSONArray()
                for ((name, pkg) in apps) items.put(JSONObject().put("app", pkg).put("name", name))
                folders.put(JSONObject().put("name", folderName).put("items", items))
            }
            return JSONObject().put("folders", folders).toString(2) + "\n"
        }

        internal fun parse(text: String, source: String): Parsed {
            val json = JSONObject(text)
            val errors = mutableListOf<String>()
            val folders = json.optJSONArray("folders")
                ?: throw JSONException("missing the top-level \"folders\" list")
            val entries = mutableListOf<FolderEntry>()
            for (i in 0 until folders.length()) {
                val where = "folders[$i]"
                val obj = folders.optJSONObject(i)
                if (obj == null) {
                    errors.add("$source: $where skipped: expected a folder object")
                    continue
                }
                try {
                    // A top-level folder is written bare, { "name", "items" }. Apps may sit here
                    // too; a platform needs no entry to be on the main menu.
                    val entry = when {
                        obj.has("app") -> parseApp(obj)
                        obj.has("folder") || obj.has("platform") ->
                            throw IllegalArgumentException("only folders and apps go at the top level")
                        else -> parseFolder(obj, emptyList(), where, source, errors)?.let { FolderEntry.Folder(it) }
                    } ?: continue
                    if (entries.any { it.key == entry.key }) {
                        val what = if (entry is FolderEntry.Folder) "a folder named \"${entry.node.name}\"" else "that app"
                        errors.add("$source: $where skipped: $what is already here")
                        continue
                    }
                    entries.add(entry)
                } catch (e: IllegalArgumentException) {
                    errors.add("$source: $where skipped: ${e.message}")
                } catch (e: JSONException) {
                    errors.add("$source: $where skipped: ${e.message}")
                }
            }
            return Parsed(FolderNode("", emptyList(), entries), errors)
        }

        private fun parseFolder(
            obj: JSONObject,
            parentPath: List<String>,
            where: String,
            source: String,
            errors: MutableList<String>,
        ): FolderNode? {
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) {
                errors.add("$source: $where skipped: a folder needs a \"name\"")
                return null
            }
            val path = parentPath + name
            val items = obj.optJSONArray("items") ?: JSONArray()
            val entries = mutableListOf<FolderEntry>()
            for (i in 0 until items.length()) {
                val itemWhere = "$where.items[$i]"
                val item = items.optJSONObject(i)
                if (item == null) {
                    errors.add("$source: $itemWhere skipped: expected an object")
                    continue
                }
                try {
                    val entry = parseEntry(item, path, itemWhere, source, errors) ?: continue
                    if (entries.any { it.key == entry.key }) {
                        val what = if (entry is FolderEntry.Folder) "a folder named \"${entry.node.name}\"" else "that entry"
                        errors.add("$source: $itemWhere skipped: $what is already here")
                        continue
                    }
                    entries.add(entry)
                } catch (e: IllegalArgumentException) {
                    errors.add("$source: $itemWhere skipped: ${e.message}")
                } catch (e: JSONException) {
                    errors.add("$source: $itemWhere skipped: ${e.message}")
                }
            }
            return FolderNode(name, path, entries)
        }

        private fun parseEntry(
            item: JSONObject,
            path: List<String>,
            where: String,
            source: String,
            errors: MutableList<String>,
        ): FolderEntry? = when {
            item.has("folder") -> {
                val obj = item.optJSONObject("folder")
                    ?: throw IllegalArgumentException("\"folder\" must be an object")
                parseFolder(obj, path, where, source, errors)?.let { FolderEntry.Folder(it) }
            }
            item.has("platform") -> {
                val tag = item.optString("platform", "").trim()
                require(tag.isNotEmpty()) { "\"platform\" needs a tag" }
                FolderEntry.Platform(tag.uppercase())
            }
            item.has("app") -> parseApp(item)
            else -> throw IllegalArgumentException("expected \"folder\", \"platform\" or \"app\"")
        }

        // "name" may sit beside "app" or, for the object form, inside it.
        private fun parseApp(item: JSONObject): FolderEntry.App {
            val value = item.get("app")
            val name = item.optString("name", "").ifBlank { null }
            val entry = if (value is String) {
                require(value.isNotBlank()) { "\"app\" needs a package name" }
                FolderEntry.App(value.trim(), name, null)
            } else {
                val obj = value as? JSONObject
                    ?: throw IllegalArgumentException("\"app\" must be a package name or an object")
                val config = PlatformConfig.parseLaunchConfig(obj)
                // Only an object that says more than its package needs a custom intent. The intent
                // schema defaults the action to VIEW for ROM launches; an app with no action given
                // is opened like a home screen icon would.
                val custom = obj.has("activity") || obj.has("action") || obj.has("extras")
                val intent = if (!custom) null else if (obj.has("action")) config else config.copy(action = Intent.ACTION_MAIN)
                FolderEntry.App(config.packageName, name ?: obj.optString("name", "").ifBlank { null }, intent)
            }
            return entry.apply { source = item }
        }
    }
}
