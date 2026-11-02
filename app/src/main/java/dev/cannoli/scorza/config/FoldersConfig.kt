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
    data class Folder(val node: FolderNode) : FolderEntry
    data class Platform(val tag: String) : FolderEntry

    /**
     * An Android app. [intent] is null for a plain launch through the app's launcher activity;
     * an entry that names an activity, action or extras launches with exactly that intent.
     */
    data class App(val packageName: String, val name: String?, val intent: AppConfig?) : FolderEntry
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
 * A bad entry is skipped and reported in [errors] rather than discarding the file, so one typo
 * cannot hide every folder.
 */
class FoldersConfig(private val fileProvider: () -> File) {

    var roots: List<FolderNode> = emptyList()
        private set

    var errors: List<String> = emptyList()
        private set

    private var loadedMtime = 0L

    /** Writes [seed] to folders.json when the file is missing, then reads it. */
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
    fun reloadIfChanged(): Boolean {
        val file = fileProvider()
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (mtime == loadedMtime) return false
        read(file)
        return true
    }

    private fun read(file: File) {
        loadedMtime = if (file.exists()) file.lastModified() else 0L
        val parsed = if (!file.exists()) Parsed(emptyList(), emptyList()) else try {
            parse(file.readText(), file.name)
        } catch (e: java.io.IOException) {
            Parsed(emptyList(), listOf("${file.name}: unreadable (${e.message})"))
        } catch (e: JSONException) {
            Parsed(emptyList(), listOf("${file.name}: not valid JSON (${e.message})"))
        }
        roots = parsed.roots
        errors = parsed.errors
        for (error in errors) dev.cannoli.scorza.util.ErrorLog.write(error)
    }

    /** The folder at [path], or null when no folder has that path any more. */
    fun find(path: List<String>): FolderNode? {
        if (path.isEmpty()) return null
        var level = roots
        var node: FolderNode? = null
        for (name in path) {
            node = level.firstOrNull { it.name == name } ?: return null
            level = node.entries.filterIsInstance<FolderEntry.Folder>().map { it.node }
        }
        return node
    }

    /** Every platform tag placed in some folder. Those are left off the top level of the menu. */
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
    fun allApps(): List<FolderEntry.App> = buildList {
        fun walk(node: FolderNode) {
            for (entry in node.entries) when (entry) {
                is FolderEntry.App -> add(entry)
                is FolderEntry.Folder -> walk(entry.node)
                is FolderEntry.Platform -> {}
            }
        }
        roots.forEach(::walk)
    }

    /** The custom launch intent for [packageName], when its folder entry gives one. */
    fun intentFor(packageName: String): AppConfig? =
        allApps().firstOrNull { it.packageName == packageName && it.intent != null }?.intent

    internal data class Parsed(val roots: List<FolderNode>, val errors: List<String>)

    companion object {
        const val EMPTY = "{\n  \"folders\": []\n}\n"

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
            val roots = parseFolderList(folders, emptyList(), "folders", source, errors)
            return Parsed(roots, errors)
        }

        private fun parseFolderList(
            array: JSONArray,
            parentPath: List<String>,
            where: String,
            source: String,
            errors: MutableList<String>,
        ): List<FolderNode> {
            val nodes = mutableListOf<FolderNode>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i)
                if (obj == null) {
                    errors.add("$source: $where[$i] skipped: expected a folder object")
                    continue
                }
                parseFolder(obj, parentPath, "$where[$i]", source, errors)?.let { node ->
                    if (nodes.any { it.name == node.name }) {
                        errors.add("$source: $where[$i] skipped: a folder named \"${node.name}\" is already here")
                    } else {
                        nodes.add(node)
                    }
                }
            }
            return nodes
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
                    if (entry is FolderEntry.Folder &&
                        entries.any { it is FolderEntry.Folder && it.node.name == entry.node.name }
                    ) {
                        errors.add("$source: $itemWhere skipped: a folder named \"${entry.node.name}\" is already here")
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
                parseFolder(obj, path, where, source, errors)
                    ?.let { FolderEntry.Folder(it) }
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
            if (value is String) {
                require(value.isNotBlank()) { "\"app\" needs a package name" }
                return FolderEntry.App(value.trim(), name, null)
            }
            val obj = value as? JSONObject
                ?: throw IllegalArgumentException("\"app\" must be a package name or an object")
            val config = PlatformConfig.parseLaunchConfig(obj)
            // Only an object that says more than its package needs a custom intent. The intent
            // schema defaults the action to VIEW for ROM launches; an app with no action given
            // is opened like a home screen icon would.
            val custom = obj.has("activity") || obj.has("action") || obj.has("extras")
            val intent = if (!custom) null else if (obj.has("action")) config else config.copy(action = Intent.ACTION_MAIN)
            return FolderEntry.App(config.packageName, name ?: obj.optString("name", "").ifBlank { null }, intent)
        }
    }
}
