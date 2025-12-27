package io.github.ceracharlescc.lmversusu.internal.domain.repository

import kotlin.uuid.Uuid

internal interface LightweightDatasetRepository {
    suspend fun availableQuestionIds(datasetPath: String): Set<Uuid>
    suspend fun declaredQuestionSetPath(datasetPath: String): String?
}