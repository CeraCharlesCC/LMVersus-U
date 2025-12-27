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
import kotlin.uuid.Uuid

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

    override suspend fun availableQuestionIds(opponentSpec: OpponentSpec): Set<Uuid>? =
        when (opponentSpec) {
            is OpponentSpec.Premium -> null
            is OpponentSpec.Lightweight -> lightweight.availableQuestionIds(opponentSpec)
        }

    override suspend fun declaredQuestionSetPath(opponentSpec: OpponentSpec): String? =
        when (opponentSpec) {
            is OpponentSpec.Premium -> null
            is OpponentSpec.Lightweight -> lightweight.declaredQuestionSetPath(opponentSpec)
        }
}
