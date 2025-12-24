package io.github.ceracharlescc.versuslm.internal

import kotlinx.serialization.Serializable
import kotlin.reflect.full.memberProperties

@Serializable
internal class AppConfig {

    @Serializable
    internal data class LogConfig(
        val level: String = DEFAULT_LOG_LEVEL,
        val format: String = DEFAULT_LOG_FORMAT,
    ) {
        companion object {
            const val DEFAULT_LOG_LEVEL = "INFO"
            const val DEFAULT_LOG_FORMAT = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
        }
    }

    @Serializable
    internal data class LlmConfig(
        val primary: LlmProviderConfig = LlmProviderConfig(),
        val fallback: LlmProviderConfig? = null,
    ) {
        override fun toString(): String = "LlmConfig(primary=$primary, fallback=$fallback)"
    }

    @Serializable
    internal data class LlmProviderConfig(
        val apiKey: String = "",
        val apiUrl: String = DEFAULT_API_URL,
        val model: String = DEFAULT_MODEL,
        val provider: String = DEFAULT_PROVIDER,
    ) {
        private companion object {
            const val DEFAULT_API_URL = "https://api.openai.com/v1"
            const val DEFAULT_MODEL = "gpt-5-mini"
            const val DEFAULT_PROVIDER = "openai"
        }

        private fun toSafeString(): String {
            val kClass = LlmProviderConfig::class
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

    @Serializable
    internal data class RateLimitConfig(
        val globalLlmGateKey: String = DEFAULT_GLOBAL_LLM_GATE_KEY,
        val globalLlmWindowMillis: Long = DEFAULT_GLOBAL_LLM_WINDOW_MILLIS,
        val globalLlmMaxRequests: Int = DEFAULT_GLOBAL_LLM_MAX_REQUESTS,
    ) {
        companion object {
            const val DEFAULT_GLOBAL_HTTP_GATE_KEY = "http:global"
            const val DEFAULT_GLOBAL_HTTP_WINDOW_MILLIS = 60_000L
            const val DEFAULT_GLOBAL_HTTP_MAX_REQUESTS = 100

            const val DEFAULT_USEER_HTTP_GATE_KEY = "http:user:"
            const val DEFAULT_USER_HTTP_WINDOW_MILLIS = 60_000L
            const val DEFAULT_USER_HTTP_MAX_REQUESTS = 10

            const val DEFAULT_GLOBAL_LLM_GATE_KEY = "llm:global"
            const val DEFAULT_GLOBAL_LLM_WINDOW_MILLIS = 60_000L
            const val DEFAULT_GLOBAL_LLM_MAX_REQUESTS = 20

            const val DEFAULT_USER_LLM_GATE_KEY = "llm:user:"
            const val DEFAULT_USER_LLM_WINDOW_MILLIS = 120_000L
            const val DEFAULT_USER_LLM_MAX_REQUESTS = 3


        }
    }

}