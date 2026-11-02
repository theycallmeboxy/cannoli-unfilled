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
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LaunchModule {

    @Provides @Singleton
    fun provideRetroArchLauncher(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
    ): RetroArchLauncher = RetroArchLauncher(context) { settings.retroArchPackage }

    @Provides @Singleton
    fun provideLaunchManager(
        @ApplicationContext context: Context,
        settings: SettingsRepository,
        platformConfig: PlatformConfig,
        retroArchLauncher: RetroArchLauncher,
        apkLauncher: ApkLauncher,
        launchState: LaunchState,
        installedCoreService: InstalledCoreService,
        gameOverrides: dev.cannoli.scorza.db.GameOverrideStore,
        foldersConfig: dev.cannoli.scorza.config.FoldersConfig,
    ): LaunchManager = LaunchManager(
        context, settings, platformConfig,
        retroArchLauncher, apkLauncher, launchState, installedCoreService, gameOverrides, foldersConfig,
    )
}
