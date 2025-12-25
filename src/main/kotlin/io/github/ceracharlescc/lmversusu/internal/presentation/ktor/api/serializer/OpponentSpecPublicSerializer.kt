package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.serializer

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpecModeSerializer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure

internal object OpponentSpecPublicSerializer : KSerializer<OpponentSpec> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("OpponentSpecPublic") {
            element<String>("id")
            element<String>("mode")
            element<String>("displayName")
            element("llmProfile", LlmProfile.serializer().descriptor)
        }

    override fun serialize(encoder: Encoder, value: OpponentSpec) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.id)
            encodeStringElement(descriptor, 1, value.mode.name)
            encodeStringElement(descriptor, 2, value.displayName)
            encodeSerializableElement(descriptor, 3, LlmProfile.serializer(), value.llmProfile)
        }
    }

    override fun deserialize(decoder: Decoder): OpponentSpec {
        return OpponentSpecModeSerializer.deserialize(decoder)
    }
}