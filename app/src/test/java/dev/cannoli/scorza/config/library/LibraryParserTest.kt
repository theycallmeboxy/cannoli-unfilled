package dev.cannoli.scorza.config.library

import android.content.Intent
import dev.cannoli.scorza.config.DataBinding
import dev.cannoli.scorza.config.ExtraValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryParserTest {

    private val Parsed<*>.messages get() = problems.map { it.message }

    private fun Parsed<*>.severityOf(part: String) = problems.single { part in it.message }.severity

    private val retroarch = """
        "retroarch": {
          "_note": "a comment",
          "package": "com.retroarch.aarch64",
          "activity": "com.retroarch.browser.retroactivity.RetroActivityFuture",
          "action": "android.intent.action.MAIN",
          "extras": [
            { "key": "ROM", "kind": "string", "value": "{rom_path}" },
            { "key": "LIBRETRO", "kind": "string", "value": "/data/data/{package}/cores/{core}_android.so" }
          ]
        }
    """.trimIndent()

    // ---- emulators.json ----

    @Test fun `emulators parse in file order with names and intents`() {
        val p = LibraryParser.parseEmulators(
            """{ "_about": ["x"], $retroarch, "pizzaboy": { "name": "Pizza Boy GBA", "package": "it.dbtecno.pizzaboygba",
                 "extras": [ { "key": "rom_uri", "kind": "uri_string" } ] } }"""
        )
        assertTrue(p.messages.toString(), p.messages.isEmpty())
        assertTrue(p.validJson)
        assertEquals(listOf("retroarch", "pizzaboy"), p.value.keys.toList())
        val ra = p.value.getValue("retroarch")
        assertNull(ra.displayName)
        assertEquals("retroarch", ra.label)
        assertEquals(Intent.ACTION_MAIN, ra.intent.action)
        assertEquals(setOf("rom_path", "package", "core"), ra.placeholders)
        val pb = p.value.getValue("pizzaboy")
        assertEquals("Pizza Boy GBA", pb.displayName)
        assertEquals(ExtraValueKind.FILE_URI_STRING, pb.intent.extras.single().kind)
    }

    @Test fun `a bad emulator is skipped and the rest kept`() {
        val p = LibraryParser.parseEmulators(
            """{ "nopkg": { "name": "x" }, "notobj": "com.foo", "badkind": { "package": "a", "data": { "kind": "zzz" } },
                 "good": { "package": "com.good" } }"""
        )
        assertEquals(listOf("good"), p.value.keys.toList())
        assertEquals(3, p.messages.size)
        assertTrue(p.messages.all { it.startsWith("emulators.json: ") && "skipped" in it })
        // A whole emulator not loaded is an error.
        assertTrue(p.problems.all { it.severity == Severity.ERROR })
        assertTrue(p.problems.all { it.logLine.startsWith("ERROR emulators.json: ") })
    }

    @Test fun `invalid JSON gives an empty result and one error`() {
        for (text in listOf("{ nope", "", "[]")) {
            val p = LibraryParser.parseEmulators(text)
            assertFalse(p.validJson)
            assertTrue(p.value.isEmpty())
            assertEquals(1, p.messages.size)
            assertTrue(p.messages.single().contains("not valid JSON"))
            assertTrue(p.messages.single().contains("the last version that loaded is still in use"))
            assertEquals(Severity.ERROR, p.problems.single().severity)
        }
    }

    // ---- systems.json ----

    @Test fun `systems parse with defaults, params and labels`() {
        val p = LibraryParser.parseSystems(
            """{ "_about": "x",
                 "GBA": { "_note": "n", "name": "Game Boy Advance", "extensions": [".GBA", "zip"],
                          "emulators": [ { "emulator": "retroarch", "core": "mgba_libretro", "_note": "skip me" },
                                         { "emulator": "retroarch", "core": "vba_next_libretro", "name": "VBA" },
                                         "pizzaboy" ] },
                 "MAME": { "arcade": true, "roms": "Arcade/MAME/" } }"""
        )
        assertTrue(p.messages.toString(), p.messages.isEmpty())
        val gba = p.value.getValue("GBA")
        assertEquals("Game Boy Advance", gba.name)
        assertEquals("GBA", gba.roms)
        assertEquals(setOf("gba", "zip"), gba.extensions)
        assertFalse(gba.arcade)
        assertEquals(
            listOf(
                EmulatorRef("retroarch", mapOf("core" to "mgba_libretro")),
                EmulatorRef("retroarch", mapOf("core" to "vba_next_libretro"), "VBA"),
                EmulatorRef("pizzaboy"),
            ),
            gba.emulators,
        )
        assertEquals(listOf("retroarch", "VBA", "pizzaboy"), gba.emulators.map { it.choiceName })
        val mame = p.value.getValue("MAME")
        assertEquals("MAME", mame.name)
        assertEquals("Arcade/MAME", mame.roms)
        assertNull(mame.extensions)
        assertTrue(mame.arcade)
        assertTrue(mame.emulators.isEmpty())
    }

    @Test fun `bad emulator refs are skipped individually`() {
        val p = LibraryParser.parseSystems(
            """{ "GB": { "emulators": [ "a", 5, { "core": "x" }, "a", { "emulator": "b", "rom_path": "/x", "core": "c" } ] },
                 "BAD": { "emulators": "a" }, "EMPTY": { "extensions": [] } }"""
        )
        val gb = p.value.getValue("GB")
        assertEquals(listOf(EmulatorRef("a"), EmulatorRef("b", mapOf("core" to "c"))), gb.emulators)
        assertFalse("BAD" in p.value)
        assertNull(p.value.getValue("EMPTY").extensions)
        // 5, missing emulator, duplicate "a", built-in rom_path, BAD, empty extensions
        assertEquals(p.messages.toString(), 6, p.messages.size)
        assertTrue(p.messages.any { "GB.emulators[3]" in it && "already one of GB's choices" in it })
        assertTrue(p.messages.any { "\"rom_path\" ignored" in it })
        // Malformed: the choice 5, the choice with no emulator, and BAD as a whole.
        assertEquals(Severity.ERROR, p.severityOf("GB.emulators[1]"))
        assertEquals(Severity.ERROR, p.severityOf("GB.emulators[2]"))
        assertEquals(Severity.ERROR, p.severityOf("BAD skipped"))
        // Harmless: a repeat, an ignored key, an empty extensions list.
        assertEquals(Severity.WARNING, p.severityOf("GB.emulators[3]"))
        assertEquals(Severity.WARNING, p.severityOf("\"rom_path\" ignored"))
        assertEquals(Severity.WARNING, p.severityOf("EMPTY.extensions"))
    }

    @Test fun `romm takes one slug or a list, lower cased, and defaults to none`() {
        val p = LibraryParser.parseSystems(
            """{ "GBA": { "romm": " GBA " },
                 "SNES": { "romm": [ "snes", "SFC", "snes", "" ] },
                 "NES": { },
                 "BAD": { "romm": 5 }, "BAD2": { "romm": [ "x", 3 ] } }"""
        )
        assertEquals(listOf("gba"), p.value.getValue("GBA").romm)
        assertEquals(listOf("snes", "sfc"), p.value.getValue("SNES").romm)
        assertTrue(p.value.getValue("NES").romm.isEmpty())
        assertFalse("BAD" in p.value || "BAD2" in p.value)
        assertEquals(p.messages.toString(), 2, p.messages.size)
        assertTrue(p.messages.toString(), p.messages.all { "\"romm\"" in it })
    }

    // ---- shortcuts.json ----

    @Test fun `shortcuts default to MAIN and no mime type`() {
        val p = LibraryParser.parseShortcuts(
            """{ "wifi": { "package": "com.android.settings", "action": "android.settings.WIFI_SETTINGS" },
                 "plain": { "name": "Plain", "package": "org.foo" },
                 "kodi": { "name": "Movies", "package": "org.xbmc.kodi", "action": "android.intent.action.VIEW",
                           "data": { "kind": "custom_scheme", "scheme": "plugin", "authority": "plugin.video.x" } } }"""
        )
        assertTrue(p.messages.toString(), p.messages.isEmpty())
        val wifi = p.value.getValue("wifi")
        assertEquals("wifi", wifi.displayName)
        assertEquals("android.settings.WIFI_SETTINGS", wifi.intent.action)
        assertNull(wifi.intent.mimeType)
        assertEquals(Intent.ACTION_MAIN, p.value.getValue("plain").intent.action)
        assertEquals(DataBinding.CustomScheme("plugin", "plugin.video.x"), p.value.getValue("kodi").intent.data)
    }

    @Test fun `shortcuts that need a game are skipped`() {
        val p = LibraryParser.parseShortcuts(
            """{ "a": { "package": "x", "data": { "kind": "file_provider" } },
                 "b": { "package": "x", "extras": [ { "key": "k", "kind": "path" } ] },
                 "c": { "package": "x", "extras": [ { "key": "k", "kind": "string", "value": "{rom_id}" } ] },
                 "d": { "package": "x", "extras": [ { "key": "k", "kind": "string", "value": "{package}" } ] } }"""
        )
        assertEquals(listOf("d"), p.value.keys.toList())
        assertEquals(3, p.messages.size)
        assertTrue(p.messages.all { "takes no game" in it })
    }

    // ---- directory.json ----

    @Test fun `directory parses a nested tree`() {
        val p = LibraryParser.parseDirectory(
            """{ "_about": "x", "items": [
                 { "folder": "Nintendo", "_note": "n", "items": [ { "system": "GB" }, { "folder": "Hacks", "items": [ { "system": "GBAHACKS" } ] } ] },
                 { "folder": "Empty" },
                 { "shortcut": "kodi-movies" },
                 { "app": "com.android.settings", "name": "Settings" },
                 { "app": "org.foo" } ] }"""
        )
        assertTrue(p.messages.toString(), p.messages.isEmpty())
        assertEquals(
            listOf(
                DirectoryItem.Folder("Nintendo", listOf(
                    DirectoryItem.SystemItem("GB"),
                    DirectoryItem.Folder("Hacks", listOf(DirectoryItem.SystemItem("GBAHACKS"))),
                )),
                DirectoryItem.Folder("Empty", emptyList()),
                DirectoryItem.ShortcutItem("kodi-movies"),
                DirectoryItem.AppItem("com.android.settings", "Settings"),
                DirectoryItem.AppItem("org.foo", null),
            ),
            p.value,
        )
    }

    @Test fun `duplicate folders and a system placed twice are skipped`() {
        val p = LibraryParser.parseDirectory(
            """{ "items": [
                 { "folder": "A", "items": [ { "system": "GB" }, { "app": "x" }, { "app": "x" } ] },
                 { "folder": "A", "items": [ { "system": "NES" } ] },
                 { "folder": "B", "items": [ { "system": "GB" }, { "folder": "A" } ] },
                 { "system": "X", "app": "y" }, "str", { "system": "" } ] }"""
        )
        assertEquals(
            listOf(
                DirectoryItem.Folder("A", listOf(DirectoryItem.SystemItem("GB"), DirectoryItem.AppItem("x", null))),
                DirectoryItem.Folder("B", listOf(DirectoryItem.Folder("A", emptyList()))),
            ),
            p.value,
        )
        assertEquals(p.messages.toString(), 6, p.messages.size)
        assertTrue(p.messages.any { "items[2].items[0]" in it && "already in the menu" in it })
        assertTrue(p.messages.any { "items[1]" in it && "folder \"A\" is already in this folder" in it })
        // Repeats are warnings, malformed items errors.
        assertEquals(Severity.WARNING, p.severityOf("items[2].items[0]"))
        assertEquals(Severity.WARNING, p.severityOf("items[0].items[2]"))
        assertEquals(Severity.WARNING, p.severityOf("items[1] skipped"))
        assertEquals(Severity.ERROR, p.severityOf("items[3] skipped"))
        assertEquals(Severity.ERROR, p.severityOf("items[4] skipped"))
        assertEquals(Severity.ERROR, p.severityOf("items[5] skipped"))
    }

    @Test fun `directory without items is not a usable document`() {
        val p = LibraryParser.parseDirectory("{}")
        assertFalse(p.validJson)
        assertEquals(1, p.messages.size)
    }

    @Test fun `an absolute roms folder keeps its leading slash and loses trailing ones`() {
        val p = LibraryParser.parseSystems(
            """{ "GBA": { "roms": "/storage/1234-ABCD/GBA/" },
                 "GB": { "roms": " //storage//1234-ABCD/./GB " } }"""
        )
        assertTrue(p.messages.toString(), p.messages.isEmpty())
        assertEquals("/storage/1234-ABCD/GBA", p.value.getValue("GBA").roms)
        assertEquals("/storage/1234-ABCD/GB", p.value.getValue("GB").roms)
    }

    @Test fun `a relative roms folder is trimmed as before`() {
        val p = LibraryParser.parseSystems(
            """{ "GBA": { "roms": "Game Boy Advance/" }, "GB": { "roms": "" }, "PS": { "roms": "Sony/PS" } }"""
        )
        assertTrue(p.messages.toString(), p.messages.isEmpty())
        assertEquals("Game Boy Advance", p.value.getValue("GBA").roms)
        assertEquals("GB", p.value.getValue("GB").roms)
        assertEquals("Sony/PS", p.value.getValue("PS").roms)
    }

    @Test fun `a roms folder with dot-dot or only the device root is skipped`() {
        val p = LibraryParser.parseSystems(
            """{ "A": { "roms": "../Elsewhere" }, "B": { "roms": "GBA/../../x" },
                 "C": { "roms": "/storage/../data" }, "D": { "roms": "/" }, "E": { "roms": "E" } }"""
        )
        assertEquals(setOf("E"), p.value.keys)
        assertEquals(4, p.messages.size)
        assertTrue(p.messages.toString(), p.messages.first().contains(".."))
    }

    // ---- overrides.json ----

    @Test fun `overrides parse and skip comments and bad values`() {
        val p = LibraryParser.parseOverrides(
            """{ "_about": "x", "games": { "_note": "n", "GBA/Pokemon Emerald.gba": "pizzaboy", "/GB/a.gb": "retroarch",
                 "GB/b.gb": 3, "GB/c.gb": "" } }"""
        )
        // A key starting with "/" is an absolute path, for a system whose folder is absolute.
        assertEquals(mapOf("GBA/Pokemon Emerald.gba" to "pizzaboy", "/GB/a.gb" to "retroarch"), p.value)
        assertEquals(2, p.messages.size)
        assertTrue(p.problems.all { it.severity == Severity.ERROR })
    }

    @Test fun `the empty documents a new install gets parse cleanly`() {
        for (file in LibraryFile.entries) {
            val p: Parsed<*> = when (file) {
                LibraryFile.EMULATORS -> LibraryParser.parseEmulators(file.emptyText)
                LibraryFile.SYSTEMS -> LibraryParser.parseSystems(file.emptyText)
                LibraryFile.SHORTCUTS -> LibraryParser.parseShortcuts(file.emptyText)
                LibraryFile.DIRECTORY -> LibraryParser.parseDirectory(file.emptyText)
                LibraryFile.OVERRIDES -> LibraryParser.parseOverrides(file.emptyText)
            }
            assertTrue(file.name, p.validJson && p.messages.isEmpty())
        }
    }
}
