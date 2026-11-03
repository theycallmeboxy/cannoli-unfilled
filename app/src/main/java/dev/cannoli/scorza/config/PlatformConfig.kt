package dev.cannoli.scorza.config

import android.content.Intent
import dev.cannoli.scorza.config.library.GameSystem
import dev.cannoli.scorza.config.library.Library
import dev.cannoli.scorza.config.library.LaunchPlan
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.util.RomLayout
import dev.cannoli.scorza.util.RomPaths
import org.json.JSONObject

/**
 * The game systems the launcher knows, read from Config/systems.json through [library]. A
 * platform tag is a system ID in upper case, the form the database and the rest of the app use,
 * so lookups ignore case. Each system names its own folder under Roms/, which defaults to its ID,
 * or an absolute folder anywhere on the device (see [RomPaths]).
 *
 * Two systems whose IDs differ only by case share a tag, and two that name the same ROM folder
 * share that folder; in both cases the first one in the file wins.
 */
class PlatformConfig(private val library: () -> Library) : RomLayout {

    private class Index(val library: Library, val byTag: Map<String, GameSystem>, val byFolder: Map<String, String>)

    @Volatile private var index: Index? = null

    // Rebuilt only when the library object changes, which is on a reload.
    private fun index(): Index {
        val current = library()
        index?.takeIf { it.library === current }?.let { return it }
        val byTag = LinkedHashMap<String, GameSystem>()
        for (system in current.systems.values) byTag.putIfAbsent(system.id.uppercase(), system)
        val byFolder = HashMap<String, String>()
        for ((tag, system) in byTag) {
            if (!RomPaths.isAbsolute(system.roms)) byFolder.putIfAbsent(system.roms.lowercase(), tag)
        }
        return Index(current, byTag, byFolder).also { index = it }
    }

    fun systemFor(tag: String): GameSystem? = index().byTag[tag.uppercase()]

    fun isKnownTag(tag: String): Boolean = systemFor(tag) != null

    fun isArcade(tag: String): Boolean = systemFor(tag)?.arcade == true

    /** Every system's tag, in systems.json order. */
    fun getAllTags(): Set<String> = index().byTag.keys

    fun getDisplayName(tag: String): String = systemFor(tag)?.name ?: tag

    /**
     * The system's folder under Roms/, or its absolute folder when it starts with "/", or [tag]
     * itself for a tag no system claims.
     */
    override fun folderFor(tag: String): String = systemFor(tag)?.roms ?: tag

    override fun extensionsFor(tag: String): Set<String>? = systemFor(tag)?.extensions

    override fun absoluteFolders(): Set<String> = absoluteFolderTags().map(::folderFor).toSet()

    /** Every system's folder under Roms/, leaving out absolute folders. */
    fun romFolders(): List<String> = getAllTags().map(::folderFor).filterNot(RomPaths::isAbsolute).distinct()

    /** The tags of the systems whose folder is absolute, in systems.json order. */
    fun absoluteFolderTags(): List<String> = getAllTags().filter { RomPaths.isAbsolute(folderFor(it)) }

    /**
     * The tag of the system whose ROM folder under Roms/ is [folder], ignoring case, or null for
     * none. Systems with an absolute folder are never matched.
     */
    fun tagForFolder(folder: String): String? = index().byFolder[folder.lowercase()]

    /**
     * How the game at [romPath] (its key, as the database stores it; see [RomPaths]) launches: its
     * override from overrides.json, else the system's first emulator. Null when the system is
     * unknown or lists no emulator.
     */
    fun launchFor(tag: String, romPath: String): LaunchPlan? {
        val system = systemFor(tag) ?: return null
        return library().launchFor(system.id, romPath)
    }

    fun resolvePlatform(tag: String, gameCount: Int): Platform =
        Platform(tag = tag, displayName = getDisplayName(tag), gameCount = gameCount)

    companion object {
        /** Parses one launch intent object in the emulators.json schema. Throws on a bad entry. */
        internal fun parseLaunchConfig(obj: JSONObject): AppConfig = parseAppConfig(obj)

        private fun parseAppConfig(obj: JSONObject): AppConfig {
            val pkg = obj.optString("package", "").ifEmpty {
                throw IllegalArgumentException("AppConfig: missing required `package`")
            }
            val activity = obj.optString("activity", "").ifEmpty { null }
            val action = obj.optString("action", "").ifEmpty { null } ?: Intent.ACTION_VIEW
            val data = obj.optJSONObject("data")?.let(::parseDataBinding) ?: DataBinding.None
            val extras = obj.optJSONArray("extras")?.let { arr ->
                (0 until arr.length()).map { parseExtraSpec(arr.getJSONObject(it)) }
            } ?: emptyList()
            val mimeType = if (obj.has("mimeType")) {
                if (obj.isNull("mimeType")) null else obj.getString("mimeType")
            } else "*/*"
            val intentFlags = obj.optInt("intentFlags", Intent.FLAG_ACTIVITY_NEW_TASK)
            val launchMethod = parseLaunchMethod(obj.optString("launchMethod", "intent"))
            return AppConfig(pkg, activity, action, data, extras, mimeType, intentFlags, launchMethod)
        }

        private fun parseDataBinding(obj: JSONObject): DataBinding {
            val kind = obj.optString("kind", "")
            return when (kind) {
                "none" -> DataBinding.None
                "file_provider" -> DataBinding.FileProvider(grantPermission = obj.optBoolean("grantPermission", true))
                "absolute_path" -> DataBinding.AbsolutePath
                "external_storage_saf" -> DataBinding.ExternalStorageSaf
                "custom_scheme" -> DataBinding.CustomScheme(
                    scheme = obj.optString("scheme").ifEmpty {
                        throw IllegalArgumentException("custom_scheme: missing `scheme`")
                    },
                    authority = obj.optString("authority").ifEmpty {
                        throw IllegalArgumentException("custom_scheme: missing `authority`")
                    },
                )
                else -> throw IllegalArgumentException("Unknown data.kind: `$kind`")
            }
        }

        private fun parseExtraSpec(obj: JSONObject): ExtraSpec {
            val key = obj.optString("key").ifEmpty {
                throw IllegalArgumentException("ExtraSpec: missing `key`")
            }
            val kind = when (obj.optString("kind")) {
                "path" -> ExtraValueKind.FILE_PATH
                "uri_string" -> ExtraValueKind.FILE_URI_STRING
                "uri_parcelable" -> ExtraValueKind.FILE_URI_PARCELABLE
                "string_array" -> ExtraValueKind.STRING_ARRAY
                "string" -> ExtraValueKind.STRING
                "int" -> ExtraValueKind.INT
                "bool" -> ExtraValueKind.BOOL
                else -> throw IllegalArgumentException("ExtraSpec `${key}`: unknown kind `${obj.optString("kind")}`")
            }
            val values = if (kind == ExtraValueKind.STRING_ARRAY) {
                val arr = obj.optJSONArray("values")
                    ?: throw IllegalArgumentException("ExtraSpec `${key}`: kind `string_array` requires `values`")
                (0 until arr.length()).map { arr.getString(it) }
            } else null
            val value = if (kind == ExtraValueKind.STRING || kind == ExtraValueKind.INT || kind == ExtraValueKind.BOOL) {
                obj.optString("value").ifEmpty {
                    throw IllegalArgumentException("ExtraSpec `${key}`: kind `${obj.optString("kind")}` requires `value`")
                }
            } else null
            val map = obj.optJSONObject("map")?.let { m ->
                m.keys().asSequence().associateWith { m.getString(it) }
            }
            val whenExtension = obj.optJSONArray("whenExtension")?.let { arr ->
                (0 until arr.length())
                    .map { arr.getString(it).removePrefix(".").lowercase() }
                    .toSet()
            }
            return ExtraSpec(key, kind, values, value, map, whenExtension)
        }

        private fun parseLaunchMethod(s: String): LaunchMethod = when (s) {
            "intent" -> LaunchMethod.INTENT
            "shell" -> LaunchMethod.SHELL
            else -> throw IllegalArgumentException("Unknown launchMethod: `$s`")
        }
    }
}
