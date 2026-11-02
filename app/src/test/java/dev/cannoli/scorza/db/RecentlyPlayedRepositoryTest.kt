package dev.cannoli.scorza.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.model.AppType
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.util.ArtworkLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentlyPlayedRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: CannoliDatabase
    private lateinit var apps: AppsRepository
    private var includeApps = true

    @Before fun setUp() {
        val root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.sdCardRoot = root.absolutePath
        val paths = CannoliPathsProvider(settings)
        db = CannoliDatabase(paths)
        apps = AppsRepository(db, ArtworkLookup(paths))
    }

    @Test fun `an opened app is listed only while apps are included`() {
        val recents = RecentlyPlayedRepository(db) { includeApps }
        val id = apps.upsert(AppType.TOOL, "Settings", "com.android.settings")
        recents.record(LibraryRef.App(id))

        assertTrue(recents.hasAny())
        assertEquals(listOf<LibraryRef>(LibraryRef.App(id)), recents.recent().map { it.ref })

        includeApps = false
        assertFalse(recents.hasAny())
        assertTrue(recents.recent().isEmpty())

        // Hidden, not forgotten: turning apps back on brings the entry back.
        includeApps = true
        assertEquals(listOf<LibraryRef>(LibraryRef.App(id)), recents.recent().map { it.ref })
    }
}
