package dev.cannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.FoldersConfig
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.config.library.LibraryConfig
import java.io.IOException
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScanModule {

    @Provides @Singleton
    fun provideLibraryConfig(paths: CannoliPathsProvider, @ApplicationContext context: Context): LibraryConfig =
        LibraryConfig({ CannoliPaths(paths.root).configDir }) { name ->
            try {
                context.assets.open(name).use { it.bufferedReader().readText() }
            } catch (_: IOException) {
                null
            }
        }

    @Provides @Singleton
    fun providePlatformConfig(libraryConfig: LibraryConfig): PlatformConfig =
        PlatformConfig { libraryConfig.library }

    @Provides @Singleton
    fun provideFoldersConfig(paths: CannoliPathsProvider): FoldersConfig =
        FoldersConfig { CannoliPaths(paths.root).foldersJson }
}
