package io.github.ceracharlescc.lmversusu.internal.di

import dagger.Binds
import dagger.Module
import io.github.ceracharlescc.lmversusu.internal.application.port.LlmPlayerGateway
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.gateway.LlmPlayerGatewayImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.source.LightweightPlayerSource
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.InMemoryResultsRepositoryImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.JsonOpponentSpecRepositoryImpl
import javax.inject.Singleton

@Module
internal interface InfrastructureModule {
    @Binds
    @Singleton
    fun bindResultsRepository(impl: InMemoryResultsRepositoryImpl): ResultsRepository

    @Binds
    @Singleton
    fun bindOpponentSpecRepository(impl: JsonOpponentSpecRepositoryImpl): OpponentSpecRepository

    @Binds
    @Singleton
    fun bindLlmPlayerGateway(impl: LlmPlayerGatewayImpl): LlmPlayerGateway

    @Binds
    @Singleton
    fun bindLightWeightPlayerSource(impl: LightweightPlayerSource): LightweightPlayerSource

    @Binds
    @Singleton
    fun bindPremiumPlayerSource(impl: LightweightPlayerSource): LightweightPlayerSource
}