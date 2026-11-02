package dev.cannoli.scorza.config

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

/** The edits behind the main menu's folder actions and Kitchen, and what they write back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoldersConfigEditTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var file: File

    private fun config(json: String): FoldersConfig {
        file = File(tmp.root, "Config/folders.json").apply { parentFile!!.mkdirs(); writeText(json) }
        return FoldersConfig { file }.also { it.load() }
    }

    /** What a fresh read of the written file says, so every assertion checks the file too. */
    private fun reread(): FoldersConfig = FoldersConfig { file }.also { it.load() }

    private fun shape(node: FolderNode): List<String> = node.entries.map {
        when (it) {
            is FolderEntry.Folder -> "folder:${it.node.name}"
            is FolderEntry.Platform -> "platform:${it.tag}"
            is FolderEntry.App -> "app:${it.packageName}"
        }
    }

    private val tree = """
        { "folders": [
          { "name": "Nintendo", "items": [
              { "platform": "GBA" },
              { "folder": { "name": "Hacks", "items": [ { "platform": "GBAHACKS" } ] } },
              { "app": { "package": "org.custom", "activity": "org.custom.Main", "extras": [ { "key": "x", "kind": "string", "value": "1" } ] }, "name": "Custom" }
          ] },
          { "name": "Tools", "items": [ { "app": "com.android.settings" } ] }
        ] }
    """.trimIndent()

    @Test fun `create adds a folder at the end and refuses a duplicate name`() {
        val c = config(tree)
        assertNull(c.createFolder(listOf("Nintendo"), "Handhelds"))
        assertEquals(listOf("platform:GBA", "folder:Hacks", "app:org.custom", "folder:Handhelds"), shape(reread().find(listOf("Nintendo"))!!))
        assertNotNull(c.createFolder(listOf("Nintendo"), "Hacks"))
        assertNotNull(c.createFolder(emptyList(), "  "))
        assertNull(c.createFolder(emptyList(), "Arcade"))
        assertEquals(listOf("Nintendo", "Tools", "Arcade"), reread().roots.map { it.name })
    }

    @Test fun `rename keeps contents and fixes nested paths`() {
        val c = config(tree)
        assertNull(c.renameFolder(listOf("Nintendo"), "Big N"))
        val r = reread()
        assertNull(r.find(listOf("Nintendo")))
        assertEquals(listOf("Big N", "Hacks"), r.find(listOf("Big N", "Hacks"))!!.path)
        assertNotNull("a sibling already has that name", c.renameFolder(listOf("Big N"), "Tools"))
    }

    @Test fun `deleting a nested folder moves its contents up into its place`() {
        val c = config(tree)
        assertNull(c.deleteFolder(listOf("Nintendo", "Hacks")))
        assertEquals(listOf("platform:GBA", "platform:GBAHACKS", "app:org.custom"), shape(reread().find(listOf("Nintendo"))!!))
    }

    @Test fun `deleting a top level folder returns platforms to the menu and keeps apps and subfolders`() {
        val c = config(tree)
        assertNull(c.deleteFolder(listOf("Nintendo")))
        val r = reread()
        assertEquals(listOf("folder:Hacks", "app:org.custom", "folder:Tools"), shape(r.root))
        assertFalse("GBA is back on the main menu", "GBA" in r.platformTagsInFolders())
        assertTrue("GBAHACKS stays in Hacks", "GBAHACKS" in r.platformTagsInFolders())
    }

    @Test fun `a subfolder moving up beside a same named folder gets a number`() {
        val c = config("""{ "folders": [ { "name": "Hacks" }, { "name": "Outer", "items": [ { "folder": { "name": "Hacks" } } ] } ] }""")
        assertNull(c.deleteFolder(listOf("Outer")))
        assertEquals(listOf("folder:Hacks", "folder:Hacks (2)"), shape(reread().root))
    }

    @Test fun `move takes an entry out of one folder and into another`() {
        val c = config(tree)
        assertNull(c.move(FolderEntry.Platform("GBA"), listOf("Tools")))
        val r = reread()
        assertFalse("platform:GBA" in shape(r.find(listOf("Nintendo"))!!))
        assertEquals(listOf("app:com.android.settings", "platform:GBA"), shape(r.find(listOf("Tools"))!!))
    }

    @Test fun `moving a platform to the main menu just takes it out of its folder`() {
        val c = config(tree)
        assertNull(c.move(FolderEntry.Platform("GBA"), emptyList()))
        val r = reread()
        assertFalse("GBA" in r.platformTagsInFolders())
        assertTrue(r.root.entries.none { it is FolderEntry.Platform })
    }

    @Test fun `a platform from the main menu moves into a folder`() {
        val c = config(tree)
        assertNull(c.move(FolderEntry.Platform("SNES"), listOf("Nintendo", "Hacks")))
        assertEquals(listOf("platform:GBAHACKS", "platform:SNES"), shape(reread().find(listOf("Nintendo", "Hacks"))!!))
    }

    @Test fun `a folder moves with everything in it but never into itself`() {
        val c = config(tree)
        val hacks = FolderEntry.Folder(c.find(listOf("Nintendo", "Hacks"))!!)
        assertNull(c.move(hacks, listOf("Tools")))
        assertEquals(listOf("Tools", "Hacks"), reread().find(listOf("Tools", "Hacks"))!!.path)
        val nintendo = FolderEntry.Folder(c.find(listOf("Nintendo"))!!)
        assertNotNull(c.move(nintendo, listOf("Nintendo")))
        val tools = FolderEntry.Folder(c.find(listOf("Tools"))!!)
        assertNotNull(c.move(tools, listOf("Tools", "Hacks")))
    }

    @Test fun `an app moved to the main menu is listed at the top level`() {
        val c = config(tree)
        assertNull(c.move(FolderEntry.App("com.android.settings", null, null), emptyList()))
        assertEquals(listOf("com.android.settings"), reread().rootApps.map { it.packageName })
    }

    @Test fun `reorder follows the given keys and keeps the rest after them`() {
        val c = config(tree)
        assertNull(c.reorder(listOf("Nintendo"), listOf("app:org.custom", "platform:GBA")))
        assertEquals(listOf("app:org.custom", "platform:GBA", "folder:Hacks"), shape(reread().find(listOf("Nintendo"))!!))
    }

    @Test fun `setApps adds and removes apps but leaves everything else where it was`() {
        val c = config(tree)
        assertNull(c.setApps(listOf("Nintendo"), listOf("org.custom" to "Custom", "org.new" to "New")))
        val r = reread()
        assertEquals(listOf("platform:GBA", "folder:Hacks", "app:org.custom", "app:org.new"), shape(r.find(listOf("Nintendo"))!!))
        assertNull(c.setApps(listOf("Nintendo"), emptyList()))
        assertEquals(listOf("platform:GBA", "folder:Hacks"), shape(reread().find(listOf("Nintendo"))!!))
    }

    @Test fun `a hand written app intent survives an edit elsewhere`() {
        val c = config(tree)
        assertNull(c.createFolder(emptyList(), "Arcade"))
        val written = JSONObject(file.readText())
        val custom = written.getJSONArray("folders").getJSONObject(0).getJSONArray("items").getJSONObject(2)
        assertEquals("org.custom.Main", custom.getJSONObject("app").getString("activity"))
        assertEquals("1", custom.getJSONObject("app").getJSONArray("extras").getJSONObject(0).getString("value"))
        assertEquals("org.custom.Main", reread().intentFor("org.custom")?.activity)
    }

    @Test fun `every save keeps the previous file as a backup`() {
        val c = config(tree)
        assertNull(c.createFolder(emptyList(), "Arcade"))
        assertEquals(tree, File(file.parentFile, "folders.json.bak").readText())
    }

    @Test fun `an edit is refused for a folder that is gone`() {
        val c = config(tree)
        assertNotNull(c.createFolder(listOf("Nope"), "X"))
        assertNotNull(c.deleteFolder(listOf("Nope")))
        assertNotNull(c.move(FolderEntry.Platform("GBA"), listOf("Nope")))
        assertEquals(tree, file.readText())
    }

    @Test fun `replaceText refuses text that is not JSON and keeps the file`() {
        val c = config(tree)
        val (written, problems) = c.replaceText("{ nope")
        assertFalse(written)
        assertEquals(1, problems.size)
        assertEquals(tree, file.readText())
        val (ok, none) = c.replaceText("""{ "folders": [ { "name": "Only" } ] }""")
        assertTrue(ok)
        assertTrue(none.isEmpty())
        assertEquals(listOf("Only"), c.roots.map { it.name })
        assertFalse("its own write is not mistaken for an outside edit", c.reloadIfChanged())
    }

    @Test fun `a top level platform entry is reported, since the main menu needs none`() {
        val c = config("""{ "folders": [ { "platform": "GBA" }, { "name": "A" } ] }""")
        assertEquals(listOf("A"), c.roots.map { it.name })
        assertEquals(1, c.errors.size)
    }
}
