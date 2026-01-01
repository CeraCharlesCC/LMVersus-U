package io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.serializer

import io.github.ceracharlescc.lmversusu.internal.domain.entity.LeaderboardEntry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure

internal object LeaderboardEntryPublicSerializer : KSerializer<LeaderboardEntry> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("LeaderboardEntryPublic") {
            element<Int>("rank")
            element<String>("gameMode")
            element<String>("opponentLlmName")
            element<String>("questionSetDisplayName")
            element<String>("nickname")
            element<Double>("bestScore")
        }

    override fun serialize(encoder: Encoder, value: LeaderboardEntry) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, value.rank)
            encodeStringElement(descriptor, 1, value.gameMode.name)
            encodeStringElement(descriptor, 2, value.opponentLlmName)
            encodeStringElement(descriptor, 3, value.questionSetDisplayName)
            encodeStringElement(descriptor, 4, value.nickname)
            encodeDoubleElement(descriptor, 5, value.bestScore)
        }
    }

    override fun deserialize(decoder: Decoder): LeaderboardEntry {
        throw UnsupportedOperationException("Deserialization of LeaderboardEntryPublic is not supported")
    }
}
