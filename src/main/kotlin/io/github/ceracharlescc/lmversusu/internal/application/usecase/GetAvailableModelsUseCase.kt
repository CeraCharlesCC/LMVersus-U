package io.github.ceracharlescc.lmversusu.internal.application.usecase

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class GetAvailableModelsUseCase @Inject constructor(
    private val opponentSpecRepository: OpponentSpecRepository
) {
    fun execute(mode: GameMode? = null): Result {
        val specs = opponentSpecRepository.getAllSpecs()
            ?: return Result.Failure

        val filtered = mode?.let { m -> specs.filter { it.mode == m } } ?: specs
        return Result.Success(filtered)
    }

    sealed interface Result {
        data class Success(val specs: List<OpponentSpec>) : Result
        data object Failure : Result
    }
}
