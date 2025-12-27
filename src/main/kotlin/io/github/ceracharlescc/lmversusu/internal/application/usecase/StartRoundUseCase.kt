package io.github.ceracharlescc.lmversusu.internal.application.usecase

import io.github.ceracharlescc.lmversusu.internal.application.port.OpponentQuestionSelector
import io.github.ceracharlescc.lmversusu.internal.application.port.QuestionConstraints
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameSession
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Round
import io.github.ceracharlescc.lmversusu.internal.domain.entity.SessionState
import io.github.ceracharlescc.lmversusu.internal.domain.policy.HandicapPolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class StartRoundUseCase @Inject constructor(
    private val opponentQuestionSelector: OpponentQuestionSelector,
    private val clock: Clock,
) {
    sealed interface Result {
        data class Success(
            val session: GameSession,
            val round: Round,
            val roundNumber: Int,
        ) : Result

        data class Failure(
            val errorCode: String,
            val message: String,
        ) : Result
    }

    suspend fun execute(session: GameSession, opponentSpec: OpponentSpec): Result {
        if (session.state == SessionState.COMPLETED || session.state == SessionState.CANCELLED) {
            return Result.Failure(errorCode = "session_inactive", message = "session is not active")
        }
        if (session.currentRound != null) {
            return Result.Failure(errorCode = "round_in_progress", message = "round is already in progress")
        }
        if (session.rounds.size >= GameSession.TOTAL_ROUNDS) {
            return Result.Failure(errorCode = "no_more_rounds", message = "no more rounds available")
        }

        val excludeIds = session.rounds.map { it.question.questionId }.toSet()
        val question = opponentQuestionSelector.pickQuestionsForOpponent(
            opponentSpec = opponentSpec,
            count = 1,
            constraints = QuestionConstraints(excludeQuestionIds = excludeIds),
        ).firstOrNull()
            ?: return Result.Failure(errorCode = "no_question", message = "no question available")

        val releasedAt = Instant.now(clock)
        val handicap = HandicapPolicy.computeHandicap(question, session.mode)
        val deadline = releasedAt.plus(handicap).plus(DEFAULT_ROUND_DURATION)
        val nonceToken = Uuid.random().toString()
        val round = Round(
            roundId = Uuid.random(),
            question = question,
            releasedAt = releasedAt,
            handicap = handicap,
            deadline = deadline,
            nonceToken = nonceToken,
        )

        val updatedSession = session.copy(
            rounds = session.rounds + round,
            state = SessionState.IN_PROGRESS,
        )

        return Result.Success(
            session = updatedSession,
            round = round,
            roundNumber = updatedSession.rounds.size,
        )
    }

    private companion object {
        val DEFAULT_ROUND_DURATION: Duration = Duration.ofSeconds(60)
    }
}
