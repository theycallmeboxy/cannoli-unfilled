package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.Library
import dev.cannoli.scorza.config.library.LibraryParser
import dev.cannoli.scorza.config.library.LibraryValidator
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A game launches with what systems.json and overrides.json give it, through ApkLauncher. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val apkLauncher = mockk<ApkLauncher>(relaxed = true)
    private val cores = mockk<InstalledCoreService>()
    private val pm = mockk<PackageManager>()
    private val installed = mutableSetOf(RETROARCH, PIZZABOY)
    private lateinit var romDir: File

    private val emulators = """
        {
          "retroarch": { "name": "RetroArch", "package": "$RETROARCH",
            "extras": [ { "key": "LIBRETRO", "kind": "string", "value": "/cores/{core}_android.so" } ] },
          "pizzaboy": { "name": "Pizza Boy", "package": "$PIZZABOY" }
        }
    """.trimIndent()

    private val systems = """
        {
          "GBA": { "name": "Game Boy Advance", "roms": "Game Boy Advance",
            "emulators": [ { "emulator": "retroarch", "core": "mgba_libretro" }, "pizzaboy" ] },
          "NES": { "name": "Nintendo" }
        }
    """.trimIndent()

    @Before fun setUp() {
        romDir = tmp.newFolder("Roms")
        every { pm.getPackageInfo(any<String>(), any<Int>()) } answers {
            if (firstArg<String>() in installed) PackageInfo() else throw PackageManager.NameNotFoundException()
        }
        every { cores.coreStatus(any(), any()) } returns CoreStatus.UNKNOWN
        every { apkLauncher.launchWithRom(any(), any(), any(), any()) } returns LaunchResult.Success
    }

    private fun manager(overrides: String = """{ "games": {} }""", state: LaunchState = LaunchState()): LaunchManager {
        val library: Library = LibraryValidator.validate(
            LibraryParser.parseEmulators(emulators).value,
            LibraryParser.parseSystems(systems).value,
            emptyMap(),
            emptyList(),
            LibraryParser.parseOverrides(overrides).value,
        ).library
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.getString(any(), any()) } answers { "no emulator: ${secondArg<Array<Any>>().joinToString()}" }
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.sdCardRoot } returns tmp.root.absolutePath
        return LaunchManager(
            context = context,
            settings = settings,
            platformConfig = PlatformConfig { library },
            apkLauncher = apkLauncher,
            launchState = state,
            romDir = { romDir },
            installedCoreService = cores,
        )
    }

    private fun rom(tag: String, relative: String): Rom {
        val file = File(romDir, relative).apply { parentFile!!.mkdirs(); writeText("x") }
        return Rom(id = 1L, path = file, platformTag = tag, displayName = file.nameWithoutExtension)
    }

    private val metroid get() = rom("GBA", "Game Boy Advance/Metroid.gba")

    @Test fun `a game launches with its system's first emulator and its core`() {
        assertNull(manager().launchRom(metroid))

        val pkg = slot<String>()
        val vars = slot<Map<String, String>>()
        verify { apkLauncher.launchWithRom(capture(pkg), any(), any(), capture(vars)) }
        assertEquals(RETROARCH, pkg.captured)
        assertEquals(mapOf("core" to "mgba_libretro"), vars.captured)
    }

    @Test fun `an override in overrides_json picks the game's emulator`() {
        val mgr = manager("""{ "games": { "Game Boy Advance/Metroid.gba": "pizzaboy" } }""")
        assertNull(mgr.launchRom(metroid))

        val pkg = slot<String>()
        verify { apkLauncher.launchWithRom(capture(pkg), any(), any(), any()) }
        assertEquals(PIZZABOY, pkg.captured)
    }

    @Test fun `a system with no emulator says so instead of launching`() {
        val dialog = manager().launchRom(rom("NES", "NES/Mario.nes"))

        assertTrue(dialog is DialogState.LaunchError)
        assertTrue((dialog as DialogState.LaunchError).message.contains("Nintendo"))
        verify(exactly = 0) { apkLauncher.launchWithRom(any(), any(), any(), any()) }
    }

    @Test fun `an emulator that is not installed shows the missing app dialog`() {
        installed.remove(RETROARCH)

        val dialog = manager().launchRom(metroid)

        assertEquals(DialogState.MissingApp("RetroArch", RETROARCH), dialog)
        verify(exactly = 0) { apkLauncher.launchWithRom(any(), any(), any(), any()) }
    }

    @Test fun `a core the emulator reports missing shows the missing core dialog`() {
        every { cores.coreStatus(RETROARCH, "mgba_libretro") } returns CoreStatus.MISSING

        val dialog = manager().launchRom(metroid)

        assertEquals(DialogState.MissingCore("mgba_libretro", "RetroArch"), dialog)
        verify(exactly = 0) { apkLauncher.launchWithRom(any(), any(), any(), any()) }
    }

    @Test fun `an emulator that cannot report its cores still launches`() {
        every { cores.coreStatus(RETROARCH, "mgba_libretro") } returns CoreStatus.UNKNOWN
        assertNull(manager().launchRom(metroid))
        verify { apkLauncher.launchWithRom(RETROARCH, any(), any(), any()) }
    }

    @Test fun `a failed launch clears the launching flag`() {
        installed.remove(RETROARCH)
        val state = LaunchState()

        manager(state = state).launchRom(metroid)

        assertEquals(false, state.launching)
        assertNull(state.lastLaunched)
    }

    private companion object {
        const val RETROARCH = "com.retroarch.aarch64"
        const val PIZZABOY = "it.dbtecno.pizzaboygba"
    }
}
