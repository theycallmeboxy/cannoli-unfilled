package dev.cannoli.scorza.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File

/** Answers with the canonical spelling of every platform that has a folder, so the dashboard keys
 *  its labels, icons, grouping and game requests off the same string the games endpoint accepts.
 *  Never fails the request: an empty list leaves the dashboard empty, where an error status would
 *  leave it unable to render at all. */
internal fun KitchenHttpServer.handleTags(): Response {
    val canonical = allPlatformTags()
        .filter { it !in KitchenHttpServer.RESERVED_APP_TAGS }
        .associateBy { it.lowercase(java.util.Locale.ROOT) }
    // The walker knows each system's own folder, under Roms/ or absolute.
    val walker = romDirectoryWalker
    val tags = if (walker != null) {
        canonical.values.filter { walker.resolveTagDir(it)?.isDirectory == true }.sorted()
    } else {
        romsRootProvider().listFiles { f -> f.isDirectory }
            ?.mapNotNull { canonical[it.name.lowercase(java.util.Locale.ROOT)] }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
    }
    return jsonResponse(200, TagsResponse.serializer(), TagsResponse(tags))
}

internal fun KitchenHttpServer.handleApps(): Response {
    val repo = appsRepository ?: return errorResponse(503, "apps not available")
    return jsonResponse(
        200, AppsResponse.serializer(),
        AppsResponse(
            tools = repo.artBasenames(dev.cannoli.scorza.model.AppType.TOOL),
            ports = repo.artBasenames(dev.cannoli.scorza.model.AppType.PORT),
        ),
    )
}

internal fun KitchenHttpServer.handleList(dir: File, displayPath: String, recursive: Boolean = false, roots: List<File> = defaultRoots()): Response {
    if (!isSecure(dir, roots)) {
        return errorResponse(403, "forbidden")
    }
    if (!dir.exists() || !dir.isDirectory) {
        return jsonResponse(200, DirListResponse.serializer(), DirListResponse(displayPath, emptyList()))
    }
    val entries: List<DirEntry> = if (recursive) {
        val dirPath = dir.toPath()
        val files = mutableListOf<Pair<String, File>>()
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (!isSecure(child, roots)) continue
                if (child.name == ".bundled_version") continue
                if (child.isDirectory) stack.add(child)
                else files.add(dirPath.relativize(child.toPath()).toString() to child)
            }
        }
        files.sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
            .map { (relativePath, f) -> DirEntry(relativePath, "file", f.length()) }
    } else {
        dir.listFiles()
            ?.filter { it.name != ".bundled_version" }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
            ?.map { f ->
                DirEntry(
                    name = f.name,
                    type = if (f.isDirectory) "dir" else "file",
                    size = if (f.isFile) f.length() else 0L,
                )
            } ?: emptyList()
    }
    return jsonResponse(200, DirListResponse.serializer(), DirListResponse(displayPath, entries))
}

internal fun KitchenHttpServer.handleMkdir(dir: File, roots: List<File> = defaultRoots()): Response {
    if (!isSecure(dir, roots)) {
        return errorResponse(403, "forbidden")
    }
    return if (dir.exists()) {
        jsonResponse(200, OkResponse.serializer(), OkResponse(ok = true, existed = true))
    } else if (dir.mkdirs()) {
        okResponse(201)
    } else {
        errorResponse(500, "mkdir failed")
    }
}

internal fun KitchenHttpServer.handleDelete(dir: File, filename: String, roots: List<File> = defaultRoots()): Response {
    val file = File(dir, filename)
    if (!isSecure(file, roots)) {
        return errorResponse(403, "forbidden")
    }
    if (!file.exists()) {
        return errorResponse(404, "not found")
    }
    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
    return if (ok) {
        okResponse()
    } else {
        errorResponse(500, "delete failed")
    }
}

internal fun KitchenHttpServer.handleMove(resourceRoot: File, subpath: String, body: String, roots: List<File> = defaultRoots(), requireSameTopDir: Boolean = true): Response {
    val to = try {
        org.json.JSONObject(body).optString("to", "")
    } catch (_: Exception) { "" }
    if (to.isEmpty()) {
        return errorResponse(400, "missing 'to' field")
    }

    if (requireSameTopDir) {
        val srcRoot = subpath.substringBefore("/", "")
        val dstRoot = to.substringBefore("/", "")
        if (srcRoot.isEmpty() || dstRoot.isEmpty() || !srcRoot.equals(dstRoot, ignoreCase = true)) {
            return errorResponse(403, "moves must stay within the same subdirectory")
        }
    }

    val src = File(resourceRoot, subpath)
    val dst = File(resourceRoot, to)

    if (!isSecure(src, roots) || !isSecure(dst, roots)) {
        return errorResponse(403, "forbidden")
    }
    if (!src.exists()) {
        return errorResponse(404, "source not found")
    }
    if (dst.exists()) {
        // On storage that ignores case, renaming "rom.zip" to "Rom.zip" finds itself as the
        // destination; going through a temporary name lets the new spelling stick.
        val sameFile = try { java.nio.file.Files.isSameFile(src.toPath(), dst.toPath()) } catch (_: Exception) { false }
        if (!sameFile || src.name == dst.name) {
            return errorResponse(409, "destination already exists")
        }
        val temp = File(src.parentFile, ".${src.name}.renaming")
        return if (src.renameTo(temp) && temp.renameTo(dst)) okResponse() else errorResponse(500, "move failed")
    }

    dst.parentFile?.mkdirs()
    return if (src.renameTo(dst)) {
        okResponse()
    } else {
        errorResponse(500, "move failed")
    }
}

internal fun KitchenHttpServer.handleUpload(destDir: File, session: NanoHTTPD.IHTTPSession, roots: List<File> = defaultRoots()): Response {
    if (!isSecure(destDir, roots)) return errorResponse(403, "forbidden")
    destDir.mkdirs()
    val contentType = session.headers["content-type"] ?: ""
    if (session.headers["transfer-encoding"]?.contains("chunked", true) == true) {
        return errorResponse(400, "chunked upload not supported")
    }
    if (!contentType.startsWith("multipart/form-data")) {
        return errorResponse(400, "multipart upload required")
    }
    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
    val written = try {
        KitchenUpload.streamTo(session.inputStream, contentType, contentLength) { name ->
            File(destDir, sanitizeFilename(name))
        }
    } catch (e: Exception) {
        dev.cannoli.scorza.util.KitchenLog.logError("upload failed", e)
        return errorResponse(500, "upload failed")
    }
    return jsonResponse(200, UploadResponse.serializer(), UploadResponse(files = written))
}
