package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import kotlinx.serialization.Serializable

/**
 * Represents an opponent specification loaded from a modelspec JSON file.
 * Contains the metadata needed to display and configure an opponent.
 */
@Serializable
internal data class OpponentSpec(
    val id: String,
    val mode: GameMode,
    val displayName: String,
    val llmProfile: LlmProfile,
)
