package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.DirectoryEditor
import dev.cannoli.scorza.config.library.LibraryConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The main menu shows directory.json in its own order, and any folder's contents, nested to any
 *  depth, when the user opens it. What it leaves out is listed while editing. */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemListFoldersTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun tearDown() {
        created.forEach { it.close() }
        created.clear()
        Dispatchers.resetMain()
    }

    private val created = mutableListOf<SystemListViewModel>()

    private val directory = """
        { "items": [
          { "system": "SNES" },
          { "folder": "Nintendo", "items": [
              { "system": "GBA" },
              { "folder": "Handhelds", "items": [
                  { "system": "GB" },
                  { "folder": "Deeper", "items": [ { "app": "org.example.deep", "name": "Deep App" } ] }
              ] },
              { "app": "org.example.tool", "name": "A Tool" }
          ] },
          { "system": "PCE" },
          { "shortcut": "movies" },
          { "folder": "Empty", "items": [] }
        ] }
    """.trimIndent()

    // NES has games but no place; PCE is placed but has none; N64 has neither.
    private val systems = """{ "GBA": {}, "GB": {}, "SNES": {}, "NES": { "name": "Nintendo" }, "PCE": {}, "N64": {} }"""

    private val shortcuts = """{ "movies": { "name": "Movies", "package": "org.xbmc.kodi" } }"""

    private fun viewModel(
        artwork: dev.cannoli.scorza.util.ArtworkLookup = mockk(relaxed = true),
    ): SystemListViewModel {
        val counts = mapOf("GBA" to 3, "GB" to 2, "SNES" to 5, "NES" to 4)
        val romsRepository = mockk<RomsRepository>(relaxed = true)
        every { romsRepository.platformCounts() } returns counts
        every { romsRepository.knownPlatformTags() } returns counts.keys.toList()

        val paths = mockk<CannoliPathsProvider>()
        every { paths.romDir } returns romDir

        val configDir = File(tmp.root, "Config").apply { mkdirs() }
        File(configDir, "systems.json").writeText(systems)
        File(configDir, "shortcuts.json").writeText(shortcuts)
        File(configDir, "directory.json").writeText(directory)
        val config = LibraryConfig({ configDir }) { null }.also { it.load() }
        val platformConfig = PlatformConfig { config.library }

        val apps = mockk<AppsRepository>(relaxed = true)
        var nextId = 1L
        every { apps.forDirectoryEntry(any(), any(), any()) } answers {
            App(id = nextId++, type = AppType.TOOL, displayName = secondArg<String?>() ?: firstArg(), packageName = firstArg())
        }

        val scheduler = mockk<ScanScheduler>(relaxed = true)
        every { scheduler.results } returns MutableSharedFlow()

        return SystemListViewModel(
            romsRepository = romsRepository,
            romScanner = mockk(relaxed = true),
            appsRepository = apps,
            collectionsRepository = mockk(relaxed = true),
            recentlyPlayedRepository = mockk(relaxed = true),
            platformConfig = platformConfig,
            cannoliPaths = paths,
            romDirectoryWatcher = mockk(relaxed = true),
            scanScheduler = scheduler,
            libraryConfig = config,
            directoryEditor = DirectoryEditor(config),
            installedCoreService = mockk(relaxed = true),
            artworkLookup = artwork,
            context = mockk(relaxed = true),
        ).also { created.add(it) }
    }

    private fun await(block: (() -> Unit) -> Unit) {
        val done = CountDownLatch(1)
        block { done.countDown() }
        assertTrue("view model did not finish", done.await(10, TimeUnit.SECONDS))
    }

    private fun labels(vm: SystemListViewModel): List<String> = vm.state.value.items.map {
        when (it) {
            is SystemListViewModel.ListItem.FolderItem -> "folder:${it.name}"
            is SystemListViewModel.ListItem.PlatformItem -> "platform:${it.platform.tag}"
            is SystemListViewModel.ListItem.GameItem -> "app:${it.displayName}"
            is SystemListViewModel.ListItem.UnplacedRow -> "unplaced:${it.entry}"
            else -> it::class.simpleName.orEmpty()
        }
    }

    private val romDir: File get() = File(tmp.root, "Roms")

    @Test fun `the main menu follows the directory and leaves out what it doesn't place`() {
        val vm = viewModel()
        await { vm.scan(scanDisk = false, onReady = it) }
        assertEquals(
            "PCE has no games, NES has no place; Quick Menu and Settings always close the main menu",
            listOf("platform:SNES", "folder:Nintendo", "app:Movies", "folder:Empty", "QuickMenuItem", "SettingsItem"),
            labels(vm),
        )
        assertFalse(vm.isInFolder())
    }

    @Test fun `unplaced systems with games and unclaimed ROM folders are listed while reordering`() {
        File(romDir, "Unknown/sub").mkdirs()
        File(romDir, "Unknown/a.bin").writeText("x")
        File(romDir, "Unknown/sub/b.bin").writeText("x")
        File(romDir, "EmptyFolder").mkdirs()
        File(romDir, "GBA").mkdirs()
        File(romDir, "GBA/game.gba").writeText("x")
        val vm = viewModel()
        await { vm.scan(scanDisk = false, onReady = it) }
        val unplaced = listOf(
            SystemListViewModel.Unplaced.System("NES", "Nintendo", 4),
            SystemListViewModel.Unplaced.RomFolder("Unknown", 2),
        )
        assertEquals(unplaced, vm.state.value.unplaced)

        vm.enterReorderMode()
        val items = vm.state.value.items
        assertEquals(SystemListViewModel.ListItem.UnplacedHeader, items[items.size - 3])
        assertEquals(unplaced.map { SystemListViewModel.ListItem.UnplacedRow(it) }, items.takeLast(2))
        repeat(10) { vm.reorderMoveDown() }
        assertTrue(
            "the moving row stops above the unplaced list",
            vm.state.value.selectedIndex < items.size - 3,
        )
        vm.confirmReorder()
        assertFalse(vm.state.value.items.any { it is SystemListViewModel.ListItem.UnplacedRow })
    }

    @Test fun `folders open and close at any depth`() {
        val vm = viewModel()
        await { vm.scan(scanDisk = false, onReady = it) }

        await { vm.enterFolder("Nintendo", it) }
        assertEquals(listOf("platform:GBA", "folder:Handhelds", "app:A Tool"), labels(vm))
        assertEquals(listOf("Nintendo"), vm.state.value.folderPath)

        await { vm.enterFolder("Handhelds", it) }
        assertEquals(listOf("platform:GB", "folder:Deeper"), labels(vm))

        await { vm.enterFolder("Deeper", it) }
        assertEquals(listOf("app:Deep App"), labels(vm))
        assertEquals(listOf("Nintendo", "Handhelds", "Deeper"), vm.state.value.folderPath)

        await { assertTrue(vm.exitFolder(it)) }
        assertEquals(listOf("platform:GB", "folder:Deeper"), labels(vm))
        assertEquals("backing out lands on the folder that was opened", 1, vm.state.value.selectedIndex)

        await { assertTrue(vm.exitFolder(it)) }
        await { assertTrue(vm.exitFolder(it)) }
        assertFalse(vm.isInFolder())
        assertFalse(vm.exitFolder())
        assertTrue("folder:Nintendo" in labels(vm))
    }

    @Test fun `reordering the main menu writes the directory's order`() {
        val vm = viewModel()
        await { vm.scan(scanDisk = false, onReady = it) }
        vm.setSelectedIndex(0)
        vm.enterReorderMode()
        vm.reorderMoveDown()
        vm.confirmReorder()
        assertEquals(listOf("folder:Nintendo", "platform:SNES", "app:Movies", "folder:Empty", "QuickMenuItem", "SettingsItem"), labels(vm))
        assertEquals(
            "PCE, hidden for want of games, goes after the rest",
            listOf("folder", "system", "shortcut", "folder", "system"),
            writtenOrder(emptyList(), "folder"),
        )
    }

    // directory.json is written off the main thread, so wait for it to start with [first].
    private fun writtenOrder(path: List<String>, first: String): List<String> {
        val file = File(tmp.root, "Config/directory.json")
        val deadline = System.currentTimeMillis() + 5000
        var order: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            var items = org.json.JSONObject(file.readText()).getJSONArray("items")
            for (name in path) {
                items = (0 until items.length()).map { items.getJSONObject(it) }
                    .first { it.optString("folder") == name }.getJSONArray("items")
            }
            order = (0 until items.length()).map { items.getJSONObject(it).keys().next() }
            if (order.firstOrNull() == first) break
            Thread.sleep(20)
        }
        return order
    }

    @Test fun `folder art is found under Art Folders nested like the folders`() {
        val root = tmp.newFolder("Cannoli")
        val top = File(root, "Art/Folders/Nintendo.png").apply { parentFile!!.mkdirs(); writeText("png") }
        val nested = File(root, "Art/Folders/Nintendo/Handhelds.png").apply { parentFile!!.mkdirs(); writeText("png") }
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        val vm = viewModel(dev.cannoli.scorza.util.ArtworkLookup(paths))

        await { vm.scan(scanDisk = false, onReady = it) }
        val nintendo = vm.state.value.items.filterIsInstance<SystemListViewModel.ListItem.FolderItem>().single { it.name == "Nintendo" }
        assertEquals(top, nintendo.artFile)
        val empty = vm.state.value.items.filterIsInstance<SystemListViewModel.ListItem.FolderItem>().single { it.name == "Empty" }
        assertEquals(null, empty.artFile)

        await { vm.enterFolder("Nintendo", it) }
        val handhelds = vm.state.value.items.filterIsInstance<SystemListViewModel.ListItem.FolderItem>().single()
        assertEquals(nested, handhelds.artFile)
    }

    @Test fun `reordering inside a folder writes the new order to directory_json`() {
        val vm = viewModel()
        await { vm.scan(scanDisk = false, onReady = it) }
        await { vm.enterFolder("Nintendo", it) }
        vm.setSelectedIndex(2)
        vm.enterReorderMode()
        assertTrue(vm.isReorderMode())
        vm.reorderMoveUp()
        vm.reorderMoveUp()
        vm.confirmReorder()
        assertEquals(listOf("app:A Tool", "platform:GBA", "folder:Handhelds"), labels(vm))


        assertEquals(listOf("app", "system", "folder"), writtenOrder(listOf("Nintendo"), "app"))
    }
}
