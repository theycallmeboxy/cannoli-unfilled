package dev.cannoli.scorza.server

import dev.cannoli.scorza.util.RomPaths
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File

internal fun KitchenHttpServer.artMime(file: File): String = when (file.extension.lowercase(java.util.Locale.ROOT)) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    else -> "application/octet-stream"
}

internal fun KitchenHttpServer.gameSavesResponse(platformTag: String, base: String): Response {
    val dir = File(cannoliRoot, "Saves/$platformTag")
    val files = dir.listFiles { f -> f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true) }
        ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
    val items = files.map { f -> FileEntry(f.name, f.length(), f.lastModified()) }
    return jsonResponse(200, FilesResponse.serializer(), FilesResponse(items))
}

internal fun KitchenHttpServer.gameGuidesResponse(platformTag: String, base: String): Response {
    val dir = File(cannoliRoot, "Guides/$platformTag/$base")
    val files = dir.listFiles { f -> f.isFile }
        ?.sortedBy { it.name.lowercase(java.util.Locale.ROOT) } ?: emptyList()
    val items = files.map { f -> FileEntry(f.name, f.length(), f.lastModified()) }
    return jsonResponse(200, FilesResponse.serializer(), FilesResponse(items))
}

internal fun KitchenHttpServer.gameRomFiles(rom: dev.cannoli.scorza.model.Rom): List<File> =
    romDirectoryWalker?.gameFiles(rom.path) ?: listOf(rom.path)

internal fun KitchenHttpServer.gameRomsResponse(rom: dev.cannoli.scorza.model.Rom): Response {
    val romsRoot = romsRootProvider()
    val items = gameRomFiles(rom).map { f ->
        val rel = RomPaths.keyFor(f, romsRoot).replace(File.separatorChar, '/')
        val size = if (f.exists()) f.length() else 0L
        RomFileEntry(name = f.name, path = rel, size = size, modified = f.lastModified())
    }
    return jsonResponse(200, RomFilesResponse.serializer(), RomFilesResponse(items))
}

internal fun KitchenHttpServer.resolveRomFile(rom: dev.cannoli.scorza.model.Rom, fileParam: String?): File? {
    if (fileParam.isNullOrBlank()) return rom.path
    val target = RomPaths.resolve(romsRootProvider(), fileParam)
    if (!isSecure(target)) return null
    return gameRomFiles(rom).firstOrNull { it.absolutePath == target.absolutePath }
}

internal fun KitchenHttpServer.streamUploadToFile(destFile: File, session: NanoHTTPD.IHTTPSession): Response? {
    if (session.headers["transfer-encoding"]?.contains("chunked", true) == true) {
        return errorResponse(400, "chunked upload not supported")
    }
    destFile.parentFile?.mkdirs()
    val contentType = session.headers["content-type"] ?: ""
    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
    if (contentType.startsWith("multipart/form-data")) {
        KitchenUpload.streamTo(session.inputStream, contentType, contentLength) { destFile }
    } else {
        destFile.outputStream().use { fos ->
            val bos = java.io.BufferedOutputStream(fos, 262144)
            val buf = ByteArray(262144)
            var remaining = contentLength
            val input = session.inputStream
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                bos.write(buf, 0, n)
                remaining -= n
            }
            bos.flush()
        }
    }
    return null
}

internal fun KitchenHttpServer.handleGames(
    method: String,
    segments: List<String>,
    query: Map<String, String>,
    session: NanoHTTPD.IHTTPSession,
): Response {
    val repo = romsRepository
        ?: return errorResponse(503, "games not available")
    if (segments.isEmpty()) return errorResponse(404, "not found")
    val platformTag = canonicalTag(segments[0])
        ?: return errorResponse(404, "platform not found")

    if (segments.size == 1) {
        if (method != "GET") return errorResponse(405, "method not allowed")
        return jsonResponse(
            200,
            GamesResponse.buildList(
                repo, cannoliRoot, romsRootProvider(), platformTag, platformTag,
                romDirectoryWalker, isArcadePlatform(platformTag),
                isCancelled = { clientGone() },
            ),
        )
    }

    val romId = segments[1].toLongOrNull()
    val rom = romId?.let { repo.gameById(it) }
    if (rom == null || !rom.platformTag.equals(platformTag, ignoreCase = true)) {
        return errorResponse(404, "game not found")
    }
    val base = java.text.Normalizer.normalize(rom.path.nameWithoutExtension, java.text.Normalizer.Form.NFC)

    when (segments.size) {
        2 -> return when (method) {
            "GET" -> jsonResponse(200, GamesResponse.buildOne(repo, cannoliRoot, romsRootProvider(), platformTag, platformTag, rom.id, romDirectoryWalker)!!)
            "DELETE" -> {
                romDirectoryWalker?.arcadeSupportDir(rom.path, isArcadePlatform(platformTag))
                    ?.deleteRecursively()
                val gameDir = romDirectoryWalker?.gameDirectory(rom.path)
                if (gameDir != null) gameDir.deleteRecursively()
                else gameRomFiles(rom).forEach { if (isSecure(it)) it.delete() }
                if (query["purge"] == "true") {
                    File(cannoliRoot, "Saves/$platformTag").listFiles { f ->
                        f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true)
                    }?.forEach { it.delete() }
                    File(cannoliRoot, "Guides/$platformTag/$base").deleteRecursively()
                    rom.artFile?.let { if (isSecure(it)) it.delete() }
                }
                repo.deleteRom(rom.id)
                scanPlatform?.invoke(platformTag)
                okResponse()
            }
            else -> errorResponse(405, "method not allowed")
        }
        3 -> return when (segments[2]) {
            "rom" -> {
                val romFile = resolveRomFile(rom, query["file"])
                if (romFile == null || !romFile.exists()) {
                    return errorResponse(404, "not found")
                }
                when (method) {
                    "GET" -> fileResponse(romFile, "application/octet-stream")
                    "DELETE" -> {
                        val gameDir = romDirectoryWalker?.gameDirectory(rom.path)
                        if (gameDir != null) gameDir.deleteRecursively() else romFile.delete()
                        scanPlatform?.invoke(platformTag)
                        okResponse()
                    }
                    else -> errorResponse(405, "method not allowed")
                }
            }
            "roms" -> {
                if (method != "GET") return errorResponse(405, "method not allowed")
                gameRomsResponse(rom)
            }
            "art" -> when (method) {
                "GET" -> {
                    val art = GamesResponse.resolveArtFile(cannoliRoot, platformTag, base)
                    if (art == null || !art.exists() || !isSecure(art)) {
                        return errorResponse(404, "not found")
                    }
                    fileResponse(art, artMime(art))
                }
                "POST" -> {
                    val name = query["name"]
                    if (name.isNullOrBlank()) return errorResponse(400, "name param required")
                    val ext = name.substringAfterLast('.', "png").lowercase(java.util.Locale.ROOT)
                    val artDir = File(cannoliRoot, "Art/$platformTag")
                    artDir.listFiles { f ->
                        f.isFile && f.nameWithoutExtension.equals(base, ignoreCase = true)
                    }?.forEach { it.delete() }
                    val dest = File(artDir, "$base.$ext")
                    if (!isSecure(dest)) return errorResponse(403, "forbidden")
                    streamUploadToFile(dest, session)?.let { return it }
                    okResponse()
                }
                "DELETE" -> {
                    val art = GamesResponse.resolveArtFile(cannoliRoot, platformTag, base)
                    if (art == null || !art.exists()) return errorResponse(404, "not found")
                    if (!isSecure(art)) return errorResponse(403, "forbidden")
                    art.delete()
                    okResponse()
                }
                else -> errorResponse(405, "method not allowed")
            }
            "saves" -> when (method) {
                "GET" -> {
                    val fileName = query["file"]
                    if (fileName.isNullOrBlank()) {
                        gameSavesResponse(platformTag, base)
                    } else {
                        val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                        if (!isSecure(target) || !target.exists()) {
                            return errorResponse(404, "not found")
                        }
                        fileResponse(target, "application/octet-stream")
                    }
                }
                "POST" -> {
                    val name = query["name"]
                    if (name.isNullOrBlank()) return errorResponse(400, "name param required")
                    val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    if (ext.isEmpty()) return errorResponse(400, "name needs an extension")
                    val dest = File(cannoliRoot, "Saves/$platformTag/$base.$ext")
                    if (!isSecure(dest)) return errorResponse(403, "forbidden")
                    streamUploadToFile(dest, session)?.let { return it }
                    okResponse()
                }
                "DELETE" -> {
                    val fileName = query["file"]
                    if (fileName.isNullOrBlank()) return errorResponse(400, "file param required")
                    val target = File(File(cannoliRoot, "Saves/$platformTag"), File(fileName).name)
                    if (!isSecure(target)) return errorResponse(403, "forbidden")
                    if (!target.exists()) return errorResponse(404, "not found")
                    target.delete()
                    okResponse()
                }
                else -> errorResponse(405, "method not allowed")
            }
            "guides" -> {
                val guideDir = File(cannoliRoot, "Guides/$platformTag/$base")
                when (method) {
                    "GET" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) {
                            gameGuidesResponse(platformTag, base)
                        } else {
                            val target = File(guideDir, File(fileName).name)
                            if (!isSecure(target) || !target.exists()) {
                                return errorResponse(404, "not found")
                            }
                            fileResponse(target, "application/octet-stream")
                        }
                    }
                    "POST" -> {
                        val name = query["name"]
                        if (name.isNullOrBlank()) return errorResponse(400, "name param required")
                        val dest = File(guideDir, File(name).name)
                        if (!isSecure(dest)) return errorResponse(403, "forbidden")
                        streamUploadToFile(dest, session)?.let { return it }
                        okResponse()
                    }
                    "DELETE" -> {
                        val fileName = query["file"]
                        if (fileName.isNullOrBlank()) return errorResponse(400, "file param required")
                        val target = File(guideDir, File(fileName).name)
                        if (!isSecure(target)) return errorResponse(403, "forbidden")
                        if (!target.exists()) return errorResponse(404, "not found")
                        target.delete()
                        okResponse()
                    }
                    else -> errorResponse(405, "method not allowed")
                }
            }
            "move" -> {
                if (method != "POST") return errorResponse(405, "method not allowed")
                val folder = query["folder"] ?: ""
                val gameDir = romDirectoryWalker?.gameDirectory(rom.path)
                val unit = gameDir ?: rom.path
                val romsRoot = romsRootProvider()
                var platformDir = unit
                while (platformDir.parentFile != null && platformDir.parentFile != romsRoot) {
                    platformDir = platformDir.parentFile!!
                }
                // Not under Roms/: the game is in a system's absolute folder, or nowhere it may go.
                if (platformDir.parentFile == null) {
                    platformDir = romDirectoryWalker?.let { w ->
                        w.tagDirFor(platformTag).takeIf { it.absolutePath in w.absoluteFolders() }
                    } ?: return errorResponse(403, "forbidden")
                }
                val destDir = if (folder.isBlank()) platformDir else File(platformDir, folder)
                if (!destDir.isDirectory) return errorResponse(400, "destination folder not found")
                val target = File(destDir, unit.name)
                if (!isSecure(unit) || !isSecure(target)) return errorResponse(403, "forbidden")
                if (target.canonicalPath == unit.canonicalPath) return okResponse()
                if (target.exists()) return errorResponse(409, "a game with that name already exists in the destination")
                if (!unit.renameTo(target)) return errorResponse(500, "move failed")
                val newPrimary = if (gameDir == null) target else File(target, rom.path.name)
                val newRelPath = RomPaths.keyFor(newPrimary, romsRoot)
                repo.updateRomPath(rom.id, newRelPath)
                scanPlatform?.invoke(platformTag)
                okResponse()
            }
            "rename" -> {
                if (method != "POST") return errorResponse(405, "method not allowed")
                val name = query["name"]
                if (name.isNullOrBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                    return errorResponse(400, "invalid name")
                }
                if (!isSecure(rom.path)) return errorResponse(403, "forbidden")
                val renamer = atomicRename ?: return errorResponse(500, "rename unavailable")
                val result = renamer.rename(rom.path, name, platformTag)
                when {
                    result.success -> {
                        val newPrimary = result.newPrimary
                        if (newPrimary != null) {
                            val newRelPath = RomPaths.keyFor(newPrimary, romsRootProvider())
                            repo.updateRomPath(rom.id, newRelPath)
                        }
                        scanPlatform?.invoke(platformTag)
                        okResponse()
                    }
                    result.error == dev.cannoli.scorza.util.AtomicRename.RenameError.ALREADY_EXISTS ->
                        errorResponse(409, "a game with that name already exists")
                    else -> errorResponse(500, "rename failed")
                }
            }
            else -> errorResponse(404, "not found")
        }
        else -> return errorResponse(404, "not found")
    }
}
