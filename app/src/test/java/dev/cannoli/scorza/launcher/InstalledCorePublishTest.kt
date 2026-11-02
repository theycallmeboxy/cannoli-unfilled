package dev.cannoli.scorza.launcher

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.library.LibraryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * publishQueryResult is private, so these call it reflectively, which is the only way to
 * simulate a query landing without a live emulator to broadcast at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstalledCorePublishTest {

    private fun service(): InstalledCoreService {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return InstalledCoreService(ctx, LibraryConfig({ ctx.cacheDir }) { null })
    }

    private fun publish(
        svc: InstalledCoreService,
        answered: Map<String, Set<String>>,
        silent: Set<String> = emptySet(),
        unsupported: Set<String> = emptySet(),
        known: Set<String> = answered.keys + silent + unsupported,
    ) {
        val m = InstalledCoreService::class.java.getDeclaredMethod(
            "publishQueryResult", Map::class.java, Set::class.java, Set::class.java, Set::class.java,
        )
        m.isAccessible = true
        m.invoke(svc, answered, silent, unsupported, known)
    }

    @Test fun `a package that answers zero cores is installed, not unresponsive`() {
        val svc = service()
        publish(svc, answered = mapOf(OTHER to emptySet()))
        assertTrue(svc.installedCores.containsKey(OTHER))
        assertFalse("answering zero is a real answer", OTHER in svc.unresponsivePackages)
        assertTrue(svc.cacheReady)
    }

    @Test fun `a package that never answers is unresponsive`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(OTHER))
        assertTrue(OTHER in svc.unresponsivePackages)
    }

    // The blanket union used to keep a deleted core cached forever.
    @Test fun `a core removed inside the emulator drops out of the cache`() {
        val svc = service()
        publish(svc, answered = mapOf(OTHER to setOf("a_libretro", "b_libretro")))
        publish(svc, answered = mapOf(OTHER to setOf("a_libretro")))
        assertEquals(setOf("a_libretro"), svc.installedCores[OTHER])
    }

    @Test fun `a package that did not answer keeps its cached cores`() {
        val svc = service()
        publish(svc, answered = mapOf(OTHER to setOf("a_libretro")))
        publish(svc, answered = emptyMap(), silent = setOf(OTHER))
        assertEquals(setOf("a_libretro"), svc.installedCores[OTHER])
    }

    @Test fun `a package with no receiver is unsupported, not silent`() {
        val svc = service()
        publish(svc, answered = emptyMap(), unsupported = setOf(STOCK))
        assertTrue(STOCK in svc.unsupportedPackages)
        assertFalse("no receiver is a different fact from no answer", STOCK in svc.unresponsivePackages)
        assertEquals(CoreReporting.UNSUPPORTED, svc.reportingFor(STOCK))
    }

    @Test fun `a silent package reports as silent`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(STOCK))
        assertEquals(CoreReporting.SILENT, svc.reportingFor(STOCK))
    }

    @Test fun `an answering package reports as reporting`() {
        val svc = service()
        publish(svc, answered = mapOf(STOCK to setOf("a_libretro")))
        assertEquals(CoreReporting.REPORTS, svc.reportingFor(STOCK))
    }

    // This used to assert REPORTS, on the theory that an optimistic default stopped the notice
    // flashing during boot. That made "no information yet" indistinguishable from "reported",
    // so the picker labelled every core Not Installed until the boot scan landed.
    @Test fun `a package is unscanned until the first query publishes`() {
        assertEquals(CoreReporting.UNSCANNED, service().reportingFor(STOCK))
    }

    @Test fun `a package that is not installed is not mistaken for one that reported`() {
        val svc = service()
        publish(svc, answered = mapOf(OTHER to setOf("a_libretro")))
        assertEquals(CoreReporting.NOT_INSTALLED, svc.reportingFor(STOCK))
    }

    @Test fun `an uninstalled package survives a scan that found nothing at all`() {
        val svc = service()
        publish(svc, answered = emptyMap())
        assertTrue("a scan that discovers nothing still counts as a scan", svc.cacheReady)
        assertEquals(CoreReporting.NOT_INSTALLED, svc.reportingFor(STOCK))
    }

    @Test fun `a core the package reported is present`() {
        val svc = service()
        publish(svc, answered = mapOf(STOCK to setOf("mgba_libretro")))
        assertEquals(CoreStatus.PRESENT, svc.coreStatus(STOCK, "mgba_libretro"))
    }

    @Test fun `a core missing from a package that answered is missing`() {
        val svc = service()
        publish(svc, answered = mapOf(STOCK to setOf("mgba_libretro")))
        assertEquals(CoreStatus.MISSING, svc.coreStatus(STOCK, "snes9x_libretro"))
    }

    @Test fun `a package that answered with no cores reports every core missing`() {
        val svc = service()
        publish(svc, answered = mapOf(STOCK to emptySet()))
        assertEquals(CoreStatus.MISSING, svc.coreStatus(STOCK, "mgba_libretro"))
    }

    @Test fun `core status is unknown for every way of not knowing`() {
        val svc = service()
        publish(svc, answered = emptyMap(), silent = setOf(OTHER), unsupported = setOf(STOCK))
        assertEquals("silent", CoreStatus.UNKNOWN, svc.coreStatus(OTHER, "a_libretro"))
        assertEquals("no receiver", CoreStatus.UNKNOWN, svc.coreStatus(STOCK, "a_libretro"))
        assertEquals("not installed", CoreStatus.UNKNOWN, svc.coreStatus("org.example.absent", "a_libretro"))
        assertEquals("never scanned", CoreStatus.UNKNOWN, service().coreStatus(STOCK, "a_libretro"))
    }

    // A silent package keeps its cached cores, but a cache is not an answer.
    @Test fun `a package that stops answering is unknown even with cached cores`() {
        val svc = service()
        publish(svc, answered = mapOf(STOCK to setOf("a_libretro")))
        publish(svc, answered = emptyMap(), silent = setOf(STOCK))
        assertEquals(CoreStatus.UNKNOWN, svc.coreStatus(STOCK, "b_libretro"))
    }

    @Test fun `an unsupported package that gains the receiver stops being unsupported`() {
        val svc = service()
        publish(svc, answered = emptyMap(), unsupported = setOf(STOCK))
        publish(svc, answered = mapOf(STOCK to setOf("a_libretro")))
        assertFalse(STOCK in svc.unsupportedPackages)
        assertEquals(CoreReporting.REPORTS, svc.reportingFor(STOCK))
    }

    // Which packages are asked comes from emulators.json, not from a list of known names.
    @Test fun `the packages asked are the installed emulators from emulators_json`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = java.io.File(ctx.cacheDir, "core-query-config").apply { deleteRecursively(); mkdirs() }
        java.io.File(dir, "emulators.json").writeText(
            """{ "a": { "package": "org.example.installed" }, "b": { "package": "org.example.absent" } }"""
        )
        val library = LibraryConfig({ dir }) { null }.also { it.load() }
        org.robolectric.Shadows.shadowOf(ctx.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = "org.example.installed" }
        )
        val svc = InstalledCoreService(ctx, library)
        kotlinx.coroutines.runBlocking { svc.queryAllPackages() }
        assertEquals(setOf("org.example.installed"), svc.knownPackages)
        assertEquals(CoreReporting.UNSUPPORTED, svc.reportingFor("org.example.installed"))
        assertEquals(CoreReporting.NOT_INSTALLED, svc.reportingFor("org.example.absent"))
    }

    private companion object {
        const val OTHER = "org.example.emulator"
        const val STOCK = "com.retroarch.aarch64"
    }
}
