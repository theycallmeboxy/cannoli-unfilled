package dev.cannoli.scorza.config.library

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryConfigTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir: File get() = File(tmp.root, "Config")

    private fun asset(name: String): String? = try {
        ApplicationProvider.getApplicationContext<android.content.Context>().assets.open(name)
            .bufferedReader().use { it.readText() }
    } catch (_: IOException) {
        null
    }

    private fun config() = LibraryConfig({ dir }, ::asset)

    private fun write(file: LibraryFile, text: String) {
        File(dir.apply { mkdirs() }, file.fileName).writeText(text)
    }

    @Test fun `a first load creates empty files and the templates`() {
        val c = config()
        c.load()
        assertTrue(c.problems.toString(), c.problems.isEmpty())
        for (file in LibraryFile.entries) {
            assertEquals(file.emptyText, File(dir, file.fileName).readText())
        }
        for (name in LibraryConfig.TEMPLATES) {
            assertEquals(asset("templates/$name"), File(dir, name).readText())
        }
        assertTrue(c.library.systems.isEmpty())
        assertTrue(c.library.directory.isEmpty())
    }

    @Test fun `load rewrites a changed template but leaves real files alone`() {
        write(LibraryFile.SYSTEMS, """{ "GB": { "emulators": [] } }""")
        File(dir, "systems.template.json").writeText("edited")
        val c = config()
        c.load()
        assertEquals(asset("templates/systems.template.json"), File(dir, "systems.template.json").readText())
        assertEquals("""{ "GB": { "emulators": [] } }""", File(dir, "systems.json").readText())
        assertEquals(setOf("GB"), c.library.systems.keys)
    }

    @Test fun `the templates are never read as config`() {
        val c = config()
        c.load()
        assertTrue(c.library.emulators.isEmpty())
        assertTrue(c.library.systems.isEmpty())
        assertTrue(c.library.shortcuts.isEmpty())
    }

    @Test fun `files load and cross-check together`() {
        write(LibraryFile.EMULATORS, """{ "pizzaboy": { "package": "it.dbtecno.pizzaboygba" } }""")
        write(LibraryFile.SYSTEMS, """{ "GBA": { "emulators": [ "pizzaboy", "missing" ] } }""")
        write(LibraryFile.DIRECTORY, """{ "items": [ { "system": "GBA" }, { "system": "GB" } ] }""")
        write(LibraryFile.OVERRIDES, """{ "games": { "GBA/a.gba": "pizzaboy" } }""")
        val c = config()
        c.load()
        assertEquals(listOf("pizzaboy"), c.library.systems.getValue("GBA").emulators.map { it.emulator })
        assertEquals(listOf(DirectoryItem.SystemItem("GBA")), c.library.directory)
        assertEquals(mapOf("GBA/a.gba" to "pizzaboy"), c.library.overrides)
        assertEquals(c.problems.toString(), 2, c.problems.size)
        assertTrue(c.problems.toString(), c.problems.all { it.severity == Severity.WARNING })
    }

    @Test fun `each problem is logged once, with its severity, until it goes away`() {
        write(LibraryFile.EMULATORS, """{ "pizzaboy": { "package": "it.dbtecno.pizzaboygba" }, "bad": "x" }""")
        write(LibraryFile.SYSTEMS, """{ "SNES": { "emulators": [ "pizzaboy", "snes9xplus" ] } }""")
        val logged = mutableListOf<String>()
        val c = config().apply { log = { logged.add(it) } }
        c.load()
        assertEquals(
            listOf(
                "ERROR emulators.json: bad skipped: it should be an object, { ... }",
                "WARNING systems.json: SNES emulator \"snes9xplus\" isn't in emulators.json, so it's left out of SNES's choices",
            ),
            logged,
        )
        // Nothing changed on disk: nothing to read, nothing logged.
        assertFalse(c.reloadIfChanged())
        assertEquals(2, logged.size)

        // Another file changed: only what is new goes to the log.
        val directory = File(dir, "directory.json")
        directory.writeText("""{ "items": [ { "system": "SNES" }, { "system": "GB" } ] }""")
        directory.setLastModified(directory.lastModified() + 2000)
        assertTrue(c.reloadIfChanged())
        assertEquals(3, logged.size)
        assertTrue(logged.last(), logged.last().startsWith("WARNING directory.json: system \"GB\""))

        // The same text saved again, so a new mtime: no new lines.
        c.replaceText(LibraryFile.DIRECTORY, directory.readText())
        assertEquals(3, logged.size)

        // Fixed, then broken again: logged again.
        c.replaceText(LibraryFile.DIRECTORY, """{ "items": [ { "system": "SNES" } ] }""")
        c.replaceText(LibraryFile.DIRECTORY, """{ "items": [ { "system": "SNES" }, { "system": "GB" } ] }""")
        assertEquals(4, logged.size)
        assertEquals(3, c.problems.size)
        assertEquals(c.problems.distinct(), c.problems)
    }

    @Test fun `the same problem twice in one read is listed and logged once`() {
        val logged = mutableListOf<String>()
        val c = config().apply { log = { logged.add(it) } }
        c.load()
        // Two blank keys give the same message.
        c.replaceText(LibraryFile.EMULATORS, """{ " ": {}, "  ": {} }""")
        assertEquals(1, c.problems.size)
        assertEquals(1, c.problems(LibraryFile.EMULATORS).size)
        assertEquals(listOf("ERROR emulators.json: an entry with an empty name was skipped"), logged)
    }

    @Test fun `an edited file is picked up by reloadIfChanged`() {
        write(LibraryFile.SYSTEMS, """{ "GB": {} }""")
        val c = config()
        c.load()
        assertFalse(c.reloadIfChanged())
        val file = File(dir, "systems.json")
        file.writeText("""{ "NES": {} }""")
        file.setLastModified(file.lastModified() + 2000)
        assertTrue(c.reloadIfChanged())
        assertEquals(setOf("NES"), c.library.systems.keys)
        assertFalse(c.reloadIfChanged())
    }

    @Test fun `invalid JSON keeps the last good state and the file`() {
        write(LibraryFile.SYSTEMS, """{ "GB": {} }""")
        val c = config()
        c.load()
        val file = File(dir, "systems.json")
        file.writeText("{ broken")
        file.setLastModified(file.lastModified() + 2000)
        assertTrue(c.reloadIfChanged())
        assertEquals(setOf("GB"), c.library.systems.keys)
        assertEquals(1, c.problems.size)
        assertTrue(c.problems.single().message.startsWith("systems.json: not valid JSON"))
        assertEquals(Severity.ERROR, c.problems.single().severity)
        assertEquals("{ broken", file.readText())
    }

    @Test fun `replaceText writes with a backup and refuses bad JSON`() {
        write(LibraryFile.DIRECTORY, """{ "items": [] }""")
        val c = config()
        c.load()
        val (saved, problems) = c.replaceText(LibraryFile.DIRECTORY, """{ "items": [ { "app": "org.foo" }, { "system": "GB" } ] }""")
        assertTrue(saved)
        assertEquals(problems.toString(), 1, problems.size)
        assertEquals(listOf(DirectoryItem.AppItem("org.foo", null)), c.library.directory)
        assertEquals("""{ "items": [] }""", File(dir, "directory.json.bak").readText())
        assertEquals("""{ "items": [ { "app": "org.foo" }, { "system": "GB" } ] }""", c.currentText(LibraryFile.DIRECTORY))
        assertFalse(c.reloadIfChanged())

        val (refused, why) = c.replaceText(LibraryFile.DIRECTORY, "nope")
        assertFalse(refused)
        assertTrue(why.single().message.contains("not valid JSON"))
        assertTrue(c.currentText(LibraryFile.DIRECTORY).contains("org.foo"))
    }

    // ---- The shipped templates ----

    private fun template(name: String) = asset("templates/$name") ?: error("missing asset templates/$name")

    @Test fun `the shipped templates parse with no errors`() {
        val emulators = LibraryParser.parseEmulators(template("emulators.template.json"))
        val systems = LibraryParser.parseSystems(template("systems.template.json"))
        val shortcuts = LibraryParser.parseShortcuts(template("shortcuts.template.json"))
        assertTrue(emulators.problems.toString(), emulators.validJson && emulators.problems.isEmpty())
        assertTrue(systems.problems.toString(), systems.validJson && systems.problems.isEmpty())
        assertTrue(shortcuts.problems.toString(), shortcuts.validJson && shortcuts.problems.isEmpty())
        assertTrue("_about" !in emulators.value && "_about" !in systems.value && "_about" !in shortcuts.value)
        assertTrue("retroarch" in emulators.value)
        assertTrue(shortcuts.value.size in 3..4)

        val platforms = JSONObject(asset("platforms.json")!!)
        assertEquals(platforms.keys().asSequence().filter { it != "apps" }.toSet(), systems.value.keys)
    }

    @Test fun `every system template names emulators from the emulator template`() {
        val emulators = LibraryParser.parseEmulators(template("emulators.template.json")).value
        val systems = LibraryParser.parseSystems(template("systems.template.json")).value
        val missing = systems.values.flatMap { s -> s.emulators.filter { it.emulator !in emulators }.map { "${s.id}: ${it.emulator}" } }
        assertTrue(missing.toString(), missing.isEmpty())
        val v = LibraryValidator.validate(emulators, systems, emptyMap(), emptyList(), emptyMap())
        assertTrue(v.problems.toString(), v.problems.isEmpty())
        assertEquals("mgba_libretro", systems.getValue("GBA").emulators.first().params["core"])
    }

    @Test fun `the system template matches RomM slugs and lists file types`() {
        val emulators = LibraryParser.parseEmulators(template("emulators.template.json")).value
        val systems = LibraryParser.parseSystems(template("systems.template.json")).value
        val library = LibraryValidator.validate(emulators, systems, emptyMap(), emptyList(), emptyMap()).library
        val config = PlatformConfig { library }
        val expected = mapOf(
            "snes" to "SNES", "sfc" to "SNES", "genesis" to "MD", "psx" to "PS", "ms" to "SMS",
            "arcade" to "MAME", "fbneo" to "FBN", "switch" to "NSW",
        )
        for ((slug, tag) in expected) assertEquals(slug, tag, config.tagForRommSlug(slug))
        assertEquals(setOf("7z", "bs", "fig", "sfc", "smc", "st", "swc", "zip"), systems.getValue("SNES").extensions)
        assertTrue(systems.getValue("MAME").extensions!!.contains("zip"))
        assertTrue("RetroArch opens archives for any core", systems.getValue("GBA").extensions!!.contains("zip"))
        assertNull(systems.getValue("PC").extensions)
    }
}
