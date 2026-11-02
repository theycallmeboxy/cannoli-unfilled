package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.config.AppConfig
import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchManagerStandalonePickTest {

    @get:Rule val tmp = TemporaryFolder()

    private val platformConfig = mockk<PlatformConfig>(relaxed = true)
    private val apkLauncher = mockk<ApkLauncher>(relaxed = true)
    private val retroArchLauncher = mockk<RetroArchLauncher>(relaxed = true)
    private val gameOverrides = mockk<dev.cannoli.scorza.db.GameOverrideStore>(relaxed = true)

    private fun rom(root: File): Rom {
        val romFile = File(root, "roms/3ds/Zelda.3ds").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = romFile, platformTag = "3DS", displayName = "Zelda")
    }

    private fun gcRom(root: File): Rom {
        val romFile = File(root, "roms/GC/Mario.iso").apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 2L, path = romFile, platformTag = "GC", displayName = "Mario")
    }

    private fun manager(root: File): LaunchManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns root.absolutePath
        every { gameOverrides.get(any()) } returns null
        every { platformConfig.getPlatformChoice("3DS") } returns
            EmulatorChoice(EmulatorSource.Standalone)
        // GC has no default core and no stored runner, so launch falls through to the
        // standalone app list.
        every { platformConfig.getCoreName("GC") } returns null
        every { platformConfig.getPlatformChoice("GC") } returns null
        every { apkLauncher.launchWithRom(any(), any(), any(), any()) } returns LaunchResult.Success
        return LaunchManager(
            context = mockk(relaxed = true),
            settings = settings,
            platformConfig = platformConfig,
            retroArchLauncher = retroArchLauncher,
            apkLauncher = apkLauncher,
            launchState = mockk(relaxed = true),
            gameOverrides = gameOverrides,
        )
    }

    private fun launchedPackage(): String {
        val pkg = slot<String>()
        verify { apkLauncher.launchWithRom(capture(pkg), any(), any(), any()) }
        return pkg.captured
    }

    @Test fun `explicit standalone pick wins over the first installed app`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getUserAppMapping("3DS") } returns "com.picked.emu"
        every { platformConfig.getAppConfig("3DS", "com.picked.emu") } returns AppConfig("com.picked.emu")
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns AppConfig("org.first.listed")

        mgr.launchRom(rom(root))

        assertEquals("com.picked.emu", launchedPackage())
    }

    @Test fun `without a pick launch still auto routes to the first installed app`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getUserAppMapping("3DS") } returns null
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns AppConfig("org.first.listed")

        mgr.launchRom(rom(root))

        assertEquals("org.first.listed", launchedPackage())
    }

    @Test fun `with no core and no stored runner a platform launches its first installed app`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        every { platformConfig.getUserAppMapping("GC") } returns null
        every { platformConfig.getFirstInstalledApp(any(), any()) } returns AppConfig(DOLPHIN)

        mgr.launchRom(gcRom(root))

        assertEquals(DOLPHIN, launchedPackage())
    }

    @Test fun `a per game standalone override wins and never reaches retroarch`() {
        val root = tmp.newFolder()
        val mgr = manager(root)
        val gc = gcRom(root)
        every { gameOverrides.get(gc.id) } returns
            EmulatorChoice(EmulatorSource.Standalone, appPackage = DOLPHIN)
        every { platformConfig.getAppConfig("GC", DOLPHIN) } returns AppConfig(DOLPHIN)
        every { platformConfig.getCoreName("GC") } returns "dolphin_libretro"

        mgr.launchRom(gc)

        assertEquals(DOLPHIN, launchedPackage())
        verify(exactly = 0) { retroArchLauncher.launchRetroArchIntent(any(), any(), any(), any()) }
    }

    companion object {
        private const val DOLPHIN = "org.dolphinemu.dolphinemu"
    }
}
