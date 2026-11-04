package dev.cannoli.scorza.server

import dev.cannoli.scorza.config.library.LibraryConfig
import dev.cannoli.scorza.config.library.LibraryFile
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * A hand-editable config file Kitchen can read and replace. [save] checks the text before writing
 * it and returns the problems found; text that is not valid JSON is refused and leaves the file
 * as it was. A file without [save] is read-only.
 */
class KitchenConfigFile(
    val read: () -> String,
    val problems: () -> List<String>,
    val save: ((String) -> SaveResult)? = null,
) {
    data class SaveResult(val written: Boolean, val problems: List<String>)
}

/**
 * The five config files, writable, plus the three bundled templates, read-only. [onSaved] runs
 * after a file is written, on the server's thread, so the launcher can pick the change up.
 */
fun libraryConfigFiles(config: LibraryConfig, onSaved: () -> Unit): Map<String, KitchenConfigFile> = buildMap {
    for (file in LibraryFile.entries) {
        put(
            file.fileName,
            KitchenConfigFile(
                read = { config.currentText(file) },
                problems = { config.problems(file) },
                save = { text ->
                    val (written, problems) = config.replaceText(file, text)
                    if (written) onSaved()
                    KitchenConfigFile.SaveResult(written, problems)
                },
            ),
        )
    }
    for (name in LibraryConfig.TEMPLATES) {
        put(name, KitchenConfigFile(read = { config.templateText(name).orEmpty() }, problems = { emptyList() }))
    }
}

/**
 * GET  /api/config/<name>  -> { "name", "text", "problems": [...] }
 * PUT  /api/config/<name>  body = the whole file -> { "saved", "problems": [...] }, 422 if refused,
 *                          405 for a read-only file such as a template
 *
 * GET  /api/config/hints  -> what the config editor suggests from the device: see [handleConfigHints]
 *
 * Only the files in [KitchenHttpServer.configFiles] are reachable, by name, never by path.
 */
internal fun KitchenHttpServer.handleConfig(
    method: String,
    segments: List<String>,
    session: NanoHTTPD.IHTTPSession,
): Response {
    if (segments.isEmpty()) {
        if (method != "GET") return errorResponse(405, "method not allowed")
        return jsonResponse(200, JSONObject().put("files", JSONArray(configFiles.keys.sorted())).toString())
    }
    val name = segments.joinToString("/")
    // Config file names end in .json, so this can't hide one.
    if (name == HINTS) return if (method == "GET") handleConfigHints() else errorResponse(405, "method not allowed")
    val file = configFiles[name] ?: return errorResponse(404, "unknown config file")
    return when (method) {
        "GET" -> jsonResponse(
            200,
            JSONObject()
                .put("name", name)
                .put("text", file.read())
                .put("problems", JSONArray(file.problems()))
                .toString(),
        )
        "PUT" -> {
            val save = file.save
                ?: return errorResponse(405, "$name is read-only: copy entries from it into the real file")
            val result = save(readBody(session))
            jsonResponse(
                if (result.written) 200 else 422,
                JSONObject()
                    .put("saved", result.written)
                    .put("problems", JSONArray(result.problems))
                    .toString(),
            )
        }
        else -> errorResponse(405, "method not allowed")
    }
}

private const val HINTS = "hints"

/** More games than this and the editor gets the first ones only, flagged by "gamesTruncated". */
internal const val MAX_HINT_GAMES = 20_000

/**
 * What the config editor offers from the device rather than the files:
 * `{ "apps": [{ "package", "label" }], "cores": { "<package>": ["mgba_libretro", ...] },
 *    "romFolders": ["GBA", ...], "games": ["GBA/Golden Sun.gba", ...], "gamesTruncated" }`.
 * `romFolders` are the folders under Roms/, `games` the keys overrides.json uses, and `cores` what
 * each emulator answered the installed-cores query with (empty until the launcher has asked).
 * Each part is best effort: one that fails comes back empty.
 */
internal fun KitchenHttpServer.handleConfigHints(): Response {
    val apps = JSONArray()
    runCatching { installedAppsProvider() }.getOrDefault(emptyList()).forEach { (label, pkg) ->
        apps.put(JSONObject().put("package", pkg).put("label", label))
    }
    val cores = JSONObject()
    runCatching { installedCoresProvider() }.getOrDefault(emptyMap()).toSortedMap().forEach { (pkg, ids) ->
        cores.put(pkg, JSONArray(ids.sorted()))
    }
    val folders = runCatching {
        romsRootProvider().listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { it.name }
            ?.sortedBy { it.lowercase(java.util.Locale.ROOT) }
    }.getOrNull().orEmpty()
    val games = runCatching { romsRepository?.allRelativePaths() }.getOrNull().orEmpty()
    return jsonResponse(
        200,
        JSONObject()
            .put("apps", apps)
            .put("cores", cores)
            .put("romFolders", JSONArray(folders))
            .put("games", JSONArray(games.sortedBy { it.lowercase(java.util.Locale.ROOT) }.take(MAX_HINT_GAMES)))
            .put("gamesTruncated", games.size > MAX_HINT_GAMES)
            .toString(),
    )
}
