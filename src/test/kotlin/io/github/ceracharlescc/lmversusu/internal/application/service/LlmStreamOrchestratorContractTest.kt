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
internal class LlmStreamOrchestratorContractTest {

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

    private fun TestScope.launchTimedCollector(flow: Flow<LlmStreamEvent>): Pair<Job, MutableList<TimedEvent>> {
        val captured = mutableListOf<TimedEvent>()
        val job = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            flow.collect { event ->
                captured += TimedEvent(atMs = testScheduler.currentTime, event = event)
            }
        }
        return job to captured
    }

    @Test
    fun `CONTRACT - apply returns a cold Flow that does not start upstream work until collected`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        var upstreamCollectionCount = 0
        val upstream = flow {
            upstreamCollectionCount += 1
            emit(delta(text = "hello", emittedTokens = 1, totalTokens = 1))
            emit(final(choiceIndex = 1))
        }

        val policy = StreamingPolicy(
            revealDelayMs = 0,
            targetTokensPerSecond = 1_000,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val shaped = orchestrator.apply(policy, upstream)

        runCurrent()
        assertEquals(0, upstreamCollectionCount)

        val (job, events) = launchTimedCollector(shaped)
        runCurrent()

        assertEquals(1, upstreamCollectionCount)
        assertTrue(events.isNotEmpty())

        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `CONTRACT - all timing is driven by caller context (virtual time), not real dispatchers`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 12_345,
            targetTokensPerSecond = 10,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            emit(delta(text = "A", emittedTokens = 10, totalTokens = 20))
            emit(delta(text = "B", emittedTokens = 10, totalTokens = 20))
            emit(final(choiceIndex = 2))
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        advanceTimeBy(12_344)
        runCurrent()
        assertTrue(events.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, events.size)
        assertEquals(12_345, events[0].atMs)
        assertTrue(events[0].event is LlmStreamEvent.ReasoningDelta)

        advanceTimeBy(199)
        runCurrent()
        assertEquals(1, events.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, events.size)
        assertEquals(12_545, events[1].atMs)
        assertTrue(events[1].event is LlmStreamEvent.ReasoningDelta)
        assertEquals(12_545, events[2].atMs)
        assertTrue(events[2].event is LlmStreamEvent.FinalAnswer)

        job.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `CONTRACT - cancellation is respected (no background work continues after collector is cancelled)`() = runTest {
        val orchestrator = LlmStreamOrchestrator()

        val policy = StreamingPolicy(
            revealDelayMs = 0,
            targetTokensPerSecond = 1_000,
            burstMultiplierOnFinal = 5.0,
            maxBufferedChars = 200_000,
        )

        val upstream = flow {
            var total = 0
            while (true) {
                total += 1
                emit(delta(text = "tick-$total", emittedTokens = 1, totalTokens = total))
                delay(1)
            }
        }

        val (job, events) = launchTimedCollector(orchestrator.apply(policy, upstream))

        runCurrent()
        assertTrue(events.isNotEmpty())

        job.cancel()

        val sizeAtCancel = events.size
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(sizeAtCancel, events.size)

        advanceUntilIdle()
    }
}