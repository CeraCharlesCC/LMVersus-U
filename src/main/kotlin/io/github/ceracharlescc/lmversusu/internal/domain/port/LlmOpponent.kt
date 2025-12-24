package io.github.ceracharlescc.lmversusu.internal.domain.port

import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmRoundContext
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmStreamEvent

internal interface LlmOpponent {
    suspend fun respond(
        context: LlmRoundContext,
        sink: LlmStreamSink
    )
}

internal fun interface LlmStreamSink {
    suspend fun emit(event: LlmStreamEvent)
}