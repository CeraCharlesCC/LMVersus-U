package io.github.ceracharlescc.lmversusu.internal.application.usecase

import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameSession
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Player
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Round
import io.github.ceracharlescc.lmversusu.internal.domain.entity.Submission
import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

@Singleton
internal class SubmitAnswerUseCase @Inject constructor(
    private val clock: Clock,
) {
    sealed interface Result {
        data class Success(
            val session: GameSession,
            val round: Round,
            val playerType: Player.PlayerType,
        ) : Result

        data class Failure(
            val errorCode: String,
            val message: String,
        ) : Result
    }

    fun execute(
        session: GameSession,
        playerId: Uuid,
        roundId: Uuid,
        nonceToken: String,
        answer: Answer,
        clientSentAt: Instant?,
    ): Result {
        val player = session.players.findById(playerId)
            ?: return Result.Failure(errorCode = "player_not_found", message = "player not found")

        val round = session.rounds.firstOrNull { it.roundId == roundId }
            ?: return Result.Failure(errorCode = "round_not_found", message = "round not found")

        if (!round.isInProgress) {
            return Result.Failure(errorCode = "round_closed", message = "round is not in progress")
        }

        if (round.nonceToken != nonceToken) {
            return Result.Failure(errorCode = "invalid_nonce", message = "nonceToken mismatch")
        }

        val now = Instant.now(clock)
        if (player.type == Player.PlayerType.HUMAN && now.isAfter(round.deadline)) {
            return Result.Failure(errorCode = "deadline_passed", message = "round deadline has passed")
        }

        if (player.type == Player.PlayerType.HUMAN && round.humanSubmission != null) {
            return Result.Failure(errorCode = "already_submitted", message = "human submission already received")
        }
        if (player.type == Player.PlayerType.LLM && round.llmSubmission != null) {
            return Result.Failure(errorCode = "already_submitted", message = "llm submission already received")
        }

        val submission = Submission(
            submissionId = Uuid.random(),
            playerId = playerId,
            answer = answer,
            serverReceivedAt = now,
            clientSentAt = clientSentAt,
        )

        val updatedRound = when (player.type) {
            Player.PlayerType.HUMAN -> round.copy(humanSubmission = submission)
            Player.PlayerType.LLM -> round.copy(llmSubmission = submission)
        }

        val updatedSession = session.copy(
            rounds = session.rounds.map { existing ->
                if (existing.roundId == roundId) updatedRound else existing
            }
        )

        return Result.Success(
            session = updatedSession,
            round = updatedRound,
            playerType = player.type,
        )
    }
}
