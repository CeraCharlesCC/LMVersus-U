package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.source

import io.github.ceracharlescc.lmversusu.internal.application.port.RoundContext
import io.github.ceracharlescc.lmversusu.internal.di.annotation.ConfigDirectory
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao.LocalAnswerDao
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class LightweightPlayerSource @Inject constructor(
    @param:ConfigDirectory private val configDirectory: Path
) {
    private val daoByDatasetPath = ConcurrentHashMap<String, LocalAnswerDao>()

    private fun daoFor(spec: OpponentSpec.Lightweight): LocalAnswerDao {
        val resolvedPath = resolveDatasetPath(spec.datasetPath)
        return daoByDatasetPath.computeIfAbsent(resolvedPath) { LocalAnswerDao(resolvedPath) }
    }

    fun stream(context: RoundContext, spec: OpponentSpec.Lightweight): Flow<LlmStreamEvent> {
        val dao = daoFor(spec)
        return dao.streamReplay(questionId = context.questionId)
    }

    suspend fun get(context: RoundContext, spec: OpponentSpec.Lightweight): LlmAnswer {
        val dao = daoFor(spec)
        return dao.getReplayAnswer(questionId = context.questionId)
    }

    suspend fun availableQuestionIds(spec: OpponentSpec.Lightweight): Set<Uuid> =
        daoFor(spec).availableQuestionIds()

    suspend fun declaredQuestionSetPath(spec: OpponentSpec.Lightweight): String? =
        daoFor(spec).questionSetPath()

    private fun resolveDatasetPath(rawPath: String): String {
        val datasetPath = Paths.get(rawPath)
        val resolved = if (datasetPath.isAbsolute) datasetPath else configDirectory.resolve(datasetPath)
        return resolved.normalize().toString()
    }
}
