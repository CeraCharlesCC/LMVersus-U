package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.serializer

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
            element<String>("displayName")
            element<String?>("description")
            element<String?>("descriptionI18nKey")
        }

    override fun serialize(encoder: Encoder, value: OpponentSpec) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.id)
            encodeStringElement(descriptor, 1, value.mode.name)
            encodeStringElement(descriptor, 2, value.displayName)
            encodeNullableSerializableElement(descriptor, 3, String.serializer(), value.description)
            encodeNullableSerializableElement(descriptor, 4, String.serializer(), value.descriptionI18nKey)
        }
    }

    override fun deserialize(decoder: Decoder): OpponentSpec {
        throw SerializationException("OpponentSpecPublicSerializer is output-only")
    }
}