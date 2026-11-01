package dev.cannoli.scorza.config

import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformSeedTest {

    @get:Rule val tmp = TemporaryFolder()

    // SNES lists exactly one standalone app, so installing it is what makes seeding decide.
    private val snesApp = "com.explusalpha.Snes9xPlus"

    private fun config(name: String): PlatformConfig {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, name).apply { mkdirs() }
        return PlatformConfig(root, ctx.assets).also { it.load() }
    }

    private fun pm() = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    private fun install(pkg: String) {
        shadowOf(pm()).installPackage(PackageInfo().apply { packageName = pkg })
    }

    @Test fun `a sole installed standalone app seeds Standalone`() {
        install(snesApp)
        val pc = config("seed-app")
        pc.seedUnsetPlatforms(pm())
        assertEquals(EmulatorChoice(EmulatorSource.Standalone, appPackage = snesApp), pc.getPlatformChoice("SNES"))
    }

    @Test fun `no installed app leaves the platform unset`() {
        val pc = config("seed-none")
        pc.seedUnsetPlatforms(pm())
        assertNull(pc.getPlatformChoice("SNES"))
    }

    // The invariant. Seeding must never touch an existing choice, including one whose target
    // is not installed, which is the case a "repair on boot" implementation would get wrong.
    @Test fun `seeding never overwrites an explicit choice`() {
        install(snesApp)
        val pc = config("seed-keep")
        pc.setPlatformChoice("SNES", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.not.installed"))
        pc.seedUnsetPlatforms(pm())
        assertEquals(
            EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.not.installed"),
            pc.getPlatformChoice("SNES"),
        )
    }

    @Test fun `an uninstalled core choice also survives seeding`() {
        install(snesApp)
        val pc = config("seed-keep-core")
        pc.setPlatformChoice("SNES", EmulatorChoice(EmulatorSource.RetroArch, "snes9x_libretro"))
        pc.seedUnsetPlatforms(pm())
        assertEquals(EmulatorChoice(EmulatorSource.RetroArch, "snes9x_libretro"), pc.getPlatformChoice("SNES"))
    }

    @Test fun `seeding is idempotent`() {
        install(snesApp)
        val pc = config("seed-idem")
        assertTrue("the first pass must seed something", pc.seedUnsetPlatforms(pm()) > 0)
        assertEquals(0, pc.seedUnsetPlatforms(pm()))
    }

    // Reset resolves the default immediately, so what the user sees after confirming is final
    // rather than something a later boot fills in.
    @Test fun `reset restores the sole installed app straight away`() {
        install(snesApp)
        val pc = config("seed-reset")
        pc.setPlatformChoice("SNES", EmulatorChoice(EmulatorSource.RetroArch, "snes9x_libretro"))
        val expected = EmulatorChoice(EmulatorSource.Standalone, appPackage = snesApp)
        assertEquals(expected, pc.resetPlatformToDefault("SNES", pm()))
        assertEquals(expected, pc.getPlatformChoice("SNES"))
    }

    @Test fun `reset on a platform with no default leaves it unmapped`() {
        val pc = config("seed-reset-none")
        pc.setPlatformChoice("PS2", EmulatorChoice(EmulatorSource.Standalone, appPackage = "com.picked.emu"))
        assertNull(pc.resetPlatformToDefault("PS2", pm()))
        assertNull(pc.getPlatformChoice("PS2"))
    }

    @Test fun `reset persists so a reload keeps the restored default`() {
        install(snesApp)
        val pc = config("seed-reset-persist")
        pc.setPlatformChoice("SNES", EmulatorChoice(EmulatorSource.RetroArch, "snes9x_libretro"))
        pc.resetPlatformToDefault("SNES", pm())
        pc.reloadCoreMappings()
        assertEquals(EmulatorChoice(EmulatorSource.Standalone, appPackage = snesApp), pc.getPlatformChoice("SNES"))
    }

    @Test fun `a failed load suppresses seeding entirely`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(tmp.root, "seed-torn").apply { mkdirs() }
        File(root, "Config").apply { mkdirs() }.also { File(it, "cores.json").writeText("{ broken") }
        val pc = PlatformConfig(root, ctx.assets).also { it.load() }
        assertEquals(0, pc.seedUnsetPlatforms(pm()))
    }

    @Test fun `a seeded choice persists across a reload`() {
        install(snesApp)
        val pc = config("seed-persist")
        pc.seedUnsetPlatforms(pm())
        pc.reloadCoreMappings()
        assertEquals(EmulatorChoice(EmulatorSource.Standalone, appPackage = snesApp), pc.getPlatformChoice("SNES"))
    }
}
