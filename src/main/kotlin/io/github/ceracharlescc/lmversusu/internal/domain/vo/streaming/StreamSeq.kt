package io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
internal value class StreamSeq(val value: Long) {
    init {
        require(value >= 0L) { "StreamSeq must be non-negative, got $value" }
    }

    fun next(): StreamSeq = StreamSeq(value + 1L)
}