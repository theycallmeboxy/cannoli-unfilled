package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.util.RomPaths
import java.io.File
import java.text.Normalizer

// The game key is the path under Roms/, so its first segment is the system's ROM folder. That is
// the tag for a system whose folder is its own ID; otherwise systems.json maps one to the other.
// A game in a system with an absolute folder (an absolute key) is never synced.
fun rommResolveGame(
    platformResolver: PlatformConfig,
    romDir: File,
): (String) -> Triple<String, String, String?>? = { gameKey ->
    val romFile = File(romDir, gameKey)
    if (RomPaths.isAbsolute(gameKey) || !romFile.exists()) {
        null
    } else {
        val folder = gameKey.substringBefore('/')
        val tag = platformResolver.tagForFolder(folder) ?: folder
        val base = Normalizer.normalize(File(gameKey).nameWithoutExtension, Normalizer.Form.NFC)
        Triple(tag, base, RomKeys.emulatorNameFor(platformResolver, tag, gameKey))
    }
}
