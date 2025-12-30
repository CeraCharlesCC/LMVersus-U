package io.github.ceracharlescc.lmversusu.internal.application.service

import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamingPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Orchestrates the pacing and buffering of an LLM streaming response.
 *
 * This service transforms a raw upstream [Flow] of [LlmStreamEvent]s into a paced
 * downstream flow, giving end-users a typewriter-like experience rather than
 * overwhelming them with a sudden wall of text.
 *
 * ## Core Behavior
 *
 * 1. Reveal Delay: The orchestrator waits [StreamingPolicy.revealDelayMs] before
 * emitting the first delta, allowing the upstream to accumulate initial content.
 *
 * 2. Chunk Delay: With [StreamingPolicy.chunkDelay] > 0, reasoning deltas are held
 * back so the UI shows reasoning N chunks behind the live stream. Deltas are
 * released only when at least `chunkDelay` newer chunks have been buffered.
 *
 * 3. Reasoning Ended: When [LlmStreamEvent.ReasoningEnded] is received, the orchestrator
 * switches to a "drain" mode. It rapidly emits buffered reasoning until only
 * [StreamingPolicy.chunkDelay] chunks remain. These remaining chunks are then
 * withheld (frozen) and [LlmStreamEvent.ReasoningEnded] is emitted downstream.
 *
 * 4. Baseline Pacing: Deltas are emitted at approximately [StreamingPolicy.targetTokensPerSecond].
 * If the policy specifies ≤ 0, pacing is disabled and deltas are forwarded immediately.
 *
 * 5. Burst Mode: Once a terminal event ([LlmStreamEvent.FinalAnswer] or
 * [LlmStreamEvent.Error]) arrives, or when ReasoningEnded arrives, the emission rate
 * accelerates by [StreamingPolicy.burstMultiplierOnFinal] to drain remaining deltas quickly.
 *
 * 6. Back-Pressure & Truncation: When the buffer grows beyond
 * [StreamingPolicy.maxBufferedChars], the oldest deltas are dropped (and later
 * reported as [LlmStreamEvent.ReasoningTruncated]) so that the UI always shows the
 * most recent reasoning.
 *
 * ## Implementation Notes
 *
 * - Concurrency model: The upstream flow is collected in a separate coroutine (`collector`).
 * Shared state is protected by a [Mutex]. The emitter loop runs in the caller's coroutine.
 * - Cooperative cancellation: If the emitter breaks out of its loop (due to terminal event
 * or upstream completion), the collector coroutine is explicitly cancelled.
 *
 * @see StreamingPolicy
 * @see LlmStreamEvent
 */
@Singleton
internal class LlmStreamOrchestrator @Inject constructor() {

    /**
     * Result of applying the orchestrator to an upstream flow.
     *
     * @property events The paced and processed event flow
     * @property getWithheldReasoning Function to retrieve reasoning withheld after ReasoningEnded
     */
    data class OrchestrationResult(
        val events: Flow<LlmStreamEvent>,
        val getWithheldReasoning: suspend () -> String,
    )

    /**
     * Applies orchestration policy to the upstream flow.
     * Returns the processed flow. For simple use cases, this matches existing behavior.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun apply(policy: StreamingPolicy, upstream: Flow<LlmStreamEvent>): Flow<LlmStreamEvent> =
        applyWithReveal(policy, upstream).events

    /**
     * Applies orchestration with support for retrieving withheld reasoning.
     * Use this when you need access to reasoning frozen after ReasoningEnded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun applyWithReveal(policy: StreamingPolicy, upstream: Flow<LlmStreamEvent>): OrchestrationResult {
        val withheldReasoningBuilder = StringBuilder()
        val withheldLock = Mutex()

        val events = flow {
            coroutineScope {
                val lock = Mutex()

                val buffer = ArrayDeque<LlmStreamEvent.ReasoningDelta>()
                var bufferedChars = 0

                var droppedPending = 0
                var upstreamTruncation: LlmStreamEvent.ReasoningTruncated? = null
                var terminal: LlmStreamEvent? = null
                var upstreamDone = false
                var reasoningEnded = false

                val updated = Channel<Unit>(Channel.CONFLATED)
                val terminalArrived = Channel<Unit>(Channel.CONFLATED)
                val reasoningEndedArrived = Channel<Unit>(Channel.CONFLATED)

                val collector = launch {
                    runCatching {
                        upstream.collect { event ->
                            lock.withLock {
                                when (event) {
                                    is LlmStreamEvent.ReasoningDelta -> {
                                        if (terminal != null || reasoningEnded) return@withLock
                                        buffer.addLast(event)
                                        bufferedChars += event.deltaText.length

                                        while (bufferedChars > policy.maxBufferedChars && buffer.size > 1) {
                                            val oldest = buffer.removeFirst()
                                            bufferedChars -= oldest.deltaText.length
                                            droppedPending += oldest.deltaText.length
                                        }
                                    }

                                    is LlmStreamEvent.ReasoningEnded -> {
                                        if (!reasoningEnded) reasoningEnded = true
                                    }

                                    is LlmStreamEvent.FinalAnswer,
                                    is LlmStreamEvent.Error -> {
                                        if (terminal == null) terminal = event
                                    }

                                    is LlmStreamEvent.ReasoningTruncated -> {
                                        upstreamTruncation = event
                                    }
                                }
                            }

                            updated.trySend(Unit)

                            when (event) {
                                is LlmStreamEvent.FinalAnswer,
                                is LlmStreamEvent.Error -> terminalArrived.trySend(Unit)

                                is LlmStreamEvent.ReasoningEnded -> reasoningEndedArrived.trySend(Unit)

                                is LlmStreamEvent.ReasoningDelta,
                                is LlmStreamEvent.ReasoningTruncated -> Unit
                            }
                        }
                    }.onFailure { e ->
                        lock.withLock {
                            if (terminal == null) {
                                terminal = LlmStreamEvent.Error(
                                    message = e.message ?: "Upstream error",
                                    cause = e,
                                )
                            }
                        }
                        updated.trySend(Unit)
                        terminalArrived.trySend(Unit)
                    }
                    lock.withLock { upstreamDone = true }
                    updated.trySend(Unit)
                }

                val baseMsPerToken =
                    if (policy.targetTokensPerSecond <= 0) 0.0 else 1000.0 / policy.targetTokensPerSecond.toDouble()
                val burstMsPerToken =
                    if (policy.burstMultiplierOnFinal <= 0.0) baseMsPerToken else baseMsPerToken / policy.burstMultiplierOnFinal

                fun delayFor(delta: LlmStreamEvent.ReasoningDelta, burst: Boolean): Long {
                    val ms = delta.emittedTokenCount.toDouble() * (if (burst) burstMsPerToken else baseMsPerToken)
                    return if (ms <= 0.0) 0L else ceil(ms).toLong()
                }

                var burstMode = false
                var reasoningEndedEmitted = false
                val holdBack = policy.chunkDelay.coerceAtLeast(0)

                if (policy.revealDelayMs > 0) delay(policy.revealDelayMs)

                yield()

                while (terminalArrived.tryReceive().isSuccess) {
                    burstMode = true
                }
                while (reasoningEndedArrived.tryReceive().isSuccess) {
                    burstMode = true
                }

                while (true) {
                    var tailToWithhold: List<LlmStreamEvent.ReasoningDelta> = emptyList()
                    var shouldEmitReasoningEndedNow = false
                    lock.withLock {
                        if (reasoningEnded && !reasoningEndedEmitted && buffer.size <= holdBack) {
                            reasoningEndedEmitted = true
                            shouldEmitReasoningEndedNow = true
                            if (buffer.isNotEmpty()) {
                                tailToWithhold = buffer.toList()
                                buffer.clear()
                                bufferedChars = 0
                            }
                        }
                    }
                    if (shouldEmitReasoningEndedNow) {
                        if (tailToWithhold.isNotEmpty()) {
                            withheldLock.withLock {
                                tailToWithhold.forEach { withheldReasoningBuilder.append(it.deltaText) }
                            }
                        }
                        emit(LlmStreamEvent.ReasoningEnded)
                    }

                    val snapshot = lock.withLock {
                        val releasable = when {
                            // once we've emitted ReasoningEnded (i.e., stop point reached), never release more
                            reasoningEndedEmitted -> false
                            reasoningEnded -> buffer.size > holdBack
                            terminal != null || burstMode -> true
                            holdBack <= 0 -> true
                            buffer.size > holdBack -> true
                            else -> false
                        }

                        val nextDelta = if (buffer.isEmpty() || !releasable) {
                            null  // Don't release if reasoning has ended (frozen for reveal)
                        } else {
                            buffer.removeFirst().also {
                                bufferedChars -= it.deltaText.length
                                if (bufferedChars < 0) bufferedChars = 0
                            }
                        }

                        val localDropped = droppedPending.also { droppedPending = 0 }

                        val terminalOrSynthetic = terminal ?: run {
                            if (upstreamDone) {
                                LlmStreamEvent.Error(
                                    message = "Upstream completed without terminal event",
                                    cause = null
                                )
                            } else {
                                null
                            }
                        }

                        Snapshot(
                            delta = nextDelta,
                            dropped = localDropped,
                            terminal = terminalOrSynthetic,
                            done = upstreamDone,
                            reasoningEnded = reasoningEnded,
                        )
                    }

                    if (snapshot.dropped > 0) {
                        emit(LlmStreamEvent.ReasoningTruncated(droppedChars = snapshot.dropped))
                    }

                    val delta = snapshot.delta
                    if (delta != null) {
                        emit(delta)

                        val terminalNow = lock.withLock {
                            if (terminal != null && buffer.isEmpty()) terminal else null
                        }
                        if (terminalNow != null) {
                            val pendingTruncation =
                                lock.withLock { upstreamTruncation.also { upstreamTruncation = null } }
                            if (pendingTruncation != null) emit(pendingTruncation)
                            emit(terminalNow)
                            break
                        }

                        if (!burstMode) {
                            val terminalKnown = lock.withLock { terminal != null }
                            if (terminalKnown) burstMode = true
                        }

                        val waitMs = delayFor(delta, burstMode)
                        if (waitMs > 0) {
                            if (!burstMode) {
                                val interrupted = select<Boolean> {
                                    terminalArrived.onReceive { true }
                                    reasoningEndedArrived.onReceive { true }
                                    onTimeout(waitMs) { false }
                                }
                                if (interrupted) burstMode = true
                            } else {
                                delay(waitMs)
                            }
                        }

                        continue
                    }

                    if (snapshot.terminal != null) {
                        val pendingTruncation = lock.withLock { upstreamTruncation.also { upstreamTruncation = null } }
                        if (pendingTruncation != null) emit(pendingTruncation)
                        emit(snapshot.terminal)
                        break
                    }

                    if (snapshot.done) break
                    updated.receive()
                }

                collector.cancel()
            }
        }

        return OrchestrationResult(
            events = events,
            getWithheldReasoning = suspend { withheldLock.withLock { withheldReasoningBuilder.toString() } },
        )
    }

    private data class Snapshot(
        val delta: LlmStreamEvent.ReasoningDelta?,
        val dropped: Int,
        val terminal: LlmStreamEvent?,
        val done: Boolean,
        val reasoningEnded: Boolean = false,
    )
}