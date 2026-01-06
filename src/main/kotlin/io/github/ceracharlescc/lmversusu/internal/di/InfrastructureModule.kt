package io.github.ceracharlescc.lmversusu.internal.di

import dagger.Binds
import dagger.Module
import io.github.ceracharlescc.lmversusu.internal.application.port.*
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.repository.PlayerActiveSessionRepository
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.infrastructure.game.InMemoryGameEventBusImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.i18n.FileQuestionLocalizerImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.gateway.LlmPlayerGatewayImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.FileQuestionBankImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.InMemoryPlayerActiveSessionRepository
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.InMemoryResultsRepositoryImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.JsonOpponentSpecRepositoryImpl
import io.github.ceracharlescc.lmversusu.internal.infrastructure.verification.AnswerVerifierImpl
import javax.inject.Singleton

@Module
internal interface InfrastructureModule {
    @Binds
    @Singleton
    fun bindGameEventBus(impl: InMemoryGameEventBusImpl): GameEventBus

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
    fun bindQuestionBank(impl: FileQuestionBankImpl): QuestionBank

    @Binds
    @Singleton
    fun bindAnswerVerifier(impl: AnswerVerifierImpl): AnswerVerifier

    @Binds
    @Singleton
    fun bindQuestionLocalizer(impl: FileQuestionLocalizerImpl): QuestionLocalizer

    @Binds
    @Singleton
    fun bindPlayerActiveSessionRepository(impl: InMemoryPlayerActiveSessionRepository): PlayerActiveSessionRepository
}
