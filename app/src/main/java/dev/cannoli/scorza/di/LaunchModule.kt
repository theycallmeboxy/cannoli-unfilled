package dev.cannoli.scorza.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.InstalledCoreService
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LaunchModule {

    @Provides @Singleton
    fun provideLaunchManager(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        platformConfig: PlatformConfig,
        apkLauncher: ApkLauncher,
        launchState: LaunchState,
        paths: CannoliPathsProvider,
        installedCoreService: InstalledCoreService,
        foldersConfig: dev.cannoli.scorza.config.FoldersConfig,
    ): LaunchManager = LaunchManager(
        context, settings, platformConfig, apkLauncher, launchState, { paths.romDir },
        installedCoreService, foldersConfig,
    )
}
