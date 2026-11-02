package dev.cannoli.scorza.config

import dev.cannoli.scorza.config.library.Library
import dev.cannoli.scorza.config.library.LibraryParser
import dev.cannoli.scorza.config.library.LibraryValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PlatformConfig answers every platform question from systems.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigSystemsTest {

    private val emulators = """
        {
          "retroarch": { "name": "RetroArch", "package": "com.retroarch.aarch64",
            "extras": [ { "key": "LIBRETRO", "kind": "string", "value": "{core}" } ] },
          "pizzaboy": { "package": "it.dbtecno.pizzaboygba" }
        }
    """.trimIndent()

    private val systems = """
        {
          "GBA": { "name": "Game Boy Advance", "roms": "Game Boy Advance", "extensions": ["gba", "zip"],
            "emulators": [ { "emulator": "retroarch", "core": "mgba_libretro" }, "pizzaboy" ] },
          "mame": { "name": "Arcade", "arcade": true, "emulators": [ { "emulator": "retroarch", "core": "mame_libretro" } ] },
          "NES": { }
        }
    """.trimIndent()

    private fun library(overrides: String = """{ "games": {} }"""): Library =
        LibraryValidator.validate(
            LibraryParser.parseEmulators(emulators).value,
            LibraryParser.parseSystems(systems).value,
            emptyMap(),
            emptyList(),
            LibraryParser.parseOverrides(overrides).value,
        ).library

    private fun config(library: Library = library()) = PlatformConfig { library }

    @Test fun `known tags are the system IDs in upper case`() {
        val c = config()
        assertEquals(setOf("GBA", "MAME", "NES"), c.getAllTags())
        assertTrue(c.isKnownTag("gba"))
        assertTrue(c.isKnownTag("MAME"))
        assertFalse(c.isKnownTag("SNES"))
    }

    @Test fun `an empty systems file knows no platforms`() {
        val c = config(Library())
        assertTrue(c.getAllTags().isEmpty())
        assertFalse(c.isKnownTag("GBA"))
        assertEquals("GBA", c.getDisplayName("GBA"))
    }

    @Test fun `names arcade and file types come from the system`() {
        val c = config()
        assertEquals("Game Boy Advance", c.getDisplayName("GBA"))
        assertEquals("NES", c.getDisplayName("NES"))
        assertTrue(c.isArcade("MAME"))
        assertFalse(c.isArcade("GBA"))
        assertEquals(setOf("gba", "zip"), c.extensionsFor("GBA"))
        assertNull(c.extensionsFor("NES"))
    }

    @Test fun `the ROM folder is the system's roms, else its ID`() {
        val c = config()
        assertEquals("Game Boy Advance", c.folderFor("GBA"))
        assertEquals("mame", c.folderFor("MAME"))
        assertEquals("SNES", c.folderFor("SNES"))
        assertEquals(listOf("Game Boy Advance", "mame", "NES"), c.romFolders())
    }

    @Test fun `a folder maps back to its system without regard to case`() {
        val c = config()
        assertEquals("GBA", c.tagForFolder("game boy advance"))
        assertEquals("MAME", c.tagForFolder("MAME"))
        assertNull("the ID is not the folder when roms says otherwise", c.tagForFolder("GBA"))
        assertNull(c.tagForFolder("SNES"))
    }

    @Test fun `a game launches with its system's first emulator and its values`() {
        val plan = config().launchFor("gba", "Game Boy Advance/Metroid.gba")!!
        assertEquals("retroarch", plan.emulator.name)
        assertEquals(mapOf("core" to "mgba_libretro"), plan.vars)
    }

    @Test fun `an override picks another of the system's emulators`() {
        val lib = library("""{ "games": { "Game Boy Advance/Metroid.gba": "pizzaboy" } }""")
        val c = config(lib)
        assertEquals("pizzaboy", c.launchFor("GBA", "Game Boy Advance/Metroid.gba")!!.emulator.name)
        assertEquals("retroarch", c.launchFor("GBA", "Game Boy Advance/Zelda.gba")!!.emulator.name)
    }

    @Test fun `a system with no emulator or no system at all has no launch`() {
        val c = config()
        assertNull(c.launchFor("NES", "NES/Mario.nes"))
        assertNull(c.launchFor("SNES", "SNES/Mario.sfc"))
    }

    @Test fun `the platform row carries the system name`() {
        val p = config().resolvePlatform("GBA", 3)
        assertEquals("Game Boy Advance", p.displayName)
        assertEquals(3, p.gameCount)
    }

    @Test fun `a reload is picked up`() {
        var current = library()
        val c = PlatformConfig { current }
        val before = c.systemFor("GBA")
        assertSame(before, c.systemFor("GBA"))
        current = Library()
        assertFalse(c.isKnownTag("GBA"))
    }
}
