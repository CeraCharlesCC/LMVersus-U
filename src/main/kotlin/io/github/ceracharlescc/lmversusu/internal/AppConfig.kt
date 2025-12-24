package io.github.ceracharlescc.lmversusu.internal

import kotlinx.serialization.Serializable
import kotlin.reflect.full.memberProperties

@Serializable
internal data class AppConfig(
    val logConfig: LogConfig = LogConfig(),
    val llmConfig: LlmConfig = LlmConfig(),
    val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
    val sessionCrypto: SessionCryptoConfig = SessionCryptoConfig(),
) {

    @Serializable
    data class LogConfig(
        val level: String = DEFAULT_LOG_LEVEL,
        val format: String = DEFAULT_LOG_FORMAT,
    ) {
        companion object {
            const val DEFAULT_LOG_LEVEL = "INFO"
            const val DEFAULT_LOG_FORMAT = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
        }
    }

    @Serializable
    data class LlmConfig(
        val primary: LlmProviderConfig = LlmProviderConfig(),
        val fallback: LlmProviderConfig? = null,
    ) {
        override fun toString(): String = "LlmConfig(primary=$primary, fallback=$fallback)"
    }

    @Serializable
    data class LlmProviderConfig(
        val apiKey: String = "",
        val apiUrl: String = DEFAULT_API_URL,
        val model: String = DEFAULT_MODEL,
        val provider: String = DEFAULT_PROVIDER,
    ) {
        private companion object {
            const val DEFAULT_API_URL = "https://api.openai.com/v1"
            const val DEFAULT_MODEL = "gpt-4o-mini"
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
    data class RateLimitConfig(
        val globalHttpGateKey: String = DEFAULT_GLOBAL_HTTP_GATE_KEY,
        val globalHttpWindowMillis: Long = DEFAULT_GLOBAL_HTTP_WINDOW_MILLIS,
        val globalHttpMaxRequests: Int = DEFAULT_GLOBAL_HTTP_MAX_REQUESTS,
        val globalLlmGateKey: String = DEFAULT_GLOBAL_LLM_GATE_KEY,
        val globalLlmWindowMillis: Long = DEFAULT_GLOBAL_LLM_WINDOW_MILLIS,
        val globalLlmMaxRequests: Int = DEFAULT_GLOBAL_LLM_MAX_REQUESTS,
    ) {
        companion object {
            const val DEFAULT_GLOBAL_HTTP_GATE_KEY = "http:global"
            const val DEFAULT_GLOBAL_HTTP_WINDOW_MILLIS = 600_000L
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

    @Serializable
    data class SessionCryptoConfig(
        val enableSecureCookie: Boolean = DEFAULT_SECURE_MODE,
        val encryptionKeyHex: String = "",
        val signKeyHex: String = "",
    ) {
        companion object {
            const val DEFAULT_SECURE_MODE = false
        }

        fun requireKeys(): Keys {
            val encryptionKey = encryptionKeyHex.decodeHexOrThrow("encryptionKeyHex")
            val signKey = signKeyHex.decodeHexOrThrow("signKeyHex")

            require(encryptionKey.size == 16) {
                "encryptionKeyHex must be 16 bytes (32 hex chars) for AES-128, but was ${encryptionKey.size} bytes"
            }
            require(signKey.size == 32) {
                "signKeyHex must be 32 bytes (64 hex chars) for HMAC-SHA256, but was ${signKey.size} bytes"
            }

            return Keys(encryptionKey = encryptionKey, signKey = signKey)
        }

        data class Keys(val encryptionKey: ByteArray, val signKey: ByteArray) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Keys

                if (!encryptionKey.contentEquals(other.encryptionKey)) return false
                if (!signKey.contentEquals(other.signKey)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = encryptionKey.contentHashCode()
                result = 31 * result + signKey.contentHashCode()
                return result
            }
        }

        private fun String.decodeHexOrThrow(field: String): ByteArray {
            require(this.isNotBlank()) { "$field is blank" }
            require(length % 2 == 0) { "$field hex length must be even" }
            return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}
