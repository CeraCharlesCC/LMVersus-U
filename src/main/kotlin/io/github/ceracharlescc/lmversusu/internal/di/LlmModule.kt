package io.github.ceracharlescc.lmversusu.internal.di

import dagger.Binds
import dagger.Module
import io.github.ceracharlescc.lmversusu.internal.domain.port.LlmOpponent
import io.github.ceracharlescc.lmversusu.internal.domain.repository.LlmTranscriptRepository
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.FileLlmTranscriptRepositoryImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.llm.LightweightLlmOpponentImpl
import javax.inject.Singleton


@Module
internal interface LlmModule {

    @Binds
    @Singleton
    fun bindTranscriptRepository(impl: FileLlmTranscriptRepositoryImpl): LlmTranscriptRepository

    @Binds
    @Singleton
    fun bindLlmOpponent(impl: LightweightLlmOpponentImpl): LlmOpponent
}