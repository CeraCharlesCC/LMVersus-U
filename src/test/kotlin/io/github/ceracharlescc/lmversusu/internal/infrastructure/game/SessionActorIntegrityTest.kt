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
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.bytebuddy.matcher.ElementMatchers.any
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
    private var currentTestScheduler: kotlinx.coroutines.test.TestCoroutineScheduler? = null
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant {
            return fixedTime.plusMillis(currentTestScheduler?.currentTime ?: 0L)
        }
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
        every { llmGateway.streamAnswer(any()) } returns kotlinx.coroutines.flow.flowOf(
            LlmStreamEvent.FinalAnswer(
                answer = LlmAnswer(
                    finalAnswer = Answer.MultipleChoice(0),
                    reasoningSummary = null,
                    confidenceScore = null
                )
            )
        )

        // Setup a dummy question
        coEvery { questionSelector.pickQuestionsForOpponent(any(), any(), any()) } returns listOf(
            Question(
                questionId = Uuid.random(),
                prompt = "Test?",
                verifierSpec = VerifierSpec.MultipleChoice(0),
                roundTime = java.time.Duration.ofMinutes(1)
            )
        )
    }

    private fun createActor(dispatcher: kotlinx.coroutines.CoroutineDispatcher): SessionActor {
        val spec = mockk<OpponentSpec.Lightweight> {
            every { id } returns "test-spec"
            every { mode } returns GameMode.LIGHTWEIGHT
            every { displayName } returns "Bot"
            every { llmProfile } returns LlmProfile.DEFAULT
            every { streaming } returns StreamingPolicy()
        }

        return SessionActor(
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
            onTerminate = {},
            dispatcher = dispatcher,
        )
    }

    @Test
    fun `Adversarial - Human cannot submit answer twice`() = runTest {
        // Keep the bot from instantly finishing the round
        every { llmGateway.streamAnswer(any()) } returns emptyFlow()

        currentTestScheduler = testScheduler
        actor = createActor(StandardTestDispatcher(testScheduler))

        joinSession()
        testScheduler.runCurrent()

        startRound()
        testScheduler.runCurrent()

        val roundStartedSlot = slot<GameEvent.RoundStarted>()
        coVerify { eventBus.publish(capture(roundStartedSlot)) }
        val roundEvent = roundStartedSlot.captured

        // First submit (valid)
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
        testScheduler.runCurrent()

        // Second submit (should be rejected)
        actor.submit(
            SessionCommand.SubmitAnswer(
                sessionId = sessionId,
                playerId = humanId,
                roundId = roundEvent.roundId,
                nonceToken = roundEvent.nonceToken,
                answer = Answer.MultipleChoice(1),
                clientSentAt = null
            )
        )
        testScheduler.runCurrent()

        coVerify(exactly = 1) {
            eventBus.publish(
                match {
                    it is GameEvent.SubmissionReceived &&
                            it.playerType == Player.PlayerType.HUMAN
                }
            )
        }

        coVerify {
            eventBus.publish(
                match { it is GameEvent.SessionError && it.errorCode == "already_submitted" },
            )
        }
    }


    @Test
    fun `Adversarial - Invalid Nonce Token Rejection`() = runTest {
        every { llmGateway.streamAnswer(any()) } returns emptyFlow()

        currentTestScheduler = testScheduler
        actor = createActor(StandardTestDispatcher(testScheduler))

        joinSession()
        testScheduler.runCurrent()

        startRound()
        testScheduler.runCurrent()

        val roundStartedSlot = slot<GameEvent.RoundStarted>()
        coVerify { eventBus.publish(capture(roundStartedSlot)) }
        val roundEvent = roundStartedSlot.captured

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

        // Drain mailbox without fast-forwarding virtual time into deadline/cleanup timers
        testScheduler.runCurrent()

        coVerify {
            eventBus.publish(
                match { it is GameEvent.SessionError && it.errorCode == "invalid_nonce" },
            )
        }
        coVerify(exactly = 0) {
            eventBus.publish(match { it is GameEvent.SubmissionReceived })
        }
    }

    @Test
    fun `Adversarial - Time Travel (Submission after deadline)`() = runTest {
        every { llmGateway.streamAnswer(any()) } returns emptyFlow()

        currentTestScheduler = testScheduler
        actor = createActor(StandardTestDispatcher(testScheduler))

        joinSession()
        testScheduler.runCurrent()

        startRound()
        testScheduler.runCurrent()

        val roundStartedSlot = slot<GameEvent.RoundStarted>()
        coVerify { eventBus.publish(capture(roundStartedSlot)) }
        val roundEvent = roundStartedSlot.captured

        // Move wall clock far past deadline (don’t advance testScheduler time)
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

        testScheduler.runCurrent()

        coVerify {
            eventBus.publish(
                match { it is GameEvent.SessionError && it.errorCode == "deadline_passed" },
            )
        }
    }


    private suspend fun TestScope.joinSession() {
        val deferred = CompletableDeferred<JoinResponse>()
        actor.submit(SessionCommand.JoinSession(sessionId, humanId, "Tester", deferred))
        testScheduler.runCurrent()
        deferred.await()
    }

    private fun startRound() {
        actor.submit(SessionCommand.StartNextRound(sessionId, humanId))
    }

    @Test
    fun `Rejoin Session returns Round Snapshot if round is in progress`() = runTest {
        every { llmGateway.streamAnswer(any()) } returns kotlinx.coroutines.flow.emptyFlow()

        currentTestScheduler = testScheduler
        actor = createActor(StandardTestDispatcher(testScheduler))

        // First join - creates session
        joinSession()
        testScheduler.runCurrent()

        // Start a round
        startRound()
        testScheduler.runCurrent()

        // Verify round started
        val roundStartedSlot = slot<GameEvent.RoundStarted>()
        coVerify { eventBus.publish(capture(roundStartedSlot)) }
        val roundEvent = roundStartedSlot.captured

        // Now rejoin - simulate browser refresh
        val rejoinDeferred = CompletableDeferred<JoinResponse>()
        actor.submit(SessionCommand.JoinSession(sessionId, humanId, "Tester", rejoinDeferred))
        testScheduler.runCurrent()
        val rejoinResponse = rejoinDeferred.await()

        // Verify the response contains the round snapshot
        assertIs<JoinResponse.Accepted>(rejoinResponse)
        val snapshot = rejoinResponse.roundSnapshot
        assertIs<GameEvent.RoundStarted>(snapshot!!)
        assertEquals(roundEvent.roundId, snapshot.roundId)
        assertEquals(roundEvent.questionPrompt, snapshot.questionPrompt)
        assertEquals(roundEvent.nonceToken, snapshot.nonceToken)
    }
}
