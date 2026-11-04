package dev.cannoli.scorza.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.EmulatorChoiceEditor
import dev.cannoli.scorza.config.library.LibraryConfig
import dev.cannoli.scorza.config.library.LibraryParser
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.LaunchResult
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.romm.sync.RomKeys
import dev.cannoli.scorza.romm.sync.rommResolveGame
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * A system whose `roms` starts with "/" reads its games from that folder anywhere on the device,
 * keys them by their absolute path, and launches the real file. Relative systems are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbsoluteRomFolderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var romDir: File
    private lateinit var sdGba: File
    private lateinit var configDir: File
    private lateinit var config: LibraryConfig
    private lateinit var platformConfig: PlatformConfig
    private lateinit var walker: RomDirectoryWalker
    private val apkLauncher = mockk<ApkLauncher>(relaxed = true)

    private val emulators = """
        { "retroarch": { "name": "RetroArch", "package": "$RETROARCH" },
          "pizzaboy": { "name": "Pizza Boy", "package": "$PIZZABOY" } }
    """.trimIndent()

    private fun systems() = """
        { "GBA": { "roms": "${sdGba.absolutePath}/",
            "emulators": [ { "emulator": "retroarch", "core": "mgba_libretro" }, "pizzaboy" ] },
          "GB": { "emulators": [ "retroarch" ] } }
    """.trimIndent()

    @Before fun setUp() {
        romDir = tmp.newFolder("Cannoli", "Roms")
        sdGba = tmp.newFolder("storage", "1234-ABCD", "GBA")
        configDir = tmp.newFolder("Cannoli", "Config")
        File(configDir, LibraryParser.EMULATORS).writeText(emulators)
        File(configDir, LibraryParser.SYSTEMS).writeText(systems())
        config = LibraryConfig({ configDir }) { null }.also { it.load() }
        platformConfig = PlatformConfig { config.library }

        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns File(tmp.root, "Cannoli")
        every { paths.romDir } returns romDir
        val arcade = mockk<ArcadeTitleLookup>()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs
        walker = RomDirectoryWalker(paths, arcade, platformConfig)
        every { apkLauncher.launchWithRom(any(), any(), any(), any()) } returns LaunchResult.Success
    }

    private fun write(file: File): File = file.apply { parentFile!!.mkdirs(); writeText("x") }

    private fun launcher(): LaunchManager {
        val pm = mockk<PackageManager>()
        every { pm.getPackageInfo(any<String>(), any<Int>()) } returns PackageInfo()
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns pm
        return LaunchManager(
            context = context,
            settings = mockk<SettingsRepository>(relaxed = true),
            platformConfig = platformConfig,
            apkLauncher = apkLauncher,
            launchState = LaunchState(),
            romDir = { romDir },
        )
    }

    /** What the database would hand back for a scanned row: the key resolved to a file. */
    private fun romFromKey(tag: String, key: String) =
        Rom(id = 1L, path = RomPaths.resolve(romDir, key), platformTag = tag, displayName = File(key).nameWithoutExtension)

    @Test fun `the absolute folder is kept and is not a folder under Roms`() {
        assertEquals(sdGba.absolutePath, platformConfig.folderFor("GBA"))
        assertEquals(listOf("GB"), platformConfig.romFolders())
        assertEquals(listOf("GBA"), platformConfig.absoluteFolderTags())
        assertNull(platformConfig.tagForFolder("GBA"))
        assertEquals("GB", platformConfig.tagForFolder("gb"))
    }

    @Test fun `a scan keys absolute-folder games by their absolute path and relative ones as before`() {
        val metroid = write(File(sdGba, "Metroid.gba"))
        write(File(sdGba, "Hacks/Zelda.gba"))
        write(File(romDir, "GBA/Decoy.gba"))
        write(File(romDir, "GB/Tetris.gb"))

        val gba = walker.walk("GBA", isArcade = false)!!
        assertEquals(sdGba.absolutePath, gba.tagDir.absolutePath)
        assertEquals(
            setOf(metroid.absolutePath, File(sdGba, "Hacks/Zelda.gba").absolutePath),
            gba.roms.map { it.relativePath }.toSet(),
        )
        assertEquals(listOf("GB${File.separator}Tetris.gb"), walker.walk("GB", isArcade = false)!!.roms.map { it.relativePath })

        // Each stored key finds its file again, and the launcher's key for that file is the same.
        for (scanned in gba.roms) {
            val file = RomPaths.resolve(romDir, scanned.relativePath)
            assertTrue(file.isFile)
            assertEquals(scanned.relativePath, RomKeys.relativeKey(file, romDir))
        }
        assertEquals("GB${File.separator}Tetris.gb", RomKeys.relativeKey(File(romDir, "GB/Tetris.gb"), romDir))
    }

    @Test fun `a loose game in an absolute folder is not a game folder`() {
        val single = write(File(sdGba, "Only.m3u"))
        write(File(sdGba, "Hacks/Zelda.gba"))
        assertNull(walker.gameDirectory(single))
        assertEquals(listOf("Hacks"), walker.categoryFolders("GBA", isArcade = false))
    }

    @Test fun `a game in an absolute folder launches with its real file`() {
        write(File(sdGba, "Metroid.gba"))
        val key = walker.walk("GBA", isArcade = false)!!.roms.single().relativePath

        assertNull(launcher().launchRom(romFromKey("GBA", key)))

        val file = slot<File>()
        val vars = slot<Map<String, String>>()
        verify { apkLauncher.launchWithRom(RETROARCH, capture(file), any(), capture(vars)) }
        assertEquals(File(sdGba, "Metroid.gba").absolutePath, file.captured.absolutePath)
        assertEquals(mapOf("core" to "mgba_libretro"), vars.captured)
    }

    @Test fun `an override for an absolute-folder game is keyed by its absolute path and round trips`() {
        val game = write(File(sdGba, "Golden Sun.gba"))
        val key = RomKeys.relativeKey(game, romDir)
        assertEquals(game.absolutePath, key)

        val gba = config.library.systems.getValue("GBA")
        assertNull(EmulatorChoiceEditor(config).setGameChoice(gba, key, "pizzaboy"))

        val saved = JSONObject(File(configDir, LibraryParser.OVERRIDES).readText()).getJSONObject("games")
        assertEquals("pizzaboy", saved.getString(game.absolutePath))
        // Read back from disk, it survives the parser and the validator.
        val reloaded = LibraryConfig({ configDir }) { null }.also { it.load() }
        assertEquals(mapOf(game.absolutePath to "pizzaboy"), reloaded.library.overrides)
        assertTrue(reloaded.problems.toString(), reloaded.problems.none { it.message.contains(LibraryParser.OVERRIDES) })

        assertNull(launcher().launchRom(romFromKey("GBA", key)))
        verify { apkLauncher.launchWithRom(PIZZABOY, any(), any(), any()) }
    }

    @Test fun `save sync leaves absolute-folder games out`() {
        val game = write(File(sdGba, "Metroid.gba"))
        write(File(romDir, "GB/Tetris.gb"))
        val resolve = rommResolveGame(platformConfig, romDir)

        assertNull(resolve(RomKeys.relativeKey(game, romDir)))
        assertEquals("GB", resolve("GB/Tetris.gb")!!.first)
    }

    @Test fun `an absolute folder that is missing walks to nothing`() {
        sdGba.deleteRecursively()
        assertNull(walker.walk("GBA", isArcade = false))
        assertFalse(walker.tagDirFor("GBA").exists())
        assertEquals(sdGba.absolutePath, walker.tagDirFor("GBA").absolutePath)
    }

    private companion object {
        const val RETROARCH = "com.retroarch.aarch64"
        const val PIZZABOY = "it.dbtecno.pizzaboygba"
    }
}
