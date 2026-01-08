package io.github.ceracharlescc.lmversusu.internal.infrastructure.serializer

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object OpponentSpecModeSerializer : JsonContentPolymorphicSerializer<OpponentSpec>(OpponentSpec::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<OpponentSpec> {
        val mode = element.jsonObject["mode"]?.jsonPrimitive?.content
            ?: throw SerializationException("OpponentSpec is missing 'mode'")

        return when (mode) {
            "LIGHTWEIGHT" -> OpponentSpec.Lightweight.serializer()
            "PREMIUM" -> OpponentSpec.Premium.serializer()
            else -> throw SerializationException("Unknown OpponentSpec mode='$mode'")
        }
    }
}