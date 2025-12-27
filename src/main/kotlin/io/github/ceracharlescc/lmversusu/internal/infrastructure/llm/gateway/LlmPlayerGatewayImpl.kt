package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.gateway

import io.github.ceracharlescc.lmversusu.internal.application.port.LlmPlayerGateway
import io.github.ceracharlescc.lmversusu.internal.application.port.RoundContext
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.source.LightweightPlayerSource
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.source.PremiumPlayerSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LlmPlayerGatewayImpl @Inject constructor(
    private val premium: PremiumPlayerSource,
    private val lightweight: LightweightPlayerSource,
) : LlmPlayerGateway {

    override fun streamAnswer(roundContext: RoundContext): Flow<LlmStreamEvent> =
        when (val spec = roundContext.opponentSpec) {
            is OpponentSpec.Premium -> premium.stream(roundContext, spec)
            is OpponentSpec.Lightweight -> lightweight.stream(roundContext, spec)
        }

    override suspend fun getAnswer(roundContext: RoundContext): LlmAnswer =
        when (val spec = roundContext.opponentSpec) {
            is OpponentSpec.Premium -> premium.get(roundContext, spec)
            is OpponentSpec.Lightweight -> lightweight.get(roundContext, spec)
        }
}
