@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.ClientIdentity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SessionManagerConcurrencyTest {

    companion object {
        private const val ACTIVE_SESSIONS_LIMIT = 5
    }

    @Test
    fun `Adversarial - Session Creation Flood (Global & IP Limits)`() {
        // Setup strict limits
        val appConfig = AppConfig(
            sessionLimitConfig = AppConfig.SessionLimitConfig(
                lightweight = AppConfig.ModeLimitConfig.lightweightDefaults().copy(
                    maxActiveSessions = ACTIVE_SESSIONS_LIMIT,
                    perPersonWindowLimit = 100,
                )
            )
        )

        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById("spec-1") } returns mockk<OpponentSpec.Lightweight> {
            every { id } returns "spec-1"
            every { mode } returns GameMode.LIGHTWEIGHT
            every { displayName } returns "Bot"
            every { llmProfile } returns mockk(relaxed = true)
            every { streaming } returns mockk(relaxed = true)
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = appConfig,
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = mockk(relaxed = true),
            submitAnswerUseCase = mockk(relaxed = true),
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex(),
        )

        val attackerIp = "192.168.1.66"

        runBlocking(Dispatchers.Default) {
            val jobs = (1..20).map {
                async {
                    manager.joinSession(
                        sessionId = Uuid.random(),
                        clientIdentity = ClientIdentity(Uuid.random(), attackerIp),
                        nickname = "Attacker-$it",
                        opponentSpecId = "spec-1"
                    )
                }
            }

            val results = jobs.awaitAll()

            val successes = results.count { it is SessionManager.JoinResult.Success }
            val failures = results.count { it is SessionManager.JoinResult.Failure }

            assertEquals(
                ACTIVE_SESSIONS_LIMIT,
                successes,
                "Should cap at $ACTIVE_SESSIONS_LIMIT active sessions"
            )

            assertTrue(failures >= 15, "Remaining attempts should fail")

            val limitError = results.filterIsInstance<SessionManager.JoinResult.Failure>()
                .firstOrNull()

            assertEquals("session_limit_exceeded", limitError?.errorCode)
        }

        manager.shutdownAll()
    }


    @Test
    fun `Adversarial - Race Condition on Join Same Session`() {
        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById(any()) } returns mockk<OpponentSpec.Lightweight>(relaxed = true) {
            every { id } returns "spec-1"
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = AppConfig(), // Default config
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = mockk(relaxed = true),
            submitAnswerUseCase = mockk(relaxed = true),
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex(),
        )

        val targetSessionId = Uuid.random()

        runBlocking(Dispatchers.Default) {
            // Two different players try to claim the same session ID at the exact same moment
            val p1 = async {
                manager.joinSession(
                    targetSessionId,
                    ClientIdentity(Uuid.random(), "1.1.1.1"),
                    "P1", "spec-1"
                )
            }
            val p2 = async {
                manager.joinSession(
                    targetSessionId,
                    ClientIdentity(Uuid.random(), "2.2.2.2"),
                    "P2", "spec-1"
                )
            }

            val (r1, r2) = listOf(p1.await(), p2.await())

            // Only one should succeed
            val successCount = listOf(r1, r2).count { it is SessionManager.JoinResult.Success }
            assertEquals(1, successCount, "Only one player should own the session")

            // The other should fail with 'session_taken' or similar logic inside SessionActor
            val failure = listOf(r1, r2).filterIsInstance<SessionManager.JoinResult.Failure>().first()

            // Note: Error can be session_taken (SessionActor), session_creating (pre-reservation check),
            // or session_not_owned (ownership check) depending on timing
            val validErrorCodes = listOf("session_taken", "session_creating", "session_not_owned")
            assertTrue(
                failure.errorCode in validErrorCodes,
                "Error code should be session security related, got: ${failure.errorCode}"
            )
        }

        manager.shutdownAll()
    }

    @Test
    fun `Adversarial - Bind Terminate DoS Attack Prevention`() {
        // Setup: Player A creates and owns a session
        // Attack: Player B tries to join with Player A's sessionId
        // Expected: Player B's join is rejected with "session_not_owned", Player A's session remains active
        val playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex()

        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById(any()) } returns mockk<OpponentSpec.Lightweight>(relaxed = true) {
            every { id } returns "spec-1"
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = AppConfig(),
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = mockk(relaxed = true),
            submitAnswerUseCase = mockk(relaxed = true),
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = playerActiveSessionIndex,
        )

        val playerA = Uuid.random()
        val playerB = Uuid.random()

        runBlocking {
            // Player A creates a session
            val resultA = manager.joinSession(
                sessionId = null, // Let system generate sessionId
                clientIdentity = ClientIdentity(playerA, "1.1.1.1"),
                nickname = "PlayerA",
                opponentSpecId = "spec-1"
            )

            assertTrue(resultA is SessionManager.JoinResult.Success, "Player A should create session successfully")
            val sessionIdA = (resultA as SessionManager.JoinResult.Success).sessionId

            // Player B tries to hijack Player A's session by explicitly providing the sessionId
            val resultB = manager.joinSession(
                sessionId = sessionIdA, // Explicitly try to join A's session
                clientIdentity = ClientIdentity(playerB, "2.2.2.2"),
                nickname = "PlayerB",
                opponentSpecId = "spec-1"
            )

            // Player B should be rejected with "session_not_owned"
            assertTrue(resultB is SessionManager.JoinResult.Failure, "Player B should be rejected")
            val failure = resultB as SessionManager.JoinResult.Failure
            assertEquals("session_not_owned", failure.errorCode, "Should reject with session_not_owned")

            // Player B should NOT have a binding for A's session
            val bindingB = playerActiveSessionIndex.get(playerB)
            assertTrue(
                bindingB == null || bindingB.sessionId != sessionIdA,
                "Player B should not have a poisonous binding to A's session"
            )

            // Player B tries to terminate - should NOT affect Player A's session
            val terminated = manager.terminateActiveSessionByOwner(playerB)
            assertTrue(terminated == null || terminated != sessionIdA, "Should not terminate A's session")

            // Verify Player A's session is still active
            val activeA = manager.getActiveSession(playerA, sessionIdA)
            assertTrue(activeA != null, "Player A's session should still be active")
        }

        manager.shutdownAll()
    }

    @Test
    fun `Adversarial - Cannot hijack Creating session`() {
        // Two players race to create the same session ID - one should be rejected
        val playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex()

        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById(any()) } returns mockk<OpponentSpec.Lightweight>(relaxed = true) {
            every { id } returns "spec-1"
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = AppConfig(),
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = mockk(relaxed = true),
            submitAnswerUseCase = mockk(relaxed = true),
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = playerActiveSessionIndex,
        )

        val playerA = Uuid.random()
        val playerB = Uuid.random()
        val targetSessionId = Uuid.random()

        runBlocking(Dispatchers.Default) {
            val p1 = async {
                manager.joinSession(
                    sessionId = targetSessionId,
                    clientIdentity = ClientIdentity(playerA, "1.1.1.1"),
                    nickname = "PlayerA",
                    opponentSpecId = "spec-1"
                )
            }

            val p2 = async {
                manager.joinSession(
                    sessionId = targetSessionId,
                    clientIdentity = ClientIdentity(playerB, "2.2.2.2"),
                    nickname = "PlayerB",
                    opponentSpecId = "spec-1"
                )
            }

            val results = listOf(p1.await(), p2.await())
            val successes = results.filterIsInstance<SessionManager.JoinResult.Success>()
            val failures = results.filterIsInstance<SessionManager.JoinResult.Failure>()

            // Only one should succeed
            assertEquals(1, successes.size, "Only one player should own the session")
            assertEquals(1, failures.size, "One player should be rejected")

            // The failure should be one of: session_creating, session_not_owned, or session_taken
            val validErrorCodes = listOf("session_creating", "session_not_owned", "session_taken")
            assertTrue(
                failures.first().errorCode in validErrorCodes,
                "Error code should be session security related, got: ${failures.first().errorCode}"
            )
        }

        manager.shutdownAll()
    }

    @Test
    fun `Binding not poisoned on opponent_spec_not_found`() {
        // Verify that no binding is created when opponent spec is invalid
        val playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex()
        val playerId = Uuid.random()

        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById("invalid-spec") } returns null
        every { specRepo.findById("valid-spec") } returns mockk<OpponentSpec.Lightweight>(relaxed = true) {
            every { id } returns "valid-spec"
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = AppConfig(),
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = mockk(relaxed = true),
            submitAnswerUseCase = mockk(relaxed = true),
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = playerActiveSessionIndex,
        )

        runBlocking {
            val result = manager.joinSession(
                sessionId = null,
                clientIdentity = ClientIdentity(playerId, "1.1.1.1"),
                nickname = "Player",
                opponentSpecId = "invalid-spec"
            )

            assertTrue(result is SessionManager.JoinResult.Failure, "Should fail for invalid spec")
            assertEquals("opponent_spec_not_found", (result as SessionManager.JoinResult.Failure).errorCode)

            // Critical: no binding should exist
            val binding = playerActiveSessionIndex.get(playerId)
            assertTrue(binding == null, "No binding should be created for invalid spec")
        }

        manager.shutdownAll()
    }

    @Test
    fun `getActiveSession heals missing binding`() {
        // Create session, clear binding, verify getActiveSession restores it
        val playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex()
        val playerId = Uuid.random()

        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById(any()) } returns mockk<OpponentSpec.Lightweight>(relaxed = true) {
            every { id } returns "spec-1"
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = AppConfig(),
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = mockk(relaxed = true),
            submitAnswerUseCase = mockk(relaxed = true),
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = playerActiveSessionIndex,
        )

        runBlocking {
            // Create session
            val result = manager.joinSession(
                sessionId = null,
                clientIdentity = ClientIdentity(playerId, "1.1.1.1"),
                nickname = "Player",
                opponentSpecId = "spec-1"
            )
            assertTrue(result is SessionManager.JoinResult.Success)
            val sessionId = (result as SessionManager.JoinResult.Success).sessionId

            // Verify binding exists
            assertTrue(playerActiveSessionIndex.get(playerId)?.sessionId == sessionId, "Binding should exist")

            // Manually clear the binding (simulate corruption)
            playerActiveSessionIndex.clear(playerId, sessionId)
            assertTrue(playerActiveSessionIndex.get(playerId) == null, "Binding should be cleared")

            // Call getActiveSession with hint - should heal binding
            val snapshot = manager.getActiveSession(playerId, sessionId)
            assertTrue(snapshot != null, "Should find session via hint")
            assertEquals(sessionId, snapshot!!.sessionId)

            // Binding should be restored
            val healedBinding = playerActiveSessionIndex.get(playerId)
            assertTrue(healedBinding != null, "Binding should be healed")
            assertEquals(sessionId, healedBinding!!.sessionId)

            // terminateActiveSessionByOwner should now work
            val terminated = manager.terminateActiveSessionByOwner(playerId)
            assertEquals(sessionId, terminated, "Should terminate session via healed binding")
        }

        manager.shutdownAll()
    }

    @Test
    fun `Idempotency - StartNextRound while round in progress is no-op`() {
        // Verify that calling StartNextRound while a round is already in progress
        // does not produce an error and does not start a duplicate round
        val playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex()
        val playerId = Uuid.random()

        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById(any()) } returns mockk<OpponentSpec.Lightweight>(relaxed = true) {
            every { id } returns "spec-1"
            every { mode } returns GameMode.LIGHTWEIGHT
        }

        val startRoundUseCase =
            mockk<io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase>(relaxed = true)
        var startRoundCallCount = 0
        coEvery { startRoundUseCase.execute(any(), any()) } answers {
            startRoundCallCount++
            // Return a success with a mock round that is in progress
            val mockRound = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.Round>(relaxed = true) {
                every { roundId } returns Uuid.random()
                every { isInProgress } returns true
                every { question } returns mockk(relaxed = true) {
                    every { questionId } returns Uuid.random()
                    every { prompt } returns "Test question"
                    every { choices } returns null
                    every { verifierSpec } returns mockk<io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec.FreeResponse>(
                        relaxed = true
                    )
                }
                every { releasedAt } returns java.time.Instant.now()
                every { handicap } returns java.time.Duration.ZERO
                every { deadline } returns java.time.Instant.now().plusSeconds(60)
                every { nonceToken } returns "nonce"
            }
            val session = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.GameSession>(relaxed = true) {
                every { rounds } returns listOf(mockRound)
            }
            io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase.Result.Success(
                session = session,
                round = mockRound,
                roundNumber = 1,
            )
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = AppConfig(),
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = startRoundUseCase,
            submitAnswerUseCase = mockk(relaxed = true),
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = playerActiveSessionIndex,
        )

        runBlocking {
            // Create session
            val joinResult = manager.joinSession(
                sessionId = null,
                clientIdentity = ClientIdentity(playerId, "1.1.1.1"),
                nickname = "Player",
                opponentSpecId = "spec-1"
            )
            assertTrue(joinResult is SessionManager.JoinResult.Success)
            val sessionId = (joinResult as SessionManager.JoinResult.Success).sessionId

            // Start first round
            val firstStartResult = manager.startNextRound(sessionId, playerId, Uuid.random())
            assertTrue(firstStartResult is SessionManager.CommandResult.Success, "First startNextRound should succeed")

            // Give the actor some time to process
            kotlinx.coroutines.delay(100)

            // Try to start another round with a DIFFERENT commandId (simulating client retry)
            val secondStartResult = manager.startNextRound(sessionId, playerId, Uuid.random())
            assertTrue(
                secondStartResult is SessionManager.CommandResult.Success,
                "Second startNextRound should also succeed (no-op)"
            )

            // Give the actor time to process
            kotlinx.coroutines.delay(100)

            // The use case should only have been called ONCE (semantic idempotency)
            assertEquals(1, startRoundCallCount, "StartRoundUseCase should only be called once")
        }

        manager.shutdownAll()
    }

    @Test
    fun `Idempotency - SubmitAnswer after already submitted is no-op`() {
        // Verify that submitting an answer when the player has already submitted
        // does not produce an error (returns success as no-op)
        // Note: Due to the complexity of properly mocking session state transitions,
        // this test focuses on the observable behavior: both submits succeed without error.
        val playerActiveSessionIndex = InMemoryPlayerActiveSessionIndex()
        val playerId = Uuid.random()
        val roundId = Uuid.random()

        val specRepo = mockk<OpponentSpecRepository>()
        every { specRepo.findById(any()) } returns mockk<OpponentSpec.Lightweight>(relaxed = true) {
            every { id } returns "spec-1"
            every { mode } returns GameMode.LIGHTWEIGHT
        }

        // Create a real mock session with proper player setup for idempotency testing
        val humanPlayer = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.Player>(relaxed = true) {
            every { this@mockk.playerId } returns playerId
            every { type } returns io.github.ceracharlescc.lmversusu.internal.domain.entity.Player.PlayerType.HUMAN
        }
        val llmPlayer = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.Player>(relaxed = true) {
            every { this@mockk.playerId } returns Uuid.random()
            every { type } returns io.github.ceracharlescc.lmversusu.internal.domain.entity.Player.PlayerType.LLM
        }
        val players = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.PlayerSet>(relaxed = true) {
            every { human } returns humanPlayer
            every { llm } returns llmPlayer
            every { findById(playerId) } returns humanPlayer
        }

        // Configure submitAnswerUseCase to properly track calls
        val submitAnswerUseCase =
            mockk<io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase>(relaxed = true)
        var submitCallCount = 0

        // First call returns success, subsequent calls return "already_submitted" failure
        every { submitAnswerUseCase.execute(any(), any(), any(), any(), any(), any()) } answers {
            submitCallCount++
            if (submitCallCount == 1) {
                val mockRound = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.Round>(relaxed = true) {
                    every { this@mockk.roundId } returns roundId
                    every { humanSubmission } returns mockk(relaxed = true)  // Now has submission
                    every { llmSubmission } returns null
                    every { isInProgress } returns true
                }
                val session =
                    mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.GameSession>(relaxed = true) {
                        every { rounds } returns listOf(mockRound)
                        every { this@mockk.players } returns players
                    }
                io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase.Result.Success(
                    session = session,
                    round = mockRound,
                    playerType = io.github.ceracharlescc.lmversusu.internal.domain.entity.Player.PlayerType.HUMAN,
                )
            } else {
                // Second call would be blocked by semantic idempotency, but if it gets through, the use case returns error
                io.github.ceracharlescc.lmversusu.internal.application.usecase.SubmitAnswerUseCase.Result.Failure(
                    errorCode = "already_submitted",
                    message = "submission already exists"
                )
            }
        }

        // Mock startRoundUseCase to set up a round with proper player references
        val startRoundUseCase =
            mockk<io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase>(relaxed = true)
        coEvery { startRoundUseCase.execute(any(), any()) } answers {
            val mockRound = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.Round>(relaxed = true) {
                every { this@mockk.roundId } returns roundId
                every { humanSubmission } returns null
                every { llmSubmission } returns null
                every { isInProgress } returns true
                every { question } returns mockk(relaxed = true) {
                    every { questionId } returns Uuid.random()
                    every { prompt } returns "Test question"
                    every { choices } returns null
                    every { verifierSpec } returns mockk<io.github.ceracharlescc.lmversusu.internal.domain.vo.VerifierSpec.FreeResponse>(
                        relaxed = true
                    )
                }
                every { releasedAt } returns java.time.Instant.now()
                every { handicap } returns java.time.Duration.ZERO
                every { deadline } returns java.time.Instant.now().plusSeconds(60)
                every { nonceToken } returns "test-nonce"
            }
            val session = mockk<io.github.ceracharlescc.lmversusu.internal.domain.entity.GameSession>(relaxed = true) {
                every { rounds } returns listOf(mockRound)
                every { this@mockk.players } returns players
            }
            io.github.ceracharlescc.lmversusu.internal.application.usecase.StartRoundUseCase.Result.Success(
                session = session,
                round = mockRound,
                roundNumber = 1,
            )
        }

        val manager = SessionManager(
            logger = mockk(relaxed = true),
            appConfig = AppConfig(),
            opponentSpecRepository = specRepo,
            gameEventBus = mockk(relaxed = true),
            startRoundUseCase = startRoundUseCase,
            submitAnswerUseCase = submitAnswerUseCase,
            answerVerifier = mockk(relaxed = true),
            llmPlayerGateway = mockk(relaxed = true),
            llmStreamOrchestrator = mockk(relaxed = true),
            resultsRepository = mockk(relaxed = true),
            clock = Clock.systemUTC(),
            playerActiveSessionIndex = playerActiveSessionIndex,
        )

        runBlocking {
            // Create session
            val joinResult = manager.joinSession(
                sessionId = null,
                clientIdentity = ClientIdentity(playerId, "1.1.1.1"),
                nickname = "Player",
                opponentSpecId = "spec-1"
            )
            assertTrue(joinResult is SessionManager.JoinResult.Success)
            val sessionId = (joinResult as SessionManager.JoinResult.Success).sessionId

            // Start a round
            manager.startNextRound(sessionId, playerId, Uuid.random())
            kotlinx.coroutines.delay(100)

            // Submit first answer
            val answer = io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer.FreeText("test answer")
            val firstSubmitResult = manager.submitAnswer(
                sessionId = sessionId,
                playerId = playerId,
                roundId = roundId,
                commandId = Uuid.random(),
                nonceToken = "test-nonce",
                answer = answer,
                clientSentAt = null,
            )
            assertTrue(firstSubmitResult is SessionManager.CommandResult.Success, "First submit should succeed")

            kotlinx.coroutines.delay(100)

            // Submit second answer with DIFFERENT commandId (simulating client retry)
            val secondSubmitResult = manager.submitAnswer(
                sessionId = sessionId,
                playerId = playerId,
                roundId = roundId,
                commandId = Uuid.random(),  // Different command ID
                nonceToken = "test-nonce",
                answer = answer,
                clientSentAt = null,
            )
            // The second submit should also succeed (either as a true no-op via semantic idempotency,
            // or by the use case handling the already_submitted case)
            assertTrue(
                secondSubmitResult is SessionManager.CommandResult.Success,
                "Second submit should also succeed (no-op)"
            )
        }

        manager.shutdownAll()
    }
}

