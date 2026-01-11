package io.github.ceracharlescc.lmversusu.internal.infrastructure.webhook

import io.github.ceracharlescc.lmversusu.internal.AppConfig
import io.github.ceracharlescc.lmversusu.internal.application.port.WebhookNotifier
import io.github.ceracharlescc.lmversusu.internal.domain.vo.WebhookEvent
import io.github.ceracharlescc.lmversusu.internal.domain.vo.WebhookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class WebhookNotifierImpl @Inject constructor(
    private val appConfig: AppConfig,
    private val logger: Logger,
) : WebhookNotifier {
    private val json = Json

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(appConfig.webhookConfig.timeoutMillis))
        .build()

    override suspend fun notify(event: WebhookEvent) {
        val config = appConfig.webhookConfig
        if (!config.enabled || config.url.isBlank()) return
        runCatching {
            val payload = buildPayload(event, config.format)
            val body = json.encodeToString(JsonObject.serializer(), payload)

            val request = HttpRequest.newBuilder()
                .uri(URI.create(config.url))
                .timeout(Duration.ofMillis(config.timeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

            withContext(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            }
        }.onFailure { throwable ->
            logger.warn("Failed to send webhook for session {}", event.sessionId, throwable)
        }.onSuccess { response ->
            if (response.statusCode() >= 300) {
                logger.warn(
                    "Webhook responded with status {} for session {}",
                    response.statusCode(),
                    event.sessionId
                )
            }
        }
    }

    private fun buildPayload(event: WebhookEvent, format: WebhookFormat): JsonObject {
        return when (format) {
            WebhookFormat.MINIMAL -> buildMinimalPayload(event)
            WebhookFormat.DETAILED -> buildDetailedPayload(event)
        }
    }

    private fun buildMinimalPayload(event: WebhookEvent): JsonObject = buildJsonObject {
        put("type", eventType(event))
        put("sessionId", event.sessionId.toString())
        put("occurredAt", event.occurredAt.toString())
        put("mode", event.mode.name.lowercase())
    }

    private fun buildDetailedPayload(event: WebhookEvent): JsonObject = buildJsonObject {
        put("type", eventType(event))
        put("sessionId", event.sessionId.toString())
        put("occurredAt", event.occurredAt.toString())
        put("mode", event.mode.name.lowercase())
        when (event) {
            is WebhookEvent.SessionStarted -> {
                put("opponentSpecId", event.opponentSpecId)
                putObject("human") {
                    put("playerId", event.humanPlayerId.toString())
                    put("nickname", event.humanNickname)
                }
                putObject("llm") {
                    put("nickname", event.llmNickname)
                    put("profileName", event.llmProfileName)
                }
                put("questionSetDisplayName", event.questionSetDisplayName)
            }

            is WebhookEvent.SessionCompleted -> {
                put("humanTotalScore", event.humanTotalScore)
                put("llmTotalScore", event.llmTotalScore)
                put("winner", event.winner)
                put("roundsPlayed", event.roundsPlayed)
                put("totalRounds", event.totalRounds)
                put("durationMs", event.durationMs)
            }
        }
    }

    private fun eventType(event: WebhookEvent): String = when (event) {
        is WebhookEvent.SessionStarted -> "session_started"
        is WebhookEvent.SessionCompleted -> "session_completed"
    }

    private fun JsonObjectBuilder.put(name: String, value: String) {
        put(name, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.put(name: String, value: Number) {
        put(name, JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putObject(name: String, block: JsonObjectBuilder.() -> Unit) {
        put(name, buildJsonObject(block))
    }
}
