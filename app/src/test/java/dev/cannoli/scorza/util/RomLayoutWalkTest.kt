package dev.cannoli.scorza.util

import android.content.res.AssetManager
import dev.cannoli.scorza.di.CannoliPathsProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** The walker reads a system's folder and file types from its [RomLayout], which systems.json backs. */
class RomLayoutWalkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var romsDir: File
    private lateinit var paths: CannoliPathsProvider
    private lateinit var assets: AssetManager
    private lateinit var arcade: ArcadeTitleLookup

    @Before
    fun setUp() {
        val root = tempFolder.root
        romsDir = File(root, "Roms").apply { mkdirs() }
        assets = mockk()
        every { assets.open(any()) } throws FileNotFoundException()
        paths = mockk()
        every { paths.root } returns root
        every { paths.romDir } returns romsDir
        arcade = mockk()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs
    }

    private fun walker(folders: Map<String, String> = emptyMap(), extensions: Map<String, Set<String>> = emptyMap()) =
        RomDirectoryWalker(paths, assets, arcade, object : RomLayout {
            override fun folderFor(tag: String) = folders[tag] ?: tag
            override fun extensionsFor(tag: String) = extensions[tag]
        })

    private fun write(relativePath: String) {
        File(romsDir, relativePath).apply { parentFile?.mkdirs() }.writeText("x")
    }

    @Test
    fun `a system reads its own folder and keys games by it`() {
        write("Game Boy Advance/Metroid.gba")
        write("GBA/Elsewhere.gba")

        val result = walker(folders = mapOf("GBA" to "Game Boy Advance")).walk("GBA", isArcade = false)!!

        assertEquals(listOf("Game Boy Advance${File.separator}Metroid.gba"), result.roms.map { it.relativePath })
        assertEquals("Game Boy Advance", result.tagDir.name)
    }

    @Test
    fun `the folder is matched without regard to case`() {
        write("game boy advance/Metroid.gba")
        val w = walker(folders = mapOf("GBA" to "Game Boy Advance"))
        assertEquals("game boy advance", w.resolveTagDir("GBA")!!.name)
    }

    @Test
    fun `a system with no folder on disk walks to nothing`() {
        assertNull(walker(folders = mapOf("GBA" to "Game Boy Advance")).walk("GBA", isArcade = false))
    }

    @Test
    fun `a system that lists file types skips every other file`() {
        write("GBA/Metroid.gba")
        write("GBA/Metroid.sav")
        write("GBA/notes.txt")
        write("GBA/Sub/Zelda.GBA")

        val result = walker(extensions = mapOf("GBA" to setOf("gba"))).walk("GBA", isArcade = false)!!

        assertEquals(
            setOf("GBA${File.separator}Metroid.gba", "GBA${File.separator}Sub${File.separator}Zelda.GBA"),
            result.roms.map { it.relativePath }.toSet(),
        )
    }

    @Test
    fun `without file types every file but the ignore list counts`() {
        write("GBA/Metroid.gba")
        write("GBA/notes.txt")

        val result = walker().walk("GBA", isArcade = false)!!

        assertEquals(2, result.roms.size)
    }

    @Test
    fun `a disc folder counts only when its launch file is an accepted type`() {
        write("PS/Game/Game.cue")
        write("PS/Game/Game.bin")
        write("PS/Other/Other.chd")

        val result = walker(extensions = mapOf("PS" to setOf("cue"))).walk("PS", isArcade = false)!!

        assertEquals(listOf("PS${File.separator}Game${File.separator}Game.cue"), result.roms.map { it.relativePath })
    }
}
