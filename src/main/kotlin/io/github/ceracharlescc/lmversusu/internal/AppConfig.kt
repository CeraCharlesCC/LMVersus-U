package io.github.ceracharlescc.lmversusu.internal

import kotlinx.serialization.Serializable

@Serializable
internal data class AppConfig(
    val serverConfig: ServerConfig = ServerConfig(),
    val logConfig: LogConfig = LogConfig(),
    val rateLimitConfig: RateLimitConfig = RateLimitConfig(),
    val sessionLimitConfig: SessionLimitConfig = SessionLimitConfig(),
    val sessionCrypto: SessionCryptoConfig = SessionCryptoConfig(),
) {

    @Serializable
    data class ServerConfig(
        val bindHost: String = DEFAULT_BIND_HOST,
        val bindPort: Int = DEFAULT_BIND_PORT,
        val corsAllowedHosts: List<String> = CORS_ALLOWED_HOSTS,
        val debug: Boolean = DEFAULT_DEBUG
    ) {
        companion object {
            const val DEFAULT_BIND_HOST = "localhost"
            const val DEFAULT_BIND_PORT = 8080
            val CORS_ALLOWED_HOSTS = listOf<String>()
            const val DEFAULT_DEBUG = false
        }
    }

    @Serializable
    data class LogConfig(
        val rootLogLevel: String = DEFAULT_LOG_LEVEL,
        val appLogLevel: String = DEFAULT_LOG_LEVEL,
        val format: String = DEFAULT_LOG_FORMAT,
    ) {
        companion object {
            const val DEFAULT_LOG_LEVEL = "INFO"
            const val DEFAULT_LOG_FORMAT = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
        }
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
    data class SessionLimitConfig(
        val actorMailboxCapacity: Int = DEFAULT_ACTOR_MAILBOX_CAPACITY,
        val websocketMessageWindowMillis: Long = DEFAULT_WEBSOCKET_MESSAGE_WINDOW_MILLIS,
        val websocketMessageMaxMessages: Int = DEFAULT_WEBSOCKET_MESSAGE_MAX_MESSAGES,
        val dailyWindowMillis: Long = DEFAULT_DAILY_WINDOW_MILLIS,
        val premium: ModeLimitConfig = ModeLimitConfig.premiumDefaults(),
        val lightweight: ModeLimitConfig = ModeLimitConfig.lightweightDefaults(),
    ) {
        companion object {
            const val DEFAULT_ACTOR_MAILBOX_CAPACITY = 256
            const val DEFAULT_WEBSOCKET_MESSAGE_WINDOW_MILLIS = 5_000L
            const val DEFAULT_WEBSOCKET_MESSAGE_MAX_MESSAGES = 20
            const val DEFAULT_DAILY_WINDOW_MILLIS = 86_400_000L
        }
    }

    @Serializable
    data class ModeLimitConfig(
        val perPersonDailyLimit: Int,
        val perPersonWindowMillis: Long,
        val perPersonWindowLimit: Int,
        val globalWindowMillis: Long,
        val globalWindowLimit: Int,
        val globalDailyLimit: Int,
        val maxActiveSessions: Int,
    ) {
        companion object {
            fun premiumDefaults(): ModeLimitConfig = ModeLimitConfig(
                perPersonDailyLimit = 2,
                perPersonWindowMillis = 60_000L,
                perPersonWindowLimit = 2,
                globalWindowMillis = 60_000L,
                globalWindowLimit = 5,
                globalDailyLimit = 100,
                maxActiveSessions = 5,
            )

            fun lightweightDefaults(): ModeLimitConfig = ModeLimitConfig(
                perPersonDailyLimit = 20,
                perPersonWindowMillis = 60_000L,
                perPersonWindowLimit = 5,
                globalWindowMillis = 60_000L,
                globalWindowLimit = 60,
                globalDailyLimit = 1_000,
                maxActiveSessions = 200,
            )
        }
    }

    @Serializable
    data class SessionCryptoConfig(
        val enableSecureCookie: Boolean = DEFAULT_SECURE_MODE,
        val encryptionKeyHex: String = "",
        val signKeyHex: String = "",
    ) {
        companion object {
            const val DEFAULT_SECURE_MODE = true
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
