package io.github.ceracharlescc.lmversusu.internal.application.service

import io.github.ceracharlescc.lmversusu.internal.domain.vo.Answer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamingPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class LlmStreamOrchestratorTest {

    private data class TimedEvent(val atMs: Long, val event: LlmStreamEvent)

    private fun delta(
        text: String,
        emittedTokens: Int,
        totalTokens: Int,
    ): LlmStreamEvent = LlmStreamEvent.ReasoningDelta(
        deltaText = text,
        emittedTokenCount = emittedTokens,
        totalTokenCount = totalTokens,
    )

    private fun final(choiceIndex: Int = 0): LlmStreamEvent = LlmStreamEvent.FinalAnswer(
        answer = LlmAnswer(
            finalAnswer = Answer.MultipleChoice(choiceIndex = choiceIndex),
            reasoningSummary = null,
            confidenceScore = null,
        )
    )

    private fun errorEvent(message: String): LlmStreamEvent = LlmStreamEvent.Error(message = message, cause = null)

    private fun TestScope.launchTimedCollector(flow: Flow<LlmStreamEvent>): Pair<Job, MutableList<TimedEvent>> {
        val events = mutableListOf<TimedEvent>()
        val job = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            flow.collect { event ->
                events += TimedEvent(atMs = testScheduler.currentTime, event = event)
            }
        }
        return job to events
    }

    @Test
    fun `does not emit anything before revealDelay (including final)`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 10_000,
            targetTokensPerSecond = 100,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "abc", emittedTokens = 3, totalTokens = 3))
            emit(final(choiceIndex = 2))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(events.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(2, events.size)
        assertEquals(10_000, events[0].atMs)
        assertEquals(10_000, events[1].atMs)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)
        assertTrue(events[1].event is LlmStreamEvent.FinalAnswer)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `paces deltas after revealDelay using emittedTokenCount and targetTokensPerSecond`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 1_000,
            targetTokensPerSecond = 10,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "first", emittedTokens = 5, totalTokens = 10))
            emit(delta(text = "second", emittedTokens = 5, totalTokens = 10))
            delay(1_500) // Final arrives at t=1500, exactly when second delta is emitted at baseline pacing
            emit(final(choiceIndex = 1))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceTimeBy(999)
        runCurrent()
        assertTrue(events.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(1_000, events[0].atMs)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)

        advanceTimeBy(499)
        runCurrent()
        assertEquals(1, events.size)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(3, events.size)

        assertEquals(1_500, events[1].atMs)
        assertTrue(events[1].event is LlmStreamEvent.ReasoningDelta)

        assertEquals(1_500, events[2].atMs)
        assertTrue(events[2].event is LlmStreamEvent.FinalAnswer)

        runCurrent()
        assertEquals(3, events.size)
        assertEquals(1_500, events[2].atMs)
        assertTrue(events[2].event is LlmStreamEvent.FinalAnswer)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `burst flush cancels baseline pacing once final arrives and drains backlog at burst rate`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 0,
            targetTokensPerSecond = 10,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "d1", emittedTokens = 20, totalTokens = 100))
            delay(50)
            emit(delta(text = "d2", emittedTokens = 20, totalTokens = 100))
            delay(50)
            emit(delta(text = "d3", emittedTokens = 20, totalTokens = 100))
            delay(50)
            emit(delta(text = "d4", emittedTokens = 20, totalTokens = 100))
            delay(50)
            emit(delta(text = "d5", emittedTokens = 20, totalTokens = 100))
            delay(50)
            emit(final(choiceIndex = 3))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        runCurrent()
        assertEquals(1, events.size)
        assertEquals(0, events[0].atMs)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)

        advanceTimeBy(250)
        runCurrent()

        val next = events.getOrNull(1) ?: error("Expected delta2 emission after final arrival triggers burst flush")
        assertTrue(next.event is LlmStreamEvent.ReasoningDelta)
        assertEquals(250, next.atMs)

        advanceTimeBy(400)
        runCurrent()
        assertTrue(events[2].event is LlmStreamEvent.ReasoningDelta)
        assertEquals(650, events[2].atMs)

        advanceTimeBy(400)
        runCurrent()
        assertTrue(events[3].event is LlmStreamEvent.ReasoningDelta)
        assertEquals(1_050, events[3].atMs)

        advanceTimeBy(400)
        runCurrent()
        assertTrue(events[4].event is LlmStreamEvent.ReasoningDelta)
        assertEquals(1_450, events[4].atMs)

        runCurrent()
        assertEquals(6, events.size)
        assertTrue(events[5].event is LlmStreamEvent.FinalAnswer)
        assertEquals(1_450, events[5].atMs)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `enforces maxBufferedChars by dropping oldest reasoning and emitting ReasoningTruncated before deltas`() =
        runTest {
            val orchestrator = LlmStreamOrchestrator()

            val policy = StreamingPolicy(
                revealDelayMs = 0,
                targetTokensPerSecond = 1_000,
                burstMultiplierOnFinal = 5.0,
                maxBufferedChars = 10,
            )

            val upstream = flow {
                emit(delta(text = "0123456789", emittedTokens = 10, totalTokens = 20))
                emit(delta(text = "abcdefghij", emittedTokens = 10, totalTokens = 20))
                emit(final(choiceIndex = 0))
            }

            val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

            advanceUntilIdle()
            job.join()

            assertEquals(3, events.size)

            // Requires: LlmStreamEvent.ReasoningTruncated(droppedChars: Int)
            assertTrue(events[0].event is LlmStreamEvent.ReasoningTruncated)
            val trunc = events[0].event as LlmStreamEvent.ReasoningTruncated
            assertEquals(10, trunc.droppedChars)

            assertTrue(events[1].event is LlmStreamEvent.ReasoningDelta)
            val d = events[1].event as LlmStreamEvent.ReasoningDelta
            assertEquals("abcdefghij", d.deltaText)

            assertTrue(events[2].event is LlmStreamEvent.FinalAnswer)
        }

    @Test
    fun `propagates Error as terminal and still respects revealDelay gating`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 1_000,
            targetTokensPerSecond = 10,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "hello", emittedTokens = 5, totalTokens = 5))
            delay(100)
            emit(errorEvent("boom"))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceTimeBy(999)
        runCurrent()
        assertTrue(events.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(2, events.size)

        assertEquals(1_000, events[0].atMs)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)

        assertEquals(1_000, events[1].atMs)
        assertTrue(events[1].event is LlmStreamEvent.Error)

        runCurrent()
        assertEquals(2, events.size)
        assertEquals(1_000, events[1].atMs)
        assertTrue(events[1].event is LlmStreamEvent.Error)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `final-only upstream is emitted at revealDelay`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 2_000,
            targetTokensPerSecond = 10,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(final(choiceIndex = 2))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(events.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(1, events.size)
        assertEquals(2_000, events[0].atMs)
        assertTrue(events[0].event is LlmStreamEvent.FinalAnswer)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `burst flush is applied immediately when final arrives during revealDelay`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 1_000,
            targetTokensPerSecond = 10,      // base = 100ms/token
            burstMultiplierOnFinal = 5.0,    // burst = 20ms/token
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "d1", emittedTokens = 10, totalTokens = 20))
            emit(delta(text = "d2", emittedTokens = 10, totalTokens = 20))
            emit(final(choiceIndex = 1))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceTimeBy(999)
        runCurrent()
        assertTrue(events.isEmpty())

        advanceTimeBy(1)
        runCurrent()

        assertEquals(1, events.size)
        assertEquals(1_000, events[0].atMs)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)

        advanceTimeBy(199)
        runCurrent()
        assertEquals(1, events.size)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(3, events.size)
        assertEquals(1_200, events[1].atMs)
        assertTrue(events[1].event is LlmStreamEvent.ReasoningDelta)

        assertEquals(1_200, events[2].atMs)
        assertTrue(events[2].event is LlmStreamEvent.FinalAnswer)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `ignores reasoning deltas received after terminal event`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 0,
            targetTokensPerSecond = 1_000,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "before", emittedTokens = 1, totalTokens = 1))
            emit(final(choiceIndex = 1))
            // Misbehaving upstream: sends more deltas after final
            emit(delta(text = "after1", emittedTokens = 1, totalTokens = 2))
            emit(delta(text = "after2", emittedTokens = 1, totalTokens = 3))
            emit(delta(text = "after3", emittedTokens = 1, totalTokens = 4))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceUntilIdle()
        job.join()

        // Should only have the delta before final + the final answer
        assertEquals(2, events.size)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)
        assertEquals("before", (events[0].event as LlmStreamEvent.ReasoningDelta).deltaText)
        assertTrue(events[1].event is LlmStreamEvent.FinalAnswer)
    }

    @Test
    fun `propagates upstream exception as Error event`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 0,
            targetTokensPerSecond = 1_000,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "before crash", emittedTokens = 1, totalTokens = 1))
            throw RuntimeException("upstream boom")
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceUntilIdle()
        job.join()

        assertEquals(2, events.size)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)
        assertTrue(events[1].event is LlmStreamEvent.Error)

        val error = events[1].event as LlmStreamEvent.Error
        assertEquals("upstream boom", error.message)
        assertTrue(error.cause is RuntimeException)
    }

    @Test
    fun `forwards upstream ReasoningTruncated with reason`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 0,
            targetTokensPerSecond = 1_000,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "part1", emittedTokens = 1, totalTokens = 2))
            emit(LlmStreamEvent.ReasoningTruncated(reason = "reasoning budget exhausted"))
            emit(final(choiceIndex = 0))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceUntilIdle()
        job.join()

        assertEquals(3, events.size)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)
        assertTrue(events[1].event is LlmStreamEvent.ReasoningTruncated)
        assertTrue(events[2].event is LlmStreamEvent.FinalAnswer)

        val trunc = events[1].event as LlmStreamEvent.ReasoningTruncated
        assertEquals("reasoning budget exhausted", trunc.reason)
        assertEquals(0, trunc.droppedChars)
    }

    @Test
    fun `emits both local and upstream truncation in correct semantic order`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 0,
            targetTokensPerSecond = 1_000,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 10,
        )

        val upstream = flow {
            emit(delta(text = "0123456789", emittedTokens = 10, totalTokens = 30))
            emit(delta(text = "abcdefghij", emittedTokens = 10, totalTokens = 30))
            emit(LlmStreamEvent.ReasoningTruncated(reason = "provider limit"))
            emit(final(choiceIndex = 0))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceUntilIdle()
        job.join()

        // Expected order:
        // 1. Local truncation (dropped chars due to buffer overflow) - emitted before delta
        // 2. Delta (the remaining content after local truncation)
        // 3. Upstream truncation (provider limit signaled) - emitted just before terminal
        // 4. FinalAnswer
        assertEquals(4, events.size)

        assertTrue(events[0].event is LlmStreamEvent.ReasoningTruncated)
        val localTrunc = events[0].event as LlmStreamEvent.ReasoningTruncated
        assertEquals(10, localTrunc.droppedChars)
        assertEquals(null, localTrunc.reason)

        assertTrue(events[1].event is LlmStreamEvent.ReasoningDelta)

        assertTrue(events[2].event is LlmStreamEvent.ReasoningTruncated)
        val upstreamTrunc = events[2].event as LlmStreamEvent.ReasoningTruncated
        assertEquals("provider limit", upstreamTrunc.reason)
        assertEquals(0, upstreamTrunc.droppedChars)

        assertTrue(events[3].event is LlmStreamEvent.FinalAnswer)
    }
}
