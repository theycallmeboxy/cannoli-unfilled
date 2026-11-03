package dev.cannoli.scorza.config.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryValidatorTest {

    private val emulators = LibraryParser.parseEmulators(
        """{ "retroarch": { "package": "com.retroarch.aarch64", "extras": [
               { "key": "ROM", "kind": "string", "value": "{rom_path}" },
               { "key": "LIBRETRO", "kind": "string", "value": "/data/data/{package}/cores/{core}_android.so" },
               { "key": "CFG", "kind": "string_array", "values": [ "{config}", "{rom_extension}" ] } ] },
             "pizzaboy": { "package": "it.dbtecno.pizzaboygba" } }"""
    ).value

    private val systems = LibraryParser.parseSystems(
        """{ "GBA": { "emulators": [
               { "emulator": "retroarch", "core": "mgba_libretro", "config": "a.cfg" },
               { "emulator": "retroarch", "core": "vba_next_libretro", "config": "b.cfg", "name": "VBA" },
               { "emulator": "retroarch", "config": "c.cfg", "name": "NoCore" },
               "pizzaboy", "missing" ] },
             "GB": { "roms": "Handheld/GB", "emulators": [ "pizzaboy" ] } }"""
    ).value

    private val shortcuts = LibraryParser.parseShortcuts("""{ "wifi": { "package": "com.android.settings" } }""").value

    private val directory = LibraryParser.parseDirectory(
        """{ "items": [ { "folder": "Nintendo", "items": [ { "system": "GBA" }, { "system": "NES" }, { "shortcut": "nope" } ] },
                        { "shortcut": "wifi" }, { "app": "org.foo" } ] }"""
    ).value

    private fun validate(overrides: Map<String, String> = emptyMap()) =
        LibraryValidator.validate(emulators, systems, shortcuts, directory, overrides)

    @Test fun `dangling emulators and unfilled placeholders drop the ref`() {
        val v = validate()
        assertEquals(listOf("retroarch", "VBA", "pizzaboy"), v.library.systems.getValue("GBA").emulators.map { it.choiceName })
        assertTrue(v.errors.toString(), v.errors.any { "GBA.emulators \"missing\"" in it && "no emulator named" in it })
        assertTrue(v.errors.toString(), v.errors.any { "\"NoCore\"" in it && "{core}" in it && "\"core\"" in it })
    }

    @Test fun `a RomM slug two systems claim stays with the first`() {
        val claims = LibraryParser.parseSystems(
            """{ "SNES": { "romm": [ "snes", "sfc" ] }, "SFC": { "romm": [ "SFC", "super-famicom" ] } }"""
        ).value
        val v = LibraryValidator.validate(emptyMap(), claims, emptyMap(), emptyList(), emptyMap())
        assertEquals(listOf("snes", "sfc"), v.library.systems.getValue("SNES").romm)
        assertEquals(listOf("super-famicom"), v.library.systems.getValue("SFC").romm)
        assertEquals(v.errors.toString(), 1, v.errors.size)
        assertTrue(v.errors.single(), "SFC.romm \"sfc\"" in v.errors.single() && "SNES" in v.errors.single())
    }

    @Test fun `dangling directory systems and shortcuts are dropped`() {
        val v = validate()
        assertEquals(
            listOf(
                DirectoryItem.Folder("Nintendo", listOf(DirectoryItem.SystemItem("GBA"))),
                DirectoryItem.ShortcutItem("wifi"),
                DirectoryItem.AppItem("org.foo", null),
            ),
            v.library.directory,
        )
        assertTrue(v.errors.any { "system \"NES\" in Nintendo skipped" in it })
        assertTrue(v.errors.any { "shortcut \"nope\" in Nintendo skipped" in it })
        assertEquals(setOf("GBA"), v.library.placedSystemIds())
        assertEquals(4, v.errors.size)
    }

    @Test fun `overrides must name a choice of the game's system`() {
        val v = validate(
            mapOf(
                "GBA/a.gba" to "VBA",
                "GBA/b.gba" to "pizzaboy",
                "GBA/c.gba" to "NoCore",
                "Handheld/GB/d.gb" to "pizzaboy",
                "GB/e.gb" to "pizzaboy",
                "GBA/f.gba" to "retroarch",
            )
        )
        assertEquals(
            mapOf("GBA/a.gba" to "VBA", "GBA/b.gba" to "pizzaboy", "Handheld/GB/d.gb" to "pizzaboy", "GBA/f.gba" to "retroarch"),
            v.library.overrides,
        )
        assertTrue(v.errors.any { "\"GBA/c.gba\" skipped" in it })
        assertTrue(v.errors.any { "\"GB/e.gb\" skipped" in it && "ROM folder" in it })
    }

    @Test fun `a launch plan fills params and leaves built-ins for the launcher`() {
        val lib = validate(mapOf("GBA/a.gba" to "VBA")).library
        val plan = lib.launchFor("GBA", "GBA/a.gba")!!
        assertEquals("VBA", plan.ref.choiceName)
        assertEquals(mapOf("core" to "vba_next_libretro", "config" to "b.cfg"), plan.vars)
        assertTrue(plan.unfilled.isEmpty())
        assertTrue("rom_path" in plan.emulator.placeholders)

        val default = lib.launchFor("GBA", "GBA/other.gba")!!
        assertEquals("retroarch", default.ref.choiceName)
        assertEquals("mgba_libretro", default.vars["core"])

        assertNull(lib.launchFor("NES", "NES/x.nes"))
    }

    @Test fun `an override that no longer matches falls back to the default`() {
        val lib = validate().library.copy(overrides = mapOf("GBA/a.gba" to "gone"))
        assertEquals("retroarch", lib.launchFor("GBA", "GBA/a.gba")!!.ref.choiceName)
    }

    @Test fun `unfilled reports placeholders nothing gives`() {
        val ra = emulators.getValue("retroarch")
        assertEquals(setOf("core", "config"), Placeholders.unfilled(ra, emptyMap()))
        assertEquals(setOf("config"), Placeholders.unfilled(ra, mapOf("core" to "x")))
        assertEquals(
            mapOf("core" to "x"),
            Placeholders.varsFor(EmulatorRef("retroarch", mapOf("core" to "x", "rom_path" to "/evil"))),
        )
        assertEquals(setOf("a", "b_2"), Placeholders.find("{a}/{b_2}/{a}/{not valid}"))
    }
}
