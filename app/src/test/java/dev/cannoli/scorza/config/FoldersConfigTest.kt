package dev.cannoli.scorza.config

import android.content.Intent
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoldersConfigTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun config(json: String?): Pair<FoldersConfig, File> {
        val file = File(tmp.root, "Config/folders.json")
        if (json != null) file.apply { parentFile!!.mkdirs() }.writeText(json)
        return FoldersConfig { file } to file
    }

    private val nested = """
        { "folders": [
          { "name": "Nintendo", "items": [
              { "platform": "gba" },
              { "folder": { "name": "Hacks", "items": [
                  { "platform": "GBAHACKS" },
                  { "folder": { "name": "Deeper", "items": [ { "platform": "GB" } ] } }
              ] } }
          ] },
          { "name": "Tools", "items": [
              { "app": "com.android.settings" },
              { "app": { "package": "org.foo", "activity": "org.foo.Main" }, "name": "Foo" }
          ] }
        ] }
    """.trimIndent()

    @Test fun `a nested tree parses in file order with paths`() {
        val (c, _) = config(nested)
        c.load()
        assertTrue(c.errors.isEmpty())
        assertEquals(listOf("Nintendo", "Tools"), c.roots.map { it.name })
        val deeper = c.find(listOf("Nintendo", "Hacks", "Deeper"))
        assertNotNull(deeper)
        assertEquals(listOf("Nintendo", "Hacks", "Deeper"), deeper!!.path)
        assertEquals(listOf(FolderEntry.Platform("GB")), deeper.entries)
        assertNull(c.find(listOf("Nintendo", "Nope")))
        assertNull(c.find(emptyList()))
    }

    @Test fun `platform tags in folders are collected from every level and upper cased`() {
        val (c, _) = config(nested)
        c.load()
        assertEquals(setOf("GBA", "GBAHACKS", "GB"), c.platformTagsInFolders())
    }

    @Test fun `a bare package app opens normally and an object with an activity gets its own intent`() {
        val (c, _) = config(nested)
        c.load()
        val apps = c.allApps()
        assertEquals(listOf("com.android.settings", "org.foo"), apps.map { it.packageName })
        assertNull(apps[0].intent)
        assertNull(apps[0].name)
        assertEquals("Foo", apps[1].name)
        val intent = c.intentFor("org.foo")
        assertNotNull(intent)
        assertEquals("org.foo.Main", intent!!.activity)
        // No action given: the app is opened, not handed a file to VIEW.
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(c.intentFor("com.android.settings"))
    }

    @Test fun `a bad entry is skipped and reported while its siblings load`() {
        val (c, _) = config("""
            { "folders": [
              { "name": "A", "items": [
                  { "platform": "GBA" },
                  { "nonsense": true },
                  { "app": "" },
                  { "folder": { "items": [] } },
                  { "app": "org.ok" }
              ] },
              { "items": [] },
              { "name": "A", "items": [] },
              "not an object"
            ] }
        """.trimIndent())
        c.load()
        assertEquals(listOf("A"), c.roots.map { it.name })
        assertEquals(listOf(FolderEntry.Platform("GBA"), FolderEntry.App("org.ok", null, null)), c.roots.single().entries)
        assertEquals(6, c.errors.size)
        assertTrue(c.errors.any { it.contains("already here") })
    }

    @Test fun `a file that is not JSON leaves no folders and one error`() {
        val (c, _) = config("{ nope")
        c.load()
        assertTrue(c.roots.isEmpty())
        assertEquals(1, c.errors.size)
    }

    @Test fun `a missing file is seeded and then read`() {
        val (c, file) = config(null)
        c.load(seed = { FoldersConfig.seedFromLegacy(listOf("Tools" to listOf("Settings" to "com.android.settings"))) })
        assertTrue(file.exists())
        assertEquals(listOf("Tools"), c.roots.map { it.name })
        assertEquals(FolderEntry.App("com.android.settings", "Settings", null), c.roots.single().entries.single())
    }

    @Test fun `the legacy seed keeps both lists in order and drops an empty one`() {
        val json = FoldersConfig.seedFromLegacy(listOf(
            "Tools" to listOf("Files" to "com.files", "Settings" to "com.settings"),
            "Ports" to emptyList(),
        ))
        val parsed = FoldersConfig.parse(json, "seed")
        assertTrue(parsed.errors.isEmpty())
        assertEquals(listOf("Tools"), parsed.roots.map { it.name })
        assertEquals(listOf("com.files", "com.settings"),
            parsed.roots.single().entries.map { (it as FolderEntry.App).packageName })
    }

    @Test fun `an edited file is picked up by reloadIfChanged`() {
        val (c, file) = config("""{ "folders": [ { "name": "Before" } ] }""")
        c.load()
        assertFalse(c.reloadIfChanged())
        file.writeText("""{ "folders": [ { "name": "After" } ] }""")
        file.setLastModified(file.lastModified() + 2000)
        assertTrue(c.reloadIfChanged())
        assertEquals(listOf("After"), c.roots.map { it.name })
    }
}
