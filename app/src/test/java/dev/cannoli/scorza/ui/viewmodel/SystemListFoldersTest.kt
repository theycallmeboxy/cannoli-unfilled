package dev.cannoli.scorza.ui.viewmodel

import dev.cannoli.scorza.config.FoldersConfig
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.db.ScanScheduler
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.App
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.model.Platform
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

/** The main menu shows folders.json: top-level folders beside the platforms, and any folder's
 *  contents, nested to any depth, when the user opens it. */
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

    private val folders = """
        { "folders": [
          { "name": "Nintendo", "items": [
              { "platform": "GBA" },
              { "folder": { "name": "Handhelds", "items": [
                  { "platform": "GB" },
                  { "folder": { "name": "Deeper", "items": [ { "app": "org.example.deep", "name": "Deep App" } ] } }
              ] } },
              { "app": "org.example.tool", "name": "A Tool" }
          ] },
          { "name": "Empty", "items": [] }
        ] }
    """.trimIndent()

    private fun viewModel(
        artwork: dev.cannoli.scorza.util.ArtworkLookup = mockk(relaxed = true),
    ): SystemListViewModel {
        val counts = mapOf("GBA" to 3, "GB" to 2, "SNES" to 5)
        val romsRepository = mockk<RomsRepository>(relaxed = true)
        every { romsRepository.platformCounts() } returns counts
        every { romsRepository.knownPlatformTags() } returns counts.keys.toList()

        val paths = mockk<CannoliPathsProvider>()
        every { paths.romDir } returns File(tmp.root, "Roms")

        val platformConfig = mockk<PlatformConfig>(relaxed = true)
        every { platformConfig.isKnownTag(any()) } returns true
        every { platformConfig.resolvePlatform(any(), any()) } answers {
            val tag = firstArg<String>()
            Platform(tag = tag, displayName = tag, coreName = null, gameCount = secondArg())
        }

        val apps = mockk<AppsRepository>(relaxed = true)
        var nextId = 1L
        every { apps.forFolderEntry(any(), any(), any()) } answers {
            App(id = nextId++, type = AppType.TOOL, displayName = secondArg<String?>() ?: firstArg(), packageName = firstArg())
        }

        val scheduler = mockk<ScanScheduler>(relaxed = true)
        every { scheduler.results } returns MutableSharedFlow()

        val file = File(tmp.root, "folders.json").apply { writeText(folders) }
        val config = FoldersConfig { file }.also { it.load() }

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
            foldersConfig = config,
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
            else -> it::class.simpleName.orEmpty()
        }
    }

    @Test fun `the main menu lists top level folders and hides platforms placed in a folder`() {
        val vm = viewModel()
        await { vm.scan(scanDisk = false, onReady = it) }
        val items = labels(vm)
        assertTrue("platform:SNES" in items)
        assertTrue("folder:Nintendo" in items)
        assertTrue("an empty folder still shows", "folder:Empty" in items)
        assertFalse("GBA lives in Nintendo", "platform:GBA" in items)
        assertFalse("GB lives in a nested folder", "platform:GB" in items)
        assertFalse(vm.isInFolder())
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

    @Test fun `reorder is not offered inside a folder`() {
        val vm = viewModel()
        await { vm.scan(scanDisk = false, onReady = it) }
        await { vm.enterFolder("Nintendo", it) }
        vm.enterReorderMode()
        assertFalse(vm.isReorderMode())
    }
}
