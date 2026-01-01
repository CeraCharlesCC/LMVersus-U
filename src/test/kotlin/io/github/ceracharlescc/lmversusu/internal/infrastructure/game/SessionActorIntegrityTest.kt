@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import app.cash.turbine.test
import io.github.ceracharlescc.lmversusu.internal.application.port.*
import io.github.ceracharlescc.lmversusu.internal.application.service.LlmStreamOrchestrator
import io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase
import io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase
import io.github.ceracharlescc.lmversusu.internal.domain.entity.*
import io.github.ceracharlescc.lmversusu.internal.domain.repository.ResultsRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.*
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamingPolicy
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

class SessionActorIntegrityTest {

    private val eventBus = mockk<GameEventBus>(relaxed = true)
    private val llmGateway = mockk<LlmPlayerGateway>(relaxed = true)
    private val resultsRepo = mockk<ResultsRepository>(relaxed = true)

    // Use fixed clock for deterministic timing tests
    private var fixedTime = Instant.parse("2024-01-01T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = fixedTime
    }

    private val humanId = Uuid.random()
    private val sessionId = Uuid.random()

    // Real use cases with mocks for strict logic testing
    private val verifier = mockk<AnswerVerifier> {
        every { verify(any(), any()) } returns VerificationOutcome(correct = true)
    }

    // We need a partial mock of QuestionSelector to return a specific question
    private val questionSelector = mockk<OpponentQuestionSelector>()

    private val startRoundUseCase = StartRoundUseCase(questionSelector, clock)
    private val submitAnswerUseCase = SubmitAnswerUseCase(clock)
    private val orchestrator = LlmStreamOrchestrator()

    private lateinit var actor: SessionActor

    @BeforeEach
    fun setup() {
        every { llmGateway.streamAnswer(any()) } returns emptyFlow()

        // Setup a dummy question
        coEvery { questionSelector.pickQuestionsForOpponent(any(), any(), any()) } returns listOf(
            Question(
                questionId = Uuid.random(),
                prompt = "Test?",
                verifierSpec = VerifierSpec.MultipleChoice(0),
                roundTime = java.time.Duration.ofMinutes(1)
            )
        )

        val spec = mockk<OpponentSpec.Lightweight> {
            every { id } returns "test-spec"
            every { mode } returns GameMode.LIGHTWEIGHT
            every { displayName } returns "Bot"
            every { llmProfile } returns LlmProfile.DEFAULT
            every { streaming } returns StreamingPolicy()
        }

        actor = SessionActor(
            logger = LoggerFactory.getLogger("TestLogger"),
            sessionId = sessionId,
            opponentSpec = spec,
            gameEventBus = eventBus,
            startRoundUseCase = startRoundUseCase,
            submitAnswerUseCase = submitAnswerUseCase,
            answerVerifier = verifier,
            llmPlayerGateway = llmGateway,
            llmStreamOrchestrator = orchestrator,
            resultsRepository = resultsRepo,
            clock = clock,
            mailboxCapacity = 100,
            onTerminate = {}
        )
    }

    @Test
    fun `Adversarial - Cannot submit answer for the LLM`() = runTest {
        joinSession()

        startRound()

        val roundStartedSlot = slot<GameEvent.RoundStarted>()
        coVerify { eventBus.publish(capture(roundStartedSlot)) }
        val roundEvent = roundStartedSlot.captured

        actor.submit(
            SessionCommand.SubmitAnswer(
                sessionId = sessionId,
                playerId = humanId,
                roundId = roundEvent.roundId,
                nonceToken = roundEvent.nonceToken,
                answer = Answer.MultipleChoice(0),
                clientSentAt = null
            )
        )

        testScheduler.advanceUntilIdle()

        // Double submit attempt for Human

        actor.submit(
            SessionCommand.SubmitAnswer(
                sessionId = sessionId,
                playerId = humanId,
                roundId = roundEvent.roundId,
                nonceToken = roundEvent.nonceToken,
                answer = Answer.MultipleChoice(1), // Different answer
                clientSentAt = null
            )
        )

        testScheduler.advanceUntilIdle()

        // verification: Should receive SessionError or simple rejection on second attempt
        // The first one triggers SubmissionReceived
        coVerify(exactly = 1) {
            eventBus.publish(match { it is GameEvent.SubmissionReceived && it.playerType == Player.PlayerType.HUMAN })
        }

        // The second triggers an error
        coVerify {
            eventBus.publish(match { it is GameEvent.SessionError && it.errorCode == "already_submitted" })
        }
    }

    @Test
    fun `Adversarial - Invalid Nonce Token Rejection`() = runTest {
        joinSession()
        startRound()

        // Wait for actor to process commands on Dispatchers.Default
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.delay(50)
        }

        val roundStartedSlot = slot<GameEvent.RoundStarted>()
        coVerify { eventBus.publish(capture(roundStartedSlot)) }
        val roundEvent = roundStartedSlot.captured

        // Attack: Client tries to automate submission but doesn't have the nonce yet,
        // or tries to replay a nonce from a previous round.
        actor.submit(
            SessionCommand.SubmitAnswer(
                sessionId = sessionId,
                playerId = humanId,
                roundId = roundEvent.roundId,
                nonceToken = "wrong-nonce-value",
                answer = Answer.MultipleChoice(0),
                clientSentAt = null
            )
        )

        // Allow the actor's Dispatchers.Default coroutine to process the command
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.delay(50)
        }
        testScheduler.advanceUntilIdle()

        coVerify {
            eventBus.publish(match { it is GameEvent.SessionError && it.errorCode == "invalid_nonce" })
        }
        // Ensure NO submission was recorded
        coVerify(exactly = 0) { eventBus.publish(match { it is GameEvent.SubmissionReceived }) }
    }

    @Test
    fun `Adversarial - Time Travel (Submission after deadline)`() = runTest {
        joinSession()
        startRound()

        val roundStartedSlot = slot<GameEvent.RoundStarted>()
        coVerify { eventBus.publish(capture(roundStartedSlot)) }
        val roundEvent = roundStartedSlot.captured

        // Move clock past deadline (Released + Handicap + Duration)
        // Handicap ~2-3s, Duration 60s. Move 2 hours ahead.
        fixedTime = fixedTime.plusSeconds(7200)

        actor.submit(
            SessionCommand.SubmitAnswer(
                sessionId = sessionId,
                playerId = humanId,
                roundId = roundEvent.roundId,
                nonceToken = roundEvent.nonceToken,
                answer = Answer.MultipleChoice(0),
                clientSentAt = null
            )
        )

        testScheduler.advanceUntilIdle()

        coVerify {
            eventBus.publish(match { it is GameEvent.SessionError && it.errorCode == "deadline_passed" })
        }
    }

    private suspend fun joinSession() {
        val deferred = CompletableDeferred<JoinResponse>()
        actor.submit(SessionCommand.JoinSession(sessionId, humanId, "Tester", deferred))
        deferred.await()
    }

    private fun startRound() {
        actor.submit(SessionCommand.StartNextRound(sessionId, humanId))
    }
}