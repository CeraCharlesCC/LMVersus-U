package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository.llm

import io.github.ceracharlescc.lmversusu.internal.domain.port.LlmOpponent
import io.github.ceracharlescc.lmversusu.internal.domain.port.LlmStreamSink
import io.github.ceracharlescc.lmversusu.internal.domain.repository.LlmTranscriptRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmRoundContext
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmStreamEvent
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max

@Singleton
internal class LightweightLlmOpponentImpl @Inject constructor(
    private val transcriptRepository: LlmTranscriptRepository
) : LlmOpponent {

    override suspend fun respond(context: LlmRoundContext, sink: LlmStreamSink) {
        val transcript = transcriptRepository.find(context.questionId, context.llmProfile)
            ?: error("Missing transcript for questionId=${context.questionId}")

        val tokens = PseudoTokenizer.tokenizeKeepingWhitespace(transcript.reasoning)
        val totalTokens = max(1, tokens.size)

        val chunkSize = transcript.chunkSizeTokens.coerceAtLeast(1)
        val tps = transcript.averageTokensPerSecond.coerceAtLeast(1.0)

        var emitted = 0

        for (chunk in tokens.chunked(chunkSize)) {
            val tokenCount = chunk.size
            delay(computeDelayMillis(tokenCount, tps))

            emitted += tokenCount
            sink.emit(
                LlmStreamEvent.ReasoningDelta(
                    deltaText = chunk.joinToString(separator = ""),
                    emittedTokenCount = emitted.coerceAtMost(totalTokens),
                    totalTokenCount = totalTokens
                )
            )
        }

        sink.emit(
            LlmStreamEvent.FinalAnswer(
                answer = transcript.finalAnswer,
            )
        )
    }

    private fun computeDelayMillis(tokenCount: Int, tokensPerSecond: Double): Long {
        val seconds = tokenCount.toDouble() / tokensPerSecond
        val ms = ceil(seconds * 1000.0).toLong()
        return ms.coerceIn(20L, 750L)
    }
}

internal object PseudoTokenizer {
    private val tokenPattern = Regex("""\s+|[^\s]+""")

    fun tokenizeKeepingWhitespace(text: String): List<String> {
        if (text.isBlank()) return listOf("")
        return tokenPattern.findAll(text).map { it.value }.toList()
    }
}