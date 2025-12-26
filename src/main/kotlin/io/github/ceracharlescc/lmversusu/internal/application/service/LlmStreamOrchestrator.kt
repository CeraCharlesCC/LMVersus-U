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

@Singleton
internal class LlmStreamOrchestrator @Inject constructor() {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun apply(policy: StreamingPolicy, upstream: Flow<LlmStreamEvent>): Flow<LlmStreamEvent> = flow {
        coroutineScope {
            val lock = Mutex()

            val buffer = ArrayDeque<LlmStreamEvent.ReasoningDelta>()
            var bufferedChars = 0

            var droppedPending = 0
            var terminal: LlmStreamEvent? = null
            var upstreamDone = false

            val updated = Channel<Unit>(Channel.CONFLATED)
            val terminalArrived = Channel<Unit>(Channel.CONFLATED)

            val collector = launch {
                try {
                    upstream.collect { event ->
                        lock.withLock {
                            when (event) {
                                is LlmStreamEvent.ReasoningDelta -> {
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
                                    // ignore upstream truncation
                                }
                            }
                        }

                        updated.trySend(Unit)

                        if (event is LlmStreamEvent.FinalAnswer || event is LlmStreamEvent.Error) {
                            terminalArrived.trySend(Unit)
                        }
                    }
                } finally {
                    lock.withLock { upstreamDone = true }
                    updated.trySend(Unit)
                }
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
                    Snapshot(
                        delta = if (buffer.isEmpty()) null else buffer.removeFirst(),
                        dropped = droppedPending.also { droppedPending = 0 },
                        terminal = terminal,
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
                    emit(snapshot.terminal)
                    break
                }

                if (snapshot.done) break

                select<Unit> {
                    updated.onReceive { }
                    onTimeout(50) { }
                }
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
