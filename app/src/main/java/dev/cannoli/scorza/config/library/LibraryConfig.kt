package dev.cannoli.scorza.config.library

import dev.cannoli.scorza.util.ErrorLog
import org.json.JSONException
import org.json.JSONObject
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
 * Bad entries are skipped and listed in [problems], each an error or a warning. Each goes to
 * Logs/error.log once, when it first shows up: a reload that finds the same problem again doesn't
 * write it again.
 */
class LibraryConfig(
    private val dirProvider: () -> File,
    private val templateSource: (String) -> String?,
) {
    // Read from the UI, the scanner and the launcher while a reload swaps them in.
    @Volatile var library: Library = Library()
        private set

    /** Every problem from the last read, without repeats: each file's own, then the cross-file ones. */
    @Volatile var problems: List<ConfigProblem> = emptyList()
        private set

    /** Where each new problem's [ConfigProblem.logLine] goes; tests swap it. */
    internal var log: (String) -> Unit = ErrorLog::write

    // The problems already written to the log, so a reload that finds them again stays quiet.
    private var logged: Set<ConfigProblem> = emptySet()

    private var emulators: Map<String, Emulator> = emptyMap()
    private var systems: Map<String, GameSystem> = emptyMap()
    private var shortcuts: Map<String, Shortcut> = emptyMap()
    private var directory: List<DirectoryItem> = emptyList()
    private var overrides: Map<String, String> = emptyMap()

    private val fileProblems = LinkedHashMap<LibraryFile, List<ConfigProblem>>()
    private var crossProblems: List<ConfigProblem> = emptyList()
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

    /** The problems from the last read of [file], then the cross-file ones, as [replaceText] returns them. */
    @Synchronized
    fun problems(file: LibraryFile): List<ConfigProblem> = (fileProblems[file].orEmpty() + crossProblems).distinct()

    /**
     * Replaces [file] with [text] once it parses. Returns whether it was written and the problems
     * found, this file's and the cross-file ones; text that is not JSON is refused and the file is
     * left as it was.
     */
    @Synchronized
    fun replaceText(file: LibraryFile, text: String): Pair<Boolean, List<ConfigProblem>> {
        val parsed = parse(file, text)
        if (!parsed.validJson) return false to parsed.problems
        val failure = writeAtomic(fileFor(file), text, backup = true)
        if (failure != null) return false to listOf(ConfigProblem.error(failure))
        loadedMtimes[file] = mtimeOf(file)
        accept(file, parsed)
        revalidate()
        return true to problems(file)
    }

    /**
     * Changes [file] through its JSON: [change] edits the document in place and returns null, or
     * returns why it refused and nothing is written. Working on the JSON rather than the parsed
     * entries keeps comment keys and entries this build skipped. Returns null once written, else
     * the reason it was not.
     */
    @Synchronized
    fun edit(file: LibraryFile, change: (JSONObject) -> String?): String? {
        val json = try {
            JSONObject(currentText(file))
        } catch (_: JSONException) {
            return "${file.fileName} is not valid JSON, so it was left alone"
        } catch (e: IOException) {
            return "${file.fileName}: unreadable (${e.message})"
        }
        change(json)?.let { return it }
        val (written, problems) = replaceText(file, json.toString(2) + "\n")
        return if (written) null else problems.firstOrNull()?.message ?: "Could not save ${file.fileName}"
    }

    /** The text of the bundled template [name], such as systems.template.json. */
    fun templateText(name: String): String? = templateSource("$TEMPLATE_ASSET_DIR/$name")

    private fun fileFor(file: LibraryFile) = File(dirProvider(), file.fileName)

    private fun mtimeOf(file: LibraryFile): Long = fileFor(file).let { if (it.exists()) it.lastModified() else 0L }

    private fun read(file: LibraryFile) {
        val target = fileFor(file)
        loadedMtimes[file] = mtimeOf(file)
        val text = try {
            if (target.exists()) target.readText() else file.emptyText
        } catch (e: IOException) {
            fileProblems[file] = listOf(
                ConfigProblem.error("${file.fileName}: couldn't be read (${e.message}); the last version that loaded is still in use")
            )
            return
        }
        val parsed = parse(file, text)
        if (parsed.validJson) accept(file, parsed) else fileProblems[file] = parsed.problems
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
        fileProblems[file] = parsed.problems
    }

    private fun revalidate() {
        val validated = LibraryValidator.validate(emulators, systems, shortcuts, directory, overrides)
        library = validated.library
        crossProblems = validated.problems
        val all = (LibraryFile.entries.flatMap { fileProblems[it].orEmpty() } + crossProblems).distinct()
        problems = all
        for (problem in all) if (problem !in logged) log(problem.logLine)
        logged = all.toSet()
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
