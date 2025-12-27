package io.github.ceracharlescc.lmversusu.internal.application.service

import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamingPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
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
 *    emitting the first delta, allowing the upstream to accumulate initial content.
 *
 * 2. Baseline Pacing: Deltas are emitted at approximately [StreamingPolicy.targetTokensPerSecond].
 *    If the policy specifies ≤ 0, pacing is disabled and deltas are forwarded immediately.
 *
 * 3. Burst Mode: Once a terminal event ([LlmStreamEvent.FinalAnswer] or
 *    [LlmStreamEvent.Error]) arrives, the emission rate accelerates by
 *    [StreamingPolicy.burstMultiplierOnFinal] to drain remaining buffered deltas quickly.
 *
 * 4. Back-Pressure & Truncation: When the buffer grows beyond
 *    [StreamingPolicy.maxBufferedChars], the oldest deltas are dropped (and later
 *    reported as [LlmStreamEvent.ReasoningTruncated]) so that the UI always shows the
 *    most recent reasoning.
 *
 * ## maxBufferedChars Caveat
 *
 * The drop loop intentionally guarantees at least one delta remains in the buffer
 * (`buffer.size > 1` guard). This design choice preserves semantic integrity: the
 * orchestrator never tampers with the *content* of deltas (e.g., by splitting a large
 * chunk). As a consequence, a single chunk larger than `maxBufferedChars` will be
 * buffered in its entirety. In practice, LLM providers stream fine-grained token
 * deltas, so this edge-case is virtually unreachable under normal operation.
 *
 * ## Implementation Notes
 *
 * - Concurrency model: The upstream flow is collected in a separate coroutine (`collector`).
 *   Shared state is protected by a [Mutex]. The emitter loop runs in the caller's coroutine.
 * - Cooperative cancellation: If the emitter breaks out of its loop (due to terminal event
 *   or upstream completion), the collector coroutine is explicitly cancelled.
 * - Timing: All `delay` calls honor the caller's [kotlin.coroutines.CoroutineContext]
 *
 * @see StreamingPolicy
 * @see LlmStreamEvent
 */
@Singleton
internal class LlmStreamOrchestrator @Inject constructor() {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun apply(policy: StreamingPolicy, upstream: Flow<LlmStreamEvent>): Flow<LlmStreamEvent> = flow {
        coroutineScope {
            val lock = Mutex()

            val buffer = ArrayDeque<LlmStreamEvent.ReasoningDelta>()
            var bufferedChars = 0

            var droppedPending = 0
            var upstreamTruncation: LlmStreamEvent.ReasoningTruncated? = null
            var terminal: LlmStreamEvent? = null
            var upstreamDone = false

            val updated = Channel<Unit>(Channel.CONFLATED)
            val terminalArrived = Channel<Unit>(Channel.CONFLATED)

            val collector = launch {
                runCatching {
                    upstream.collect { event ->
                        lock.withLock {
                            when (event) {
                                is LlmStreamEvent.ReasoningDelta -> {
                                    if (terminal != null) return@withLock // Ignore deltas after terminal
                                    buffer.addLast(event)
                                    bufferedChars += event.deltaText.length

                                    while (bufferedChars > policy.maxBufferedChars && buffer.size > 1) {
                                        val oldest = buffer.removeFirst()
                                        bufferedChars -= oldest.deltaText.length
                                        droppedPending += oldest.deltaText.length
                                    }
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

                        if (event is LlmStreamEvent.FinalAnswer || event is LlmStreamEvent.Error) {
                            terminalArrived.trySend(Unit)
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

            if (policy.revealDelayMs > 0) delay(policy.revealDelayMs)

            yield()

            while (terminalArrived.tryReceive().isSuccess) {
                burstMode = true
            }

            while (true) {
                val snapshot = lock.withLock {
                    val nextDelta = if (buffer.isEmpty()) null else buffer.removeFirst().also {
                        bufferedChars -= it.deltaText.length
                        if (bufferedChars < 0) bufferedChars = 0
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
                        val pendingTruncation = lock.withLock { upstreamTruncation.also { upstreamTruncation = null } }
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

    private data class Snapshot(
        val delta: LlmStreamEvent.ReasoningDelta?,
        val dropped: Int,
        val terminal: LlmStreamEvent?,
        val done: Boolean,
    )
}
