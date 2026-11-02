package dev.cannoli.scorza.config.library

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(c.errors.toString(), c.errors.isEmpty())
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
        assertEquals(c.errors.toString(), 2, c.errors.size)
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
        assertEquals(1, c.errors.size)
        assertTrue(c.errors.single().startsWith("systems.json: not valid JSON"))
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
        assertTrue(why.single().contains("not valid JSON"))
        assertTrue(c.currentText(LibraryFile.DIRECTORY).contains("org.foo"))
    }

    // ---- The shipped templates ----

    private fun template(name: String) = asset("templates/$name") ?: error("missing asset templates/$name")

    @Test fun `the shipped templates parse with no errors`() {
        val emulators = LibraryParser.parseEmulators(template("emulators.template.json"))
        val systems = LibraryParser.parseSystems(template("systems.template.json"))
        val shortcuts = LibraryParser.parseShortcuts(template("shortcuts.template.json"))
        assertTrue(emulators.errors.toString(), emulators.validJson && emulators.errors.isEmpty())
        assertTrue(systems.errors.toString(), systems.validJson && systems.errors.isEmpty())
        assertTrue(shortcuts.errors.toString(), shortcuts.validJson && shortcuts.errors.isEmpty())
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
        assertTrue(v.errors.toString(), v.errors.isEmpty())
        assertEquals("mgba_libretro", systems.getValue("GBA").emulators.first().params["core"])
    }
}
