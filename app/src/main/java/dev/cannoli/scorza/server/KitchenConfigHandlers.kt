package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * A hand-editable config file Kitchen can read and replace. [save] checks the text before writing
 * it and returns the problems found; text that is not valid JSON is refused and leaves the file
 * as it was.
 */
class KitchenConfigFile(
    val read: () -> String,
    val problems: () -> List<String>,
    val save: (String) -> SaveResult,
) {
    data class SaveResult(val written: Boolean, val problems: List<String>)
}

/**
 * GET  /api/config/<name>  -> { "name", "text", "problems": [...] }
 * PUT  /api/config/<name>  body = the whole file -> { "saved", "problems": [...] }, 422 if refused
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
            val result = file.save(readBody(session))
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
