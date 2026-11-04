package dev.cannoli.scorza.server

import dev.cannoli.scorza.config.CannoliPaths
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal data class MappingEntry(val file: String, val name: String)

@Serializable
internal data class MappingsResponse(val mappings: List<MappingEntry>)

/**
 * GET /api/mappings         -> { "mappings": [ { "file", "name" } ] }, the controller mappings saved
 *                              on the device, named by their display_name
 * GET /api/mappings/<file>  -> that .ini file
 */
internal fun KitchenHttpServer.handleMappings(segments: List<String>): Response {
    val dir = CannoliPaths(cannoliRoot).configInputMappings
    if (segments.isEmpty()) {
        val entries = dir.listFiles { f -> f.isFile && f.extension.equals("ini", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
            ?.map { MappingEntry(file = it.name, name = mappingDisplayName(it)) }
            .orEmpty()
        return jsonResponse(200, MappingsResponse.serializer(), MappingsResponse(entries))
    }
    if (segments.size != 1) return errorResponse(404, "not found")
    val file = File(dir, segments[0])
    if (!file.name.endsWith(".ini", ignoreCase = true) || file.parentFile != dir) return errorResponse(404, "not found")
    if (!file.isFile || !isSecure(file)) return errorResponse(404, "not found")
    return fileResponse(file, "text/plain")
}

private fun mappingDisplayName(file: File): String {
    val line = try {
        file.useLines { lines -> lines.firstOrNull { it.trimStart().startsWith("display_name=") } }
    } catch (_: Exception) { null }
    return line?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() } ?: file.nameWithoutExtension
}
