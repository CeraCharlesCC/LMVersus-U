package io.github.ceracharlescc.io.github.ceracharlescc.lmversusu.internal.application.service

import io.github.ceracharlescc.lmversusu.internal.application.service.LlmStreamOrchestrator
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

        assertEquals(2, events.size)
        assertEquals(1_500, events[1].atMs)
        assertTrue(events[1].event is LlmStreamEvent.ReasoningDelta)

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
        assertEquals(1, events.size)
        assertEquals(1_000, events[0].atMs)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)

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
}
