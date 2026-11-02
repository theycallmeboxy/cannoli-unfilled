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

/** The edits behind the menu's folder actions, and what they write to directory.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryEditorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir: File get() = File(tmp.root, "Config")
    private val file: File get() = File(dir, LibraryParser.DIRECTORY)

    private val systems = """
        { "GBA": { "name": "Game Boy Advance" }, "GBAHACKS": {}, "GB": {}, "SNES": {} }
    """.trimIndent()

    private val tree = """
        { "items": [
          { "folder": "Nintendo", "_note": "kept", "items": [
              { "system": "GBA" },
              { "folder": "Hacks", "items": [ { "system": "GBAHACKS" } ] },
              { "app": "org.custom", "name": "Custom" }
          ] },
          { "folder": "Tools", "items": [ { "app": "com.android.settings" } ] },
          { "system": "SNES" }
        ] }
    """.trimIndent()

    private var templates = mapOf<String, String>()

    private fun config(json: String = tree): LibraryConfig {
        dir.mkdirs()
        File(dir, LibraryParser.SYSTEMS).writeText(systems)
        file.writeText(json)
        return LibraryConfig({ dir }) { templates[it.removePrefix("${LibraryConfig.TEMPLATE_ASSET_DIR}/")] }
            .also { it.load() }
    }

    /** What a fresh read of the written file says, so every assertion checks the file too. */
    private fun reread(): Library = LibraryConfig({ dir }) { null }.also { it.load() }.library

    private fun shape(path: List<String>): List<String> = reread().itemsAt(path)!!.map { it.key }

    @Test fun `create adds a folder at the end and refuses a duplicate name`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.createFolder(listOf("Nintendo"), "Handhelds"))
        assertEquals(
            listOf("system:GBA", "folder:Hacks", "app:org.custom", "folder:Handhelds"),
            shape(listOf("Nintendo")),
        )
        assertNotNull(edit.createFolder(listOf("Nintendo"), "Hacks"))
        assertNotNull(edit.createFolder(emptyList(), "  "))
        assertNull(edit.createFolder(emptyList(), "Arcade"))
        assertEquals(listOf("folder:Nintendo", "folder:Tools", "system:SNES", "folder:Arcade"), shape(emptyList()))
    }

    @Test fun `rename keeps contents and refuses a sibling's name`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.renameFolder(listOf("Nintendo"), "Big N"))
        assertNull(reread().itemsAt(listOf("Nintendo")))
        assertEquals(listOf("system:GBAHACKS"), shape(listOf("Big N", "Hacks")))
        assertNotNull("a sibling already has that name", edit.renameFolder(listOf("Big N"), "Tools"))
    }

    @Test fun `deleting a folder moves its contents up into its place`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.deleteFolder(listOf("Nintendo", "Hacks")))
        assertEquals(listOf("system:GBA", "system:GBAHACKS", "app:org.custom"), shape(listOf("Nintendo")))
        assertNull(edit.deleteFolder(listOf("Nintendo")))
        assertEquals(
            listOf("system:GBA", "system:GBAHACKS", "app:org.custom", "folder:Tools", "system:SNES"),
            shape(emptyList()),
        )
    }

    @Test fun `a subfolder moving up beside a same named folder gets a number`() {
        val edit = DirectoryEditor(config("""{ "items": [ { "folder": "Hacks" }, { "folder": "Outer", "items": [ { "folder": "Hacks" } ] } ] }"""))
        assertNull(edit.deleteFolder(listOf("Outer")))
        assertEquals(listOf("folder:Hacks", "folder:Hacks (2)"), shape(emptyList()))
    }

    @Test fun `move takes an item out of one folder and into another`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.move("system:GBA", listOf("Nintendo"), listOf("Tools")))
        assertFalse("system:GBA" in shape(listOf("Nintendo")))
        assertEquals(listOf("app:com.android.settings", "system:GBA"), shape(listOf("Tools")))
        assertNull(edit.move("system:SNES", emptyList(), listOf("Nintendo", "Hacks")))
        assertEquals(listOf("system:GBAHACKS", "system:SNES"), shape(listOf("Nintendo", "Hacks")))
        assertNull(edit.move("app:org.custom", listOf("Nintendo"), emptyList()))
        assertEquals("app:org.custom", shape(emptyList()).last())
    }

    @Test fun `a folder moves with everything in it but never into itself`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.move("folder:Hacks", listOf("Nintendo"), listOf("Tools")))
        assertEquals(listOf("system:GBAHACKS"), shape(listOf("Tools", "Hacks")))
        assertNotNull(edit.move("folder:Nintendo", emptyList(), listOf("Nintendo")))
        assertNotNull(edit.move("folder:Tools", emptyList(), listOf("Tools", "Hacks")))
    }

    @Test fun `an unplaced system is placed once and only once`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.move("system:GB", null, emptyList()))
        assertEquals("system:GB", shape(emptyList()).last())
        assertNotNull("already placed", edit.move("system:GB", null, listOf("Tools")))
        assertEquals(setOf("GBA", "GBAHACKS", "SNES", "GB"), reread().placedSystemIds())
    }

    @Test fun `remove takes an item out, leaving a system unplaced`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.remove(emptyList(), "system:SNES"))
        assertFalse("SNES" in reread().placedSystemIds())
    }

    @Test fun `reorder follows the given keys and keeps the rest after them`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.reorder(listOf("Nintendo"), listOf("app:org.custom", "system:GBA")))
        assertEquals(listOf("app:org.custom", "system:GBA", "folder:Hacks"), shape(listOf("Nintendo")))
        assertNull(edit.reorder(emptyList(), listOf("system:SNES", "folder:Tools", "folder:Nintendo")))
        assertEquals(listOf("system:SNES", "folder:Tools", "folder:Nintendo"), shape(emptyList()))
    }

    @Test fun `fill sets the apps and adds unplaced systems, leaving the rest where it was`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.fill(listOf("Nintendo"), listOf("org.custom" to "Custom", "org.new" to "New"), listOf("GB", "SNES")))
        assertEquals(
            listOf("system:GBA", "folder:Hacks", "app:org.custom", "system:GB", "app:org.new"),
            shape(listOf("Nintendo")),
        )
        val added = reread().itemsAt(listOf("Nintendo"))!!.last() as DirectoryItem.AppItem
        assertEquals("New", added.name)
        assertNull(edit.fill(listOf("Nintendo"), emptyList(), emptyList()))
        assertEquals(listOf("system:GBA", "folder:Hacks", "system:GB"), shape(listOf("Nintendo")))
    }

    @Test fun `comment keys and entries this build skips survive an edit`() {
        val edit = DirectoryEditor(config(tree.replace("""{ "system": "SNES" }""", """{ "system": "NOPE" }""")))
        assertNull(edit.createFolder(emptyList(), "Arcade"))
        val written = JSONObject(file.readText()).getJSONArray("items")
        assertEquals("kept", written.getJSONObject(0).getString("_note"))
        assertEquals("NOPE", written.getJSONObject(2).getString("system"))
    }

    @Test fun `every save keeps the previous file as a backup`() {
        val edit = DirectoryEditor(config())
        assertNull(edit.createFolder(emptyList(), "Arcade"))
        assertEquals(tree, File(dir, "${LibraryParser.DIRECTORY}.bak").readText())
    }

    @Test fun `an edit is refused for a folder that is gone, or a file that is not JSON`() {
        val c = config()
        val edit = DirectoryEditor(c)
        assertNotNull(edit.createFolder(listOf("Nope"), "X"))
        assertNotNull(edit.deleteFolder(listOf("Nope")))
        assertNotNull(edit.move("system:GBA", listOf("Nintendo"), listOf("Nope")))
        assertEquals(tree, file.readText())
        file.writeText("{ nope")
        assertNotNull(edit.createFolder(emptyList(), "X"))
        assertEquals("{ nope", file.readText())
    }

    @Test fun `a ROM folder adopts its system and emulators from the templates`() {
        templates = mapOf(
            DirectoryEditor.SYSTEMS_TEMPLATE to """
                { "_about": ["x"],
                  "PCE": { "_note": "n", "name": "PC Engine", "roms": "PCE", "emulators": [ { "emulator": "retroarch", "core": "pce" }, "solo" ] } }
            """.trimIndent(),
            DirectoryEditor.EMULATORS_TEMPLATE to """
                { "retroarch": { "package": "com.retroarch", "extras": [ { "key": "C", "kind": "string", "value": "{core}" } ] },
                  "solo": { "package": "org.solo" }, "other": { "package": "org.other" } }
            """.trimIndent(),
        )
        val c = config()
        File(dir, LibraryParser.EMULATORS).writeText("""{ "solo": { "package": "org.mine" } }""")
        c.reloadIfChanged()
        val edit = DirectoryEditor(c)
        assertEquals("PC Engine", edit.templateSystemFor("pce"))
        assertNull(edit.templateSystemFor("Nothing"))

        assertEquals(DirectoryEditor.Adoption.Added("PCE", "PC Engine"), edit.adoptRomFolder("pce"))
        val library = c.library
        assertEquals("pce", library.systems.getValue("PCE").roms)
        assertEquals(listOf("retroarch", "solo"), library.systems.getValue("PCE").emulators.map { it.emulator })
        assertEquals("an emulator already there is left alone", "org.mine", library.emulators.getValue("solo").intent.packageName)
        assertFalse("other" in library.emulators)
        assertEquals(DirectoryEditor.Adoption.NoTemplate, edit.adoptRomFolder("Nothing"))
        assertTrue(edit.adoptRomFolder("PCE") is DirectoryEditor.Adoption.Failed)
    }
}
