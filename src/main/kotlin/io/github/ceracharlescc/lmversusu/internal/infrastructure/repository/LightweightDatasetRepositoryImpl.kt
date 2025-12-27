package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository

import io.github.ceracharlescc.lmversusu.internal.domain.repository.LightweightDatasetRepository
import io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao.LocalAnswerDao
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class LightweightDatasetRepositoryImpl @Inject constructor(
    @param:Named("configDirectory") private val configDirectory: Path,
) : LightweightDatasetRepository {

    private val daoByResolvedPath = ConcurrentHashMap<String, LocalAnswerDao>()

    override suspend fun availableQuestionIds(datasetPath: String): Set<Uuid> =
        daoFor(datasetPath).availableQuestionIds()

    override suspend fun declaredQuestionSetPath(datasetPath: String): String? =
        daoFor(datasetPath).questionSetPath()

    private fun daoFor(rawDatasetPath: String): LocalAnswerDao {
        val resolved = resolvePath(rawDatasetPath)
        return daoByResolvedPath.computeIfAbsent(resolved) { LocalAnswerDao(datasetPath = it) }
    }

    private fun resolvePath(rawPath: String): String {
        val path = Paths.get(rawPath)
        val resolved = if (path.isAbsolute) path else configDirectory.resolve(path)
        return resolved.normalize().toString()
    }
}