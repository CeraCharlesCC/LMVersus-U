package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.response

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.serializer.OpponentSpecPublicSerializer
import kotlinx.serialization.Serializable

@Serializable
internal data class ModelsResponse(
    val models: List<@Serializable(with = OpponentSpecPublicSerializer::class) OpponentSpec>
)
