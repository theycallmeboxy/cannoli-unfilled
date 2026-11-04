package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val serverJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
internal data class OkResponse(
    val ok: Boolean = true,
    val existed: Boolean? = null,
)

@Serializable
internal data class ErrorResponse(val error: String)

@Serializable
internal data class TagsResponse(val tags: List<String>)

@Serializable
internal data class AppsResponse(val tools: List<String>, val ports: List<String>)

@Serializable
internal data class FileEntry(
    val name: String,
    val size: Long,
    val modified: Long,
)

@Serializable
internal data class FilesResponse(val files: List<FileEntry>)

@Serializable
internal data class RomFileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
)

@Serializable
internal data class RomFilesResponse(val files: List<RomFileEntry>)

@Serializable
internal data class DirEntry(
    val name: String,
    val type: String,
    val size: Long,
)

@Serializable
internal data class DirListResponse(
    val path: String,
    val entries: List<DirEntry>,
)

@Serializable
internal data class UploadResponse(
    val ok: Boolean = true,
    val files: List<String>,
)

@Serializable
internal data class AuthStatusResponse(val required: Boolean)

@Serializable
internal data class InfoResponse(val name: String, val version: Int)

@Serializable
internal data class VolumeEntry(
    val id: String,
    val label: String,
    val totalBytes: Long,
    val freeBytes: Long,
)

@Serializable
internal data class VolumesResponse(val volumes: List<VolumeEntry>)

@Serializable
internal data class ApkUploadResponse(
    val ok: Boolean = true,
    val installId: String,
)

@Serializable
internal data class ApkCapabilityResponse(val canInstall: Boolean)

@Serializable
internal data class ApkStatusResponse(
    val status: String,
    val message: String? = null,
)

internal fun <T> KitchenHttpServer.jsonResponse(code: Int, serializer: KSerializer<T>, value: T): Response =
    jsonResponse(code, serverJson.encodeToString(serializer, value))

internal fun KitchenHttpServer.okResponse(code: Int = 200): Response =
    jsonResponse(code, OkResponse.serializer(), OkResponse())

internal fun KitchenHttpServer.errorResponse(code: Int, message: String): Response =
    jsonResponse(code, ErrorResponse.serializer(), ErrorResponse(message))
