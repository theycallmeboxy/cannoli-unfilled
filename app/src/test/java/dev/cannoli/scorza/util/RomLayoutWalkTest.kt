package dev.cannoli.scorza.util

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

/** The walker reads a system's folder and file types from its [RomLayout], which systems.json backs. */
class RomLayoutWalkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var romsDir: File
    private lateinit var paths: CannoliPathsProvider
    private lateinit var arcade: ArcadeTitleLookup

    @Before
    fun setUp() {
        val root = tempFolder.root
        romsDir = File(root, "Roms").apply { mkdirs() }
        paths = mockk()
        every { paths.root } returns root
        every { paths.romDir } returns romsDir
        arcade = mockk()
        every { arcade.mapFor(any(), any()) } returns emptyMap()
        every { arcade.invalidate(any()) } just Runs
    }

    private fun walker(folders: Map<String, String> = emptyMap(), extensions: Map<String, Set<String>> = emptyMap()) =
        RomDirectoryWalker(paths, arcade, object : RomLayout {
            override fun folderFor(tag: String) = folders[tag] ?: tag
            override fun extensionsFor(tag: String) = extensions[tag]
        })

    private fun write(relativePath: String, text: String = "x") {
        File(romsDir, relativePath).apply { parentFile?.mkdirs() }.writeText(text)
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
    fun `without file types every file but hidden ones counts`() {
        write("GBA/Metroid.gba")
        write("GBA/notes.txt")
        write("GBA/Metroid.srm")
        write("GBA/.hidden.gba")
        write("GBA/.cache/Zelda.gba")

        val result = walker().walk("GBA", isArcade = false)!!

        assertEquals(
            setOf("GBA${File.separator}Metroid.gba", "GBA${File.separator}notes.txt", "GBA${File.separator}Metroid.srm"),
            result.roms.map { it.relativePath }.toSet(),
        )
    }

    @Test
    fun `with file types other files are not games, whatever their case`() {
        write("GBA/Metroid.GBA")
        write("GBA/Metroid.srm")
        write("GBA/Zelda.zip")
        write("GBA/gamelist.xml")

        val result = walker(extensions = mapOf("GBA" to setOf("gba", "zip"))).walk("GBA", isArcade = false)!!

        assertEquals(
            setOf("GBA${File.separator}Metroid.GBA", "GBA${File.separator}Zelda.zip"),
            result.roms.map { it.relativePath }.toSet(),
        )
    }

    @Test
    fun `a multi-disc set still shows when the system lists its discs but not m3u`() {
        write("PCFX/Game (Disc 1).cue")
        write("PCFX/Game (Disc 2).cue")

        val result = walker(extensions = mapOf("PCFX" to setOf("cue", "chd"))).walk("PCFX", isArcade = false)!!

        assertEquals(listOf("PCFX${File.separator}Game${File.separator}Game.m3u"), result.roms.map { it.relativePath })
    }

    @Test
    fun `an m3u whose discs are all of other types is not a game`() {
        write("GBA/Thing.m3u", "Thing.txt\n")
        write("GBA/Thing.txt")

        val result = walker(extensions = mapOf("GBA" to setOf("gba"))).walk("GBA", isArcade = false)!!

        assertEquals(emptyList<String>(), result.roms.map { it.relativePath })
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
