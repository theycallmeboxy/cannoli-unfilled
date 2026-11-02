package dev.cannoli.scorza.config.library

import android.content.Intent
import dev.cannoli.scorza.config.DataBinding
import dev.cannoli.scorza.config.ExtraValueKind
import dev.cannoli.scorza.config.PlatformConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One file's parse: the good entries in [value] and a message for each one skipped. [validJson]
 * is false when the text was not a usable document at all; [value] is then empty and the caller
 * should keep what it had.
 */
data class Parsed<T>(val value: T, val errors: List<String>, val validJson: Boolean = true)

/**
 * Parsers for the five files in Config/. Each skips a bad entry, reports it, and keeps the rest,
 * so one typo cannot hide everything else. A key starting with `_` is a comment wherever it
 * appears and is never read. References between files are checked later by [LibraryValidator].
 *
 * Launch intents use the same fields as before (CONFIG.md) and are parsed by
 * [PlatformConfig.parseLaunchConfig].
 */
object LibraryParser {

    const val EMULATORS = "emulators.json"
    const val SYSTEMS = "systems.json"
    const val SHORTCUTS = "shortcuts.json"
    const val DIRECTORY = "directory.json"
    const val OVERRIDES = "overrides.json"

    private const val ITEMS = "items"
    private const val GAMES = "games"

    /** Placeholders a shortcut may use. It has no game, so only its own package is known. */
    private val SHORTCUT_PLACEHOLDERS = setOf("package")

    internal fun isComment(key: String) = key.startsWith("_")

    fun parseEmulators(text: String, source: String = EMULATORS): Parsed<Map<String, Emulator>> =
        parseDocument(text, source, emptyMap()) { json, errors ->
            entries(json, source, errors) { name, obj ->
                val intent = PlatformConfig.parseLaunchConfig(obj)
                Emulator(name, obj.optString("name", "").trim().ifEmpty { null }, intent)
            }
        }

    fun parseSystems(text: String, source: String = SYSTEMS): Parsed<Map<String, GameSystem>> =
        parseDocument(text, source, emptyMap()) { json, errors ->
            entries(json, source, errors) { id, obj -> parseSystem(id, obj, source, errors) }
        }

    fun parseShortcuts(text: String, source: String = SHORTCUTS): Parsed<Map<String, Shortcut>> =
        parseDocument(text, source, emptyMap()) { json, errors ->
            entries(json, source, errors) { name, obj -> parseShortcut(name, obj) }
        }

    fun parseDirectory(text: String, source: String = DIRECTORY): Parsed<List<DirectoryItem>> =
        parseDocument(text, source, emptyList()) { json, errors ->
            val items = json.optJSONArray(ITEMS)
                ?: throw JSONException("missing the top-level \"$ITEMS\" list")
            parseItems(items, ITEMS, mutableSetOf(), source, errors)
        }

    fun parseOverrides(text: String, source: String = OVERRIDES): Parsed<Map<String, String>> =
        parseDocument(text, source, emptyMap()) { json, errors ->
            val games = json.optJSONObject(GAMES)
                ?: throw JSONException("missing the top-level \"$GAMES\" object")
            val out = LinkedHashMap<String, String>()
            for (key in games.keys()) {
                if (isComment(key)) continue
                val path = key.trim().removePrefix("/")
                val value = games.opt(key)
                if (path.isEmpty()) {
                    errors.add("$source: $GAMES.\"$key\" skipped: needs a ROM path")
                } else if (value !is String || value.isBlank()) {
                    errors.add("$source: $GAMES.\"$key\" skipped: expected an emulator name")
                } else {
                    out[path] = value.trim()
                }
            }
            out
        }

    private fun <T> parseDocument(
        text: String,
        source: String,
        empty: T,
        body: (JSONObject, MutableList<String>) -> T,
    ): Parsed<T> {
        val errors = mutableListOf<String>()
        return try {
            Parsed(body(JSONObject(text), errors), errors)
        } catch (e: JSONException) {
            Parsed(empty, listOf("$source: not valid JSON (${e.message})"), validJson = false)
        }
    }

    /** Each non-comment key of [json] whose value is an object, through [parse], in file order. */
    private fun <T> entries(
        json: JSONObject,
        source: String,
        errors: MutableList<String>,
        parse: (String, JSONObject) -> T,
    ): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        for (key in json.keys()) {
            if (isComment(key)) continue
            val obj = json.optJSONObject(key)
            when {
                key.isBlank() -> errors.add("$source: an entry with an empty name skipped")
                obj == null -> errors.add("$source: $key skipped: expected an object")
                else -> try {
                    out[key] = parse(key, obj)
                } catch (e: Exception) {
                    errors.add("$source: $key skipped: ${e.message}")
                }
            }
        }
        return out
    }

    private fun parseSystem(id: String, obj: JSONObject, source: String, errors: MutableList<String>): GameSystem {
        val name = obj.optString("name", "").trim().ifEmpty { id }
        val roms = obj.optString("roms", "").trim().trim('/').ifEmpty { id }
        val extensions = if (!obj.has("extensions")) null else {
            val arr = obj.optJSONArray("extensions")
                ?: throw IllegalArgumentException("\"extensions\" must be a list of file types")
            val set = (0 until arr.length()).map { arr.getString(it).trim().removePrefix(".").lowercase() }
                .filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
            if (set.isEmpty()) {
                errors.add("$source: $id.extensions ignored: an empty list would hide every game; leave it out to accept all")
                null
            } else set
        }
        val refs = mutableListOf<EmulatorRef>()
        val list = obj.opt("emulators")
        require(list == null || list is JSONArray) { "\"emulators\" must be a list" }
        if (list is JSONArray) for (i in 0 until list.length()) {
            val where = "$source: $id.emulators[$i]"
            try {
                val ref = parseRef(list.get(i), where, errors)
                if (refs.any { it.choiceName == ref.choiceName }) {
                    errors.add("$where skipped: \"${ref.choiceName}\" is already listed; give one a \"name\"")
                } else refs.add(ref)
            } catch (e: Exception) {
                errors.add("$where skipped: ${e.message}")
            }
        }
        return GameSystem(id, name, roms, extensions, obj.optBoolean("arcade", false), refs)
    }

    private fun parseRef(item: Any, where: String, errors: MutableList<String>): EmulatorRef = when (item) {
        is String -> {
            require(item.isNotBlank()) { "needs an emulator name" }
            EmulatorRef(item.trim())
        }
        is JSONObject -> {
            val emulator = item.optString("emulator", "").trim()
            require(emulator.isNotEmpty()) { "needs \"emulator\", the emulator's name" }
            val label = item.optString("name", "").trim().ifEmpty { null }
            val params = LinkedHashMap<String, String>()
            for (key in item.keys()) {
                if (isComment(key) || key == "emulator" || key == "name") continue
                val value = item.opt(key)
                when {
                    key in Placeholders.BUILT_IN ->
                        errors.add("$where: \"$key\" ignored: it is filled in at launch")
                    value is String -> params[key] = value
                    value is Number || value is Boolean -> params[key] = value.toString()
                    else -> errors.add("$where: \"$key\" ignored: expected text")
                }
            }
            EmulatorRef(emulator, params, label)
        }
        else -> throw IllegalArgumentException("expected an emulator name or an object")
    }

    private fun parseShortcut(name: String, obj: JSONObject): Shortcut {
        val parsed = PlatformConfig.parseLaunchConfig(obj)
        // Opened like a home screen icon unless it says otherwise, and with no MIME type unless it
        // gives one: a URI such as https://... must not be sent as */*.
        val intent = parsed.copy(
            action = if (obj.has("action")) parsed.action else Intent.ACTION_MAIN,
            mimeType = if (obj.has("mimeType")) parsed.mimeType else null,
        )
        when (intent.data) {
            is DataBinding.FileProvider, DataBinding.AbsolutePath, DataBinding.ExternalStorageSaf ->
                throw IllegalArgumentException("a shortcut takes no game, so data kind ${obj.optJSONObject("data")?.optString("kind")} doesn't apply")
            DataBinding.None, is DataBinding.CustomScheme -> {}
        }
        for (extra in intent.extras) {
            require(extra.kind !in ROM_EXTRA_KINDS) {
                "a shortcut takes no game, so extra \"${extra.key}\" can't pass one"
            }
        }
        val unknown = Placeholders.usedBy(intent) - SHORTCUT_PLACEHOLDERS
        require(unknown.isEmpty()) {
            "a shortcut takes no game, so nothing fills ${unknown.joinToString { "{$it}" }}"
        }
        return Shortcut(name, obj.optString("name", "").trim().ifEmpty { name }, intent)
    }

    private val ROM_EXTRA_KINDS = setOf(
        ExtraValueKind.FILE_PATH,
        ExtraValueKind.FILE_URI_STRING,
        ExtraValueKind.FILE_URI_PARCELABLE,
    )

    private val ITEM_KINDS = listOf("folder", "system", "shortcut", "app")

    /** [placed] is every system ID seen so far anywhere in the file: a system goes in one place. */
    private fun parseItems(
        arr: JSONArray,
        where: String,
        placed: MutableSet<String>,
        source: String,
        errors: MutableList<String>,
    ): List<DirectoryItem> {
        val out = mutableListOf<DirectoryItem>()
        val siblings = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val at = "$source: $where[$i]"
            val obj = arr.optJSONObject(i)
            if (obj == null) {
                errors.add("$at skipped: expected an object")
                continue
            }
            val kinds = ITEM_KINDS.filter { obj.has(it) }
            if (kinds.size != 1) {
                errors.add("$at skipped: expected one of \"folder\", \"system\", \"shortcut\" or \"app\"")
                continue
            }
            val kind = kinds.single()
            val value = obj.opt(kind)
            if (value !is String || value.isBlank()) {
                errors.add("$at skipped: \"$kind\" needs a name")
                continue
            }
            val name = value.trim()
            if (kind == "system" && !placed.add(name)) {
                errors.add("$at skipped: system \"$name\" is already placed; a system goes in one place")
                continue
            }
            if (kind != "system" && !siblings.add("$kind:$name")) {
                errors.add("$at skipped: $kind \"$name\" is already here")
                continue
            }
            out.add(
                when (kind) {
                    "folder" -> {
                        val items = obj.opt(ITEMS)
                        if (items != null && items !is JSONArray) {
                            errors.add("$at.$ITEMS ignored: expected a list")
                        }
                        DirectoryItem.Folder(
                            name,
                            (items as? JSONArray)?.let { parseItems(it, "$where[$i].$ITEMS", placed, source, errors) }
                                ?: emptyList(),
                        )
                    }
                    "system" -> DirectoryItem.SystemItem(name)
                    "shortcut" -> DirectoryItem.ShortcutItem(name)
                    else -> DirectoryItem.AppItem(name, obj.optString("name", "").trim().ifEmpty { null })
                }
            )
        }
        return out
    }
}
