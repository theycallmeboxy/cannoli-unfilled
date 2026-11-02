package dev.cannoli.scorza.config.library

import dev.cannoli.scorza.util.ErrorLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The five real config files, each with the text a new install starts from. */
enum class LibraryFile(val fileName: String, val emptyText: String) {
    EMULATORS(LibraryParser.EMULATORS, "{}\n"),
    SYSTEMS(LibraryParser.SYSTEMS, "{}\n"),
    SHORTCUTS(LibraryParser.SHORTCUTS, "{}\n"),
    DIRECTORY(LibraryParser.DIRECTORY, "{\n  \"items\": []\n}\n"),
    OVERRIDES(LibraryParser.OVERRIDES, "{\n  \"games\": {}\n}\n"),
}

/**
 * Reads Config/emulators.json, systems.json, shortcuts.json, directory.json and overrides.json
 * into one cross-checked [library].
 *
 * [load] creates any missing file as an empty document and copies the three reference templates
 * next to them, from [templateSource] (asset name to text), whenever their text differs. The
 * templates are never read back as config.
 *
 * A file that is not valid JSON keeps the last good state of that file and is never overwritten.
 * Bad entries are skipped and listed in [errors], which also go to Logs/error.log.
 */
class LibraryConfig(
    private val dirProvider: () -> File,
    private val templateSource: (String) -> String?,
) {
    var library: Library = Library()
        private set

    /** Every problem from the last read: each file's parse errors, then the cross-file ones. */
    var errors: List<String> = emptyList()
        private set

    private var emulators: Map<String, Emulator> = emptyMap()
    private var systems: Map<String, GameSystem> = emptyMap()
    private var shortcuts: Map<String, Shortcut> = emptyMap()
    private var directory: List<DirectoryItem> = emptyList()
    private var overrides: Map<String, String> = emptyMap()

    private val fileErrors = LinkedHashMap<LibraryFile, List<String>>()
    private var crossErrors: List<String> = emptyList()
    private val loadedMtimes = HashMap<LibraryFile, Long>()

    /** Creates missing files, refreshes the templates, and reads everything. */
    @Synchronized
    fun load() {
        val dir = dirProvider()
        dir.mkdirs()
        for (file in LibraryFile.entries) {
            val target = File(dir, file.fileName)
            if (target.exists()) continue
            try {
                target.writeText(file.emptyText)
            } catch (e: IOException) {
                ErrorLog.write("${file.fileName} create failed: ${e.message}")
            }
        }
        writeTemplates(dir)
        for (file in LibraryFile.entries) read(file)
        revalidate()
    }

    /** Re-reads the files that changed on disk since the last read. True if any did. */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val changed = LibraryFile.entries.filter { mtimeOf(it) != loadedMtimes[it] }
        if (changed.isEmpty()) return false
        changed.forEach(::read)
        revalidate()
        return true
    }

    /** The file as it is on disk, for an editor to start from. */
    @Synchronized
    fun currentText(file: LibraryFile): String {
        val target = fileFor(file)
        return if (target.exists()) target.readText() else file.emptyText
    }

    /**
     * Replaces [file] with [text] once it parses. Returns whether it was written and the problems
     * found, this file's and the cross-file ones; text that is not JSON is refused and the file is
     * left as it was.
     */
    @Synchronized
    fun replaceText(file: LibraryFile, text: String): Pair<Boolean, List<String>> {
        val parsed = parse(file, text)
        if (!parsed.validJson) return false to parsed.errors
        val failure = writeAtomic(fileFor(file), text, backup = true)
        if (failure != null) return false to listOf(failure)
        loadedMtimes[file] = mtimeOf(file)
        accept(file, parsed)
        revalidate()
        return true to parsed.errors + crossErrors
    }

    private fun fileFor(file: LibraryFile) = File(dirProvider(), file.fileName)

    private fun mtimeOf(file: LibraryFile): Long = fileFor(file).let { if (it.exists()) it.lastModified() else 0L }

    private fun read(file: LibraryFile) {
        val target = fileFor(file)
        loadedMtimes[file] = mtimeOf(file)
        val text = try {
            if (target.exists()) target.readText() else file.emptyText
        } catch (e: IOException) {
            fileErrors[file] = listOf("${file.fileName}: unreadable (${e.message})")
            return
        }
        val parsed = parse(file, text)
        if (parsed.validJson) accept(file, parsed) else fileErrors[file] = parsed.errors
    }

    private fun parse(file: LibraryFile, text: String): Parsed<*> = when (file) {
        LibraryFile.EMULATORS -> LibraryParser.parseEmulators(text)
        LibraryFile.SYSTEMS -> LibraryParser.parseSystems(text)
        LibraryFile.SHORTCUTS -> LibraryParser.parseShortcuts(text)
        LibraryFile.DIRECTORY -> LibraryParser.parseDirectory(text)
        LibraryFile.OVERRIDES -> LibraryParser.parseOverrides(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun accept(file: LibraryFile, parsed: Parsed<*>) {
        when (file) {
            LibraryFile.EMULATORS -> emulators = parsed.value as Map<String, Emulator>
            LibraryFile.SYSTEMS -> systems = parsed.value as Map<String, GameSystem>
            LibraryFile.SHORTCUTS -> shortcuts = parsed.value as Map<String, Shortcut>
            LibraryFile.DIRECTORY -> directory = parsed.value as List<DirectoryItem>
            LibraryFile.OVERRIDES -> overrides = parsed.value as Map<String, String>
        }
        fileErrors[file] = parsed.errors
    }

    private fun revalidate() {
        val validated = LibraryValidator.validate(emulators, systems, shortcuts, directory, overrides)
        library = validated.library
        crossErrors = validated.errors
        errors = LibraryFile.entries.flatMap { fileErrors[it].orEmpty() } + crossErrors
        for (error in errors) ErrorLog.write(error)
    }

    private fun writeTemplates(dir: File) {
        for (name in TEMPLATES) {
            val text = templateSource("$TEMPLATE_ASSET_DIR/$name")
            if (text == null) {
                ErrorLog.write("template $name missing from the app")
                continue
            }
            val target = File(dir, name)
            val current = try {
                if (target.exists()) target.readText() else null
            } catch (_: IOException) {
                null
            }
            if (current != text) writeAtomic(target, text, backup = false)
        }
    }

    companion object {
        const val TEMPLATE_ASSET_DIR = "templates"
        val TEMPLATES = listOf("emulators.template.json", "systems.template.json", "shortcuts.template.json")

        /** Writes through a temp file and a rename, keeping the old file as .bak when [backup]. */
        internal fun writeAtomic(target: File, text: String, backup: Boolean): String? = try {
            val dir = target.parentFile
            dir?.mkdirs()
            if (backup && target.exists()) target.copyTo(File(dir, "${target.name}.bak"), overwrite = true)
            val tmp = File(dir, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            if (tmp.renameTo(target)) null else {
                tmp.delete()
                "Could not save ${target.name}"
            }
        } catch (e: IOException) {
            ErrorLog.write("${target.name} write failed: ${e.message}")
            "Could not save ${target.name}"
        }
    }
}
