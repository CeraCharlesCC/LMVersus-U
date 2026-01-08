package io.github.ceracharlescc.lmversusu.internal.domain.entity

import io.github.ceracharlescc.lmversusu.internal.domain.vo.LlmProfile
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.StreamingPolicy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.full.memberProperties

@Serializable(with = OpponentSpecModeSerializer::class)
internal sealed interface OpponentSpec {
    val id: String
    val mode: GameMode
    val metadata: OpponentMetadata
    val llmProfile: LlmProfile
    val streaming: StreamingPolicy

    @Serializable
    data class Lightweight(
        override val id: String,
        override val mode: GameMode = GameMode.LIGHTWEIGHT,
        override val metadata: OpponentMetadata,
        override val llmProfile: LlmProfile,
        override val streaming: StreamingPolicy,
        val questionSetPath: String,
        val questionSetDisplayName: String,
        val datasetPath: String,
    ) : OpponentSpec

    @Serializable
    data class Premium(
        override val id: String,
        override val mode: GameMode = GameMode.PREMIUM,
        override val metadata: OpponentMetadata,
        override val llmProfile: LlmProfile,
        override val streaming: StreamingPolicy,
        val questionSetPath: String,
        val questionSetDisplayName: String,
        val provider: ProviderConfig,
    ) : OpponentSpec

    @Serializable
    data class OpponentMetadata(
        val displayName: String,
        val description: String? = null,
        val descriptionI18nKey: String? = null,
    )

    @Serializable
    data class ProviderConfig(
        val providerName: String,
        val apiUrl: String,
        val apiKey: String,
        val compat: ProviderCompat = ProviderCompat(),
        val extraBody: Map<String, JsonElement>? = null,
    ) {
        @Serializable
        data class ProviderCompat(
            val apiProtocol: ProviderApiProtocol = ProviderApiProtocol.AUTO,
            val structuredOutput: ProviderStructuredOutput = ProviderStructuredOutput.JSON_OBJECT,
            val reasoning: ProviderReasoning = ProviderReasoning.AUTO,
        ) {
            @Serializable
            internal enum class ProviderApiProtocol {
                AUTO,
                RESPONSES,
                CHAT_COMPLETIONS,
            }

            @Serializable
            internal enum class ProviderStructuredOutput {
                JSON_SCHEMA,
                JSON_OBJECT,
                NONE,
            }

            @Serializable
            internal enum class ProviderReasoning {
                AUTO,
                SUMMARY_ONLY,
                RAW_REASONING_FIELD,
                NONE,
            }
        }

        private fun toSafeString(): String {
            val kClass = ProviderConfig::class
            val props = kClass.memberProperties
            val parts = props.joinToString(", ") { prop ->
                val value = when (prop.name) {
                    "apiKey" -> "****"
                    else -> prop.get(this)
                }
                "${prop.name}=$value"
            }
            return "${kClass.simpleName}($parts)"
        }

        override fun toString(): String = toSafeString()
    }
}

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