package io.github.ceracharlescc.lmversusu.internal.di

import dagger.Binds
import dagger.Module
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.InMemoryResultsRepositoryImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.spec.JsonOpponentSpecRepositoryImpl
import javax.inject.Singleton

@Module
internal interface RepositoryModule {
    @Binds
    @Singleton
    fun bindResultsRepository(impl: InMemoryResultsRepositoryImpl): ResultsRepository

    @Binds
    @Singleton
    fun bindOpponentSpecRepository(impl: JsonOpponentSpecRepositoryImpl): OpponentSpecRepository
}