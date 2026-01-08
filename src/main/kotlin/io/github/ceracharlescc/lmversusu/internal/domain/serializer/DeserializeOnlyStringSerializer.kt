package io.github.ceracharlescc.lmversusu.internal.domain.serializer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object DeserializeOnlyStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "io.github.ceracharlescc.lmversusu.internal.domain.serializer.DeserializeOnlyStringSerializer",
            PrimitiveKind.STRING
        )

    override fun deserialize(decoder: Decoder): String =
        decoder.decodeString()

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString("****")
    }
}