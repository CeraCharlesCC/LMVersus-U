package io.github.ceracharlescc.lmversusu.internal.di

import dagger.Module
import dagger.Provides
import io.github.ceracharlescc.lmversusu.internal.di.annotation.ConfigDirectory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import javax.inject.Singleton

@Module
internal object AppModule {

    @Provides
    @Singleton
    fun provideLogger(): Logger = LoggerFactory.getLogger("LMVersus-U")

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    @ConfigDirectory
    fun provideConfigDirectory(): Path {
        val configDirProperty = System.getProperty("lmversusu.configDir")
        return if (!configDirProperty.isNullOrBlank()) {
            Paths.get(configDirProperty)
        } else {
            Paths.get("").toAbsolutePath()
        }
    }
}