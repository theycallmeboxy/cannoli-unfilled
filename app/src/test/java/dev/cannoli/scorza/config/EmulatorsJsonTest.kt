package dev.cannoli.scorza.config

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.launcher.EmulatorIntentBuilder
import dev.cannoli.scorza.launcher.ResolvedExtra
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
class EmulatorsJsonTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun root(name: String, emulatorsJson: String? = null): File {
        val root = File(tmp.root, name).apply { mkdirs() }
        if (emulatorsJson != null) {
            File(root, "Config").apply { mkdirs() }.also { File(it, "emulators.json").writeText(emulatorsJson) }
        }
        return root
    }

    private fun load(root: File) = PlatformConfig(root, ctx.assets).also { it.load() }

    @Test fun `a missing emulators_json is seeded from the bundled definitions`() {
        val root = root("seed")
        val pc = load(root)
        val seeded = File(root, "Config/emulators.json")
        assertTrue(seeded.exists())
        assertEquals(ctx.assets.open("platforms.json").use { it.bufferedReader().readText() }, seeded.readText())
        assertTrue(pc.emulatorConfigErrors.isEmpty())
        assertTrue("GBA" in pc.getAllTags())
    }

    @Test fun `the user file replaces the bundled definitions`() {
        val pc = load(root("user", """
            {"GBA": {"name": "My GBA", "core": "gpsp_libretro",
                     "app": [{"package": "org.example.gba", "activity": "org.example.Main"}]}}
        """.trimIndent()))
        assertEquals("My GBA", pc.getDisplayName("GBA"))
        assertEquals("gpsp_libretro", pc.getCoreName("GBA"))
        assertEquals(listOf("org.example.gba"), pc.getAppOptions("GBA").map { it.packageName })
        assertEquals("org.example.Main", pc.getAppConfig("GBA", "org.example.gba").activity)
        // Only what the user file names exists: the bundled NES entry is gone with it.
        assertFalse(pc.isKnownTag("NES"))
    }

    @Test fun `a bad entry is skipped and reported while the rest still load`() {
        val pc = load(root("bad-entry", """
            {"GBA": {"name": "GBA", "app": [
                {"package": "org.good.one"},
                {"activity": "no.package.Here"},
                {"package": "org.bad.data", "data": {"kind": "nonsense"}},
                {"package": "org.good.two"}
             ]},
             "NES": "not an object",
             "SNES": {"name": "Super Nintendo"}}
        """.trimIndent()))
        assertEquals(listOf("org.good.one", "org.good.two"), pc.getAppOptions("GBA").map { it.packageName })
        assertEquals("Super Nintendo", pc.getDisplayName("SNES"))
        assertEquals(3, pc.emulatorConfigErrors.size)
        assertTrue(pc.emulatorConfigErrors.any { it.contains("GBA.app[1]") })
        assertTrue(pc.emulatorConfigErrors.any { it.contains("GBA.app[2]") })
        assertTrue(pc.emulatorConfigErrors.any { it.startsWith("emulators.json: NES") })
    }

    @Test fun `a file that is not JSON falls back to the bundled definitions`() {
        val pc = load(root("broken", "{ this is not json"))
        assertTrue("GBA" in pc.getAllTags())
        assertTrue(pc.getAppOptions("GBA").isNotEmpty())
        assertEquals(1, pc.emulatorConfigErrors.size)
        assertTrue(pc.emulatorConfigErrors.single().contains("not valid JSON"))
    }

    @Test fun `a bare string naming a template uses that template`() {
        val pc = load(root("template", """
            {"apps": {"ra64": {"package": "com.retroarch.aarch64", "activity": "x.Activity"}},
             "GBA": {"name": "GBA", "app": ["ra64", "org.plain.package"]}}
        """.trimIndent()))
        val apps = pc.getAppOptions("GBA")
        assertEquals("com.retroarch.aarch64", apps[0].packageName)
        assertEquals("x.Activity", apps[0].activity)
        assertEquals("org.plain.package", apps[1].packageName)
        assertNull(apps[1].activity)
        // The template key is not a platform.
        assertFalse(pc.isKnownTag("apps"))
    }

    @Test fun `an edited file is picked up by reloadEmulatorsIfChanged`() {
        val root = root("reload", """{"GBA": {"name": "Before"}}""")
        val pc = load(root)
        assertFalse("nothing changed yet", pc.reloadEmulatorsIfChanged())
        val file = File(root, "Config/emulators.json")
        file.writeText("""{"GBA": {"name": "After"}}""")
        file.setLastModified(file.lastModified() + 2000)
        assertTrue(pc.reloadEmulatorsIfChanged())
        assertEquals("After", pc.getDisplayName("GBA"))
    }

    // Older builds copied every bundled name into platforms.ini on first run. Those copies must
    // not shadow a name the user then sets in emulators.json; a real rename still wins.
    @Test fun `a platforms_ini copy of the bundled name does not shadow emulators_json`() {
        val root = root("ini-copy", """{"GBA": {"name": "From JSON"}, "NES": {"name": "From JSON"}}""")
        File(root, "Config/platforms.ini").writeText("[platforms]\nGBA = Game Boy Advance\nNES = Famicom\n")
        val pc = load(root)
        assertEquals("From JSON", pc.getDisplayName("GBA"))
        assertEquals("Famicom", pc.getDisplayName("NES"))
    }

    @Test fun `removing the retroarch template leaves no template`() {
        val pc = load(root("no-ra", """{"GBA": {"name": "GBA"}}"""))
        assertNull(pc.getRetroArchTemplate())
    }

    // The bundled template must reproduce the intent the launcher used to hardcode.
    @Test fun `the bundled retroarch template resolves to the classic RetroArch intent`() {
        val pc = PlatformConfig(root("ra"), ctx.assets)
        val template = pc.getRetroArchTemplate()
        assertNotNull(template)
        val pkg = "com.retroarch"
        val rom = tmp.newFile("Game.gba")
        val resolved = EmulatorIntentBuilder.resolve(
            ctx, template!!.copy(packageName = pkg), rom, mapOf(EmulatorIntentBuilder.VAR_CORE to "mgba_libretro"),
        )
        assertEquals(ComponentName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture"), resolved.component)
        assertNull(resolved.dataUri)
        val extras = resolved.extras.associate { (it as ResolvedExtra.StringExtra).key to it.value }
        assertEquals(
            mapOf(
                "ROM" to rom.absolutePath,
                "LIBRETRO" to "/data/data/$pkg/cores/mgba_libretro_android.so",
                "CONFIGFILE" to "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg",
            ),
            extras,
        )
    }
}
