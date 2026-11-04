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

    private val Validated.messages get() = problems.map { it.message }

    private fun Validated.severityOf(part: String) = problems.single { part in it.message }.severity

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
        assertTrue(
            v.messages.toString(),
            "systems.json: GBA emulator \"missing\" isn't in emulators.json, so it's left out of GBA's choices" in v.messages,
        )
        assertTrue(v.messages.toString(), v.messages.any { "\"NoCore\"" in it && "{core}" in it && "\"core\"" in it })
        // A missing emulator only drops that choice; a choice missing a value its emulator needs is malformed.
        assertEquals(Severity.WARNING, v.severityOf("\"missing\""))
        assertEquals(Severity.ERROR, v.severityOf("\"NoCore\""))
    }

    @Test fun `a RomM slug two systems claim stays with the first`() {
        val claims = LibraryParser.parseSystems(
            """{ "SNES": { "romm": [ "snes", "sfc" ] }, "SFC": { "romm": [ "SFC", "super-famicom" ] } }"""
        ).value
        val v = LibraryValidator.validate(emptyMap(), claims, emptyMap(), emptyList(), emptyMap())
        assertEquals(listOf("snes", "sfc"), v.library.systems.getValue("SNES").romm)
        assertEquals(listOf("super-famicom"), v.library.systems.getValue("SFC").romm)
        assertEquals(v.messages.toString(), 1, v.messages.size)
        assertTrue(v.messages.single(), "SFC.romm \"sfc\"" in v.messages.single() && "SNES" in v.messages.single())
        assertEquals(Severity.WARNING, v.problems.single().severity)
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
        assertTrue(v.messages.any { "system \"NES\" in Nintendo isn't in systems.json" in it })
        assertTrue(v.messages.any { "shortcut \"nope\" in Nintendo isn't in shortcuts.json" in it })
        assertEquals(Severity.WARNING, v.severityOf("system \"NES\""))
        assertEquals(Severity.WARNING, v.severityOf("shortcut \"nope\""))
        assertEquals(setOf("GBA"), v.library.placedSystemIds())
        assertEquals(4, v.messages.size)
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
        assertTrue(v.messages.any { "\"GBA/c.gba\" is ignored" in it && "isn't one of GBA's emulators" in it })
        assertTrue(v.messages.any { "\"GB/e.gb\" is ignored" in it && "ROM folder" in it })
        assertTrue(v.problems.toString(), v.problems.all { it.severity == Severity.WARNING || "NoCore" in it.message })
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
