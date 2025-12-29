package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.source

import io.github.ceracharlescc.lmversusu.internal.application.port.RoundContext
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ProviderConfig
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao.OpenAIApiDao
import kotlinx.coroutines.flow.Flow
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class PremiumPlayerSource @Inject constructor(
    private val logger: Logger
) {
    private val daoByProvider = ConcurrentHashMap<ProviderConfig, OpenAIApiDao>()

    private fun daoFor(spec: OpponentSpec.Premium): OpenAIApiDao {
        val provider = spec.provider
        require(provider.apiKey.isNotBlank()) { "Premium provider apiKey is blank for specId=${spec.id}" }

        return daoByProvider.computeIfAbsent(provider) {
            OpenAIApiDao(
                logger = logger,
                providerName = provider.providerName,
                apiKey = provider.apiKey,
                apiUrl = provider.apiUrl,
                compat = provider.compat,
            )
        }
    }

    fun stream(context: RoundContext, spec: OpponentSpec.Premium): Flow<LlmStreamEvent> {
        val dao = daoFor(spec)
        return dao.streamAnswer(
            model = spec.llmProfile.modelName,
            prompt = context.questionPrompt,
            choices = context.choices,
            temperature = spec.llmProfile.temperature,
            maxTokens = spec.llmProfile.maxTokens,
        )
    }

    suspend fun get(context: RoundContext, spec: OpponentSpec.Premium): LlmAnswer {
        val dao = daoFor(spec)
        return dao.getAnswer(
            model = spec.llmProfile.modelName,
            prompt = context.questionPrompt,
            choices = context.choices,
            temperature = spec.llmProfile.temperature,
            maxTokens = spec.llmProfile.maxTokens,
        )
    }
}
