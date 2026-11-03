package dev.cannoli.scorza.util

import java.io.File

/**
 * A system's `roms` in systems.json is a folder under the ROM root or, when it starts with "/",
 * an absolute folder anywhere on the device.
 *
 * A game's key is the path the database stores for it, and the key in overrides.json and save
 * sync. It is the game's path under the ROM root when the file is inside it, as before, and
 * otherwise its absolute path. Games of a relative system keep their old keys; games of an
 * absolute system are keyed by their absolute path, which is unique and stays the same as long as
 * systems.json names the same folder.
 */
object RomPaths {

    fun isAbsolute(path: String): Boolean = path.startsWith("/")

    /** The file or folder [path] names: itself when absolute, else that path under [romRoot]. */
    fun resolve(romRoot: File, path: String): File = if (isAbsolute(path)) File(path) else File(romRoot, path)

    /** [file]'s key: its path under [romRoot] when it is inside it, else its absolute path. */
    fun keyFor(file: File, romRoot: File): String {
        val abs = file.absolutePath
        val root = romRoot.absolutePath.trimEnd(File.separatorChar) + File.separator
        return if (abs.startsWith(root)) abs.substring(root.length) else abs
    }

    /** The key of a system's folder [folder], which every one of its games' keys starts with, then a separator. */
    fun systemKey(romRoot: File, folder: String): String =
        if (isAbsolute(folder)) keyFor(File(folder), romRoot) else folder
}
