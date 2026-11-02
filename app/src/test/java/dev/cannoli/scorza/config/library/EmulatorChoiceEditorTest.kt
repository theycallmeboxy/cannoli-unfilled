package dev.cannoli.scorza.config.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** "Launch With" writes overrides.json and "Default Emulator" reorders systems.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmulatorChoiceEditorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir: File get() = File(tmp.root, "Config")

    private val emulators = """
        {
          "retroarch": { "name": "RetroArch", "package": "com.retroarch.aarch64",
            "extras": [ { "key": "LIBRETRO", "kind": "string", "value": "{core}" } ] },
          "pizzaboy": { "name": "Pizza Boy", "package": "it.dbtecno.pizzaboygba" }
        }
    """.trimIndent()

    private val systems = """
        {
          "_about": "kept",
          "GBA": { "name": "Game Boy Advance", "_note": "mine",
            "emulators": [
              { "emulator": "retroarch", "core": "mgba_libretro", "_note": "fast" },
              { "emulator": "retroarch", "core": "vba_next_libretro", "name": "VBA Next" },
              "pizzaboy"
            ] }
        }
    """.trimIndent()

    private fun config(overrides: String = """{ "_about": "device picks", "games": {} }"""): LibraryConfig {
        dir.mkdirs()
        File(dir, LibraryParser.EMULATORS).writeText(emulators)
        File(dir, LibraryParser.SYSTEMS).writeText(systems)
        File(dir, LibraryParser.OVERRIDES).writeText(overrides)
        return LibraryConfig({ dir }) { null }.also { it.load() }
    }

    private fun reread(): Library = LibraryConfig({ dir }) { null }.also { it.load() }.library

    private fun fileJson(name: String) = JSONObject(File(dir, name).readText())

    private val gba get() = reread().systems.getValue("GBA")

    @Test fun `a choice is written to overrides_json and launches from then on`() {
        val config = config()
        val edit = EmulatorChoiceEditor(config)

        assertNull(edit.setGameChoice(config.library.systems.getValue("GBA"), "GBA/Metroid.gba", "pizzaboy"))

        assertEquals("pizzaboy", fileJson(LibraryParser.OVERRIDES).getJSONObject("games").getString("GBA/Metroid.gba"))
        assertEquals("device picks", fileJson(LibraryParser.OVERRIDES).getString("_about"))
        // The library in memory changes with the file, so no reload is needed to launch with it.
        assertEquals("pizzaboy", config.library.launchFor("GBA", "GBA/Metroid.gba")!!.ref.choiceName)
        assertEquals("pizzaboy", reread().launchFor("GBA", "GBA/Metroid.gba")!!.ref.choiceName)
        assertTrue(File(dir, "${LibraryParser.OVERRIDES}.bak").exists())
    }

    @Test fun `a named choice is saved by its name`() {
        val config = config()
        assertNull(EmulatorChoiceEditor(config).setGameChoice(config.library.systems.getValue("GBA"), "GBA/Zelda.gba", "VBA Next"))
        val plan = config.library.launchFor("GBA", "GBA/Zelda.gba")!!
        assertEquals(mapOf("core" to "vba_next_libretro"), plan.vars)
    }

    @Test fun `picking the system's default removes the game's override`() {
        val config = config("""{ "games": { "GBA/Metroid.gba": "pizzaboy", "GBA/Other.gba": "pizzaboy" } }""")

        assertNull(EmulatorChoiceEditor(config).setGameChoice(config.library.systems.getValue("GBA"), "GBA/Metroid.gba", "retroarch"))

        val games = fileJson(LibraryParser.OVERRIDES).getJSONObject("games")
        assertFalse(games.has("GBA/Metroid.gba"))
        assertEquals("pizzaboy", games.getString("GBA/Other.gba"))
        assertEquals("retroarch", config.library.launchFor("GBA", "GBA/Metroid.gba")!!.ref.choiceName)
    }

    @Test fun `picking the default for a game without an override writes nothing`() {
        val config = config()
        val before = File(dir, LibraryParser.OVERRIDES).readText()
        assertNull(EmulatorChoiceEditor(config).setGameChoice(config.library.systems.getValue("GBA"), "GBA/Metroid.gba", "retroarch"))
        assertEquals(before, File(dir, LibraryParser.OVERRIDES).readText())
    }

    @Test fun `a choice the system doesn't list is refused`() {
        val config = config()
        assertNotNull(EmulatorChoiceEditor(config).setGameChoice(config.library.systems.getValue("GBA"), "GBA/Metroid.gba", "nope"))
        assertTrue(reread().overrides.isEmpty())
    }

    @Test fun `overrides_json without games gets the object`() {
        val config = config("{}")
        assertNull(EmulatorChoiceEditor(config).setGameChoice(config.library.systems.getValue("GBA"), "GBA/Metroid.gba", "pizzaboy"))
        assertEquals(mapOf("GBA/Metroid.gba" to "pizzaboy"), reread().overrides)
    }

    @Test fun `default emulator moves the entry to the top unchanged`() {
        val config = config()
        val original = fileJson(LibraryParser.SYSTEMS).getJSONObject("GBA").getJSONArray("emulators")
        val vba = original.getJSONObject(1).toString()

        assertNull(EmulatorChoiceEditor(config).makeDefault("GBA", "VBA Next"))

        assertEquals(listOf("VBA Next", "retroarch", "pizzaboy"), gba.emulators.map { it.choiceName })
        val written = fileJson(LibraryParser.SYSTEMS)
        val list = written.getJSONObject("GBA").getJSONArray("emulators")
        assertEquals(vba, list.getJSONObject(0).toString())
        assertEquals("fast", list.getJSONObject(1).getString("_note"))
        assertEquals("pizzaboy", list.getString(2))
        assertEquals("kept", written.getString("_about"))
        assertEquals("mine", written.getJSONObject("GBA").getString("_note"))
        assertEquals("VBA Next", config.library.systems.getValue("GBA").emulators.first().choiceName)
    }

    @Test fun `a plain name moves too, and the old default follows it`() {
        val config = config()
        assertNull(EmulatorChoiceEditor(config).makeDefault("GBA", "pizzaboy"))
        assertEquals(listOf("pizzaboy", "retroarch", "VBA Next"), gba.emulators.map { it.choiceName })
        // A game with no override now launches with the new default.
        assertEquals("pizzaboy", config.library.launchFor("GBA", "GBA/Metroid.gba")!!.ref.choiceName)
    }

    @Test fun `default emulator refuses an unknown system or choice`() {
        val config = config()
        val before = File(dir, LibraryParser.SYSTEMS).readText()
        val edit = EmulatorChoiceEditor(config)
        assertNotNull(edit.makeDefault("SNES", "retroarch"))
        assertNotNull(edit.makeDefault("GBA", "nope"))
        assertNull("already first", edit.makeDefault("GBA", "retroarch"))
        assertEquals(before, File(dir, LibraryParser.SYSTEMS).readText())
    }

    @Test fun `the pickers' label is the choice's name, else the emulator's with its core`() {
        val library = config().library
        assertEquals(
            listOf("RetroArch · mgba_libretro", "VBA Next", "Pizza Boy"),
            library.systems.getValue("GBA").emulators.map(library::choiceLabel),
        )
    }
}
