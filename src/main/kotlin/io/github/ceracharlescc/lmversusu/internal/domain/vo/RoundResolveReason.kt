package io.github.ceracharlescc.lmversusu.internal.domain.vo

internal enum class RoundResolveReason {
    /** Both players submitted before the deadline */
    NORMAL,

    /** Neither player submitted before the deadline */
    TIMEOVER_BOTH,

    /** Only the LLM failed to submit before the deadline */
    TIMEOVER_LLM,

    /** Only the human failed to submit before the deadline */
    TIMEOVER_HUMAN,
}
