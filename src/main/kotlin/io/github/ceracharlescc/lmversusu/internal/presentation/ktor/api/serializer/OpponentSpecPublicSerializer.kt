package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.serializer

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentMetadata
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure

@OptIn(ExperimentalSerializationApi::class)
internal object OpponentSpecPublicSerializer : KSerializer<OpponentSpec> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("OpponentSpecPublic") {
            element<String>("id")
            element<String>("mode")
            element("metadata", OpponentMetadata.serializer().descriptor)
        }

    override fun serialize(encoder: Encoder, value: OpponentSpec) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.id)
            encodeStringElement(descriptor, 1, value.mode.name)
            encodeSerializableElement(descriptor, 2, OpponentMetadata.serializer(), value.metadata)
        }
    }

    override fun deserialize(decoder: Decoder): OpponentSpec {
        throw SerializationException("OpponentSpecPublicSerializer is output-only")
    }
}