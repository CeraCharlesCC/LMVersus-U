package io.github.ceracharlescc.versuslm.internal.di

import dagger.Module
import dagger.Provides
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import javax.inject.Singleton

@Module
internal object AppModule {

    @Provides
    @Singleton
    fun provideLogger(): Logger = LoggerFactory.getLogger("VersusLM")

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}