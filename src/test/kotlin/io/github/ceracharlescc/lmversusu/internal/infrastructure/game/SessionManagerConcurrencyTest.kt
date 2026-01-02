@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.ceracharlescc.lmversusu.internal.infrastructure.game

import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.domain.entity.GameMode
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import io.github.ceracharlescc.lmversusu.internal.domain.vo.ClientIdentity
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

            // Note: In SessionActor logic, if a new player tries to join an existing session owned by someone else,
            // it returns Rejected("session_taken").
            assertEquals("session_taken", failure.errorCode)
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
}
