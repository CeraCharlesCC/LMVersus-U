package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.source

import io.github.ceracharlescc.lmversusu.internal.application.port.RoundContext
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao.LocalAnswerDao
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class LightweightPlayerSource @Inject constructor() {
    private val daoByDatasetPath = ConcurrentHashMap<String, LocalAnswerDao>()

    private fun daoFor(spec: OpponentSpec.Lightweight): LocalAnswerDao {
        return daoByDatasetPath.computeIfAbsent(spec.datasetPath) { LocalAnswerDao(spec.datasetPath) }
    }

    fun stream(context: RoundContext, spec: OpponentSpec.Lightweight): Flow<LlmStreamEvent> {
        val dao = daoFor(spec)
        return dao.streamReplay(questionId = context.questionId)
    }

    suspend fun get(context: RoundContext, spec: OpponentSpec.Lightweight): LlmAnswer {
        val dao = daoFor(spec)
        return dao.getReplayAnswer(questionId = context.questionId)
    }
}
