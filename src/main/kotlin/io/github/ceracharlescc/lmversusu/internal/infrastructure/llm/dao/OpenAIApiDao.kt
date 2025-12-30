package io.github.ceracharlescc.lmversusu.internal.infrastructure.llm.dao

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.helpers.ResponseAccumulator
import com.openai.models.ChatModel
import com.openai.models.ResponseFormatJsonObject
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.ResponsesModel
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseTextConfig
import io.github.ceracharlescc.lmversusu.internal.application.port.ExpectedAnswerKind
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ProviderApiProtocol
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ProviderCompat
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ProviderReasoning
import io.github.ceracharlescc.lmversusu.internal.domain.entity.ProviderStructuredOutput
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmAnswer
import io.github.ceracharlescc.lmversusu.internal.domain.vo.streaming.LlmStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import kotlin.jvm.optionals.getOrNull
import kotlin.math.ceil

/**
 * Encapsulates the OpenAI Java client configuration and request mapping.
 * Responsible for translating provider streaming responses into [LlmStreamEvent]
 * and for producing a terminal [LlmStreamEvent.FinalAnswer] or [LlmStreamEvent.Error].
 *
 * @param providerName Human-readable name for logging/diagnostics
 * @param apiUrl Base URL of the OpenAI-compatible API endpoint
 * @param apiKey API key for authentication
 */
internal class OpenAIApiDao(
    val logger: Logger,
    val providerName: String,
    val apiUrl: String,
    val apiKey: String,
    val compat: ProviderCompat = ProviderCompat(),
    val extraBody: Map<String, kotlinx.serialization.json.JsonElement>? = null,
) {
    private companion object {
        const val CHARS_PER_TOKEN_ESTIMATE = 4.0
        const val JSON_SCHEMA_NAME = "llm_answer"
        val RAW_REASONING_FIELDS = listOf("reasoning_content", "reasoning")
    }

    private val client: OpenAIClient = OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(apiUrl)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class PromptParts(
        val system: String,
        val user: String,
    )

    /**
     * Streams an answer from the LLM API.
     *
     * @param model The model identifier (e.g., "gpt-4o-mini")
     * @param prompt The question prompt to send to the model
     * @param choices Optional list of choices for multiple-choice questions
     * @param expectedKind The expected answer type for deterministic JSON templates
     * @param temperature Sampling temperature
     * @param maxTokens Maximum tokens in the response
     * @return A flow of [LlmStreamEvent]s representing the streaming response
     */
    fun streamAnswer(
        model: String,
        prompt: String,
        choices: List<String>?,
        expectedKind: ExpectedAnswerKind,
        temperature: Double,
        maxTokens: Int,
    ): Flow<LlmStreamEvent> {
        val resolvedProtocol = resolveApiProtocol()
        val resolvedStructuredOutput = resolveStructuredOutput()
        val resolvedReasoning = resolveReasoning()
        val prompts = buildPromptParts(prompt, choices, expectedKind)

        return when (resolvedProtocol) {
            ProviderApiProtocol.RESPONSES -> streamViaResponses(
                model = model,
                prompts = prompts,
                temperature = temperature,
                maxTokens = maxTokens,
                structuredOutput = resolvedStructuredOutput,
                reasoning = resolvedReasoning,
            )

            ProviderApiProtocol.CHAT_COMPLETIONS -> streamViaChatCompletions(
                model = model,
                prompts = prompts,
                temperature = temperature,
                maxTokens = maxTokens,
                structuredOutput = resolvedStructuredOutput,
                reasoning = resolvedReasoning,
            )

            ProviderApiProtocol.AUTO -> error("Resolved protocol cannot be AUTO")
        }
    }

    /**
     * Gets a complete answer from the LLM API (non-streaming).
     *
     * @param model The model identifier (e.g., "gpt-4o-mini")
     * @param prompt The question prompt to send to the model
     * @param choices Optional list of choices for multiple-choice questions
     * @param expectedKind The expected answer type for deterministic JSON templates
     * @param temperature Sampling temperature
     * @param maxTokens Maximum tokens in the response
     * @return The complete [LlmAnswer]
     */
    suspend fun getAnswer(
        model: String,
        prompt: String,
        choices: List<String>?,
        expectedKind: ExpectedAnswerKind,
        temperature: Double,
        maxTokens: Int,
    ): LlmAnswer {
        val resolvedProtocol = resolveApiProtocol()
        val resolvedStructuredOutput = resolveStructuredOutput()
        val prompts = buildPromptParts(prompt, choices, expectedKind)

        return withContext(Dispatchers.IO) {
            when (resolvedProtocol) {
                ProviderApiProtocol.RESPONSES -> {
                    val params = buildResponseParams(
                        model = model,
                        prompts = prompts,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        structuredOutput = resolvedStructuredOutput,
                    )
                    val response = client.responses().create(params)
                    val answerText = extractResponseOutputText(response)
                    parseLlmAnswer(answerText)
                }

                ProviderApiProtocol.CHAT_COMPLETIONS -> {
                    val params = buildChatParams(
                        model = model,
                        prompts = prompts,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        structuredOutput = resolvedStructuredOutput,
                    )
                    val completion = client.chat().completions().create(params)
                    val answerText = extractChatCompletionContent(completion)
                    parseLlmAnswer(answerText)
                }

                ProviderApiProtocol.AUTO -> error("Resolved protocol cannot be AUTO")
            }
        }
    }

    private fun streamViaChatCompletions(
        model: String,
        prompts: PromptParts,
        temperature: Double,
        maxTokens: Int,
        structuredOutput: ProviderStructuredOutput,
        reasoning: ProviderReasoning,
    ): Flow<LlmStreamEvent> = callbackFlow {
        val params = buildChatParams(
            model = model,
            prompts = prompts,
            temperature = temperature,
            maxTokens = maxTokens,
            structuredOutput = structuredOutput,
        )

        val answerBuf = StringBuilder()
        val accumulator = ChatCompletionAccumulator.create()

        val stream = client.chat().completions().createStreaming(params)
        val job = launch(Dispatchers.IO) {
            var terminalReason: ChatCompletionChunk.Choice.FinishReason? = null
            var reasoningEnded = false
            try {
                for (chunk in stream.stream()) {
                    accumulator.accumulate(chunk)

                    for (choice in chunk.choices()) {
                        val delta = choice.delta()

                        delta.content().getOrNull()?.let { piece ->
                            if (!reasoningEnded && piece.isNotEmpty()) {
                                reasoningEnded = true
                                trySend(LlmStreamEvent.ReasoningEnded)
                            }
                            answerBuf.append(piece)
                        }

                        if (!reasoningEnded && shouldUseRawReasoning(reasoning)) {
                            val r = extractRawReasoningDelta(delta)
                            if (!r.isNullOrEmpty()) {
                                trySend(
                                    LlmStreamEvent.ReasoningDelta(
                                        deltaText = r,
                                        emittedTokenCount = estimateTokenCount(r),
                                        totalTokenCount = maxTokens,
                                    )
                                )
                            }
                        }

                        choice.finishReason().getOrNull()?.let { fr ->
                            terminalReason = fr
                        }
                    }

                    when (terminalReason) {
                        ChatCompletionChunk.Choice.FinishReason.STOP,
                        ChatCompletionChunk.Choice.FinishReason.LENGTH,
                        ChatCompletionChunk.Choice.FinishReason.CONTENT_FILTER -> break

                        else -> Unit
                    }
                }

                when (terminalReason) {
                    ChatCompletionChunk.Choice.FinishReason.LENGTH,
                    ChatCompletionChunk.Choice.FinishReason.CONTENT_FILTER -> {
                        trySend(
                            LlmStreamEvent.Error(
                                message = "Chat completion was cut off due to $terminalReason",
                                cause = null,
                            )
                        )
                    }

                    else -> {
                        val answerText = answerBuf.toString().ifEmpty {
                            val completion = accumulator.chatCompletion()
                            extractChatCompletionContent(completion)
                        }
                        val answer = parseLlmAnswer(answerText)
                        trySend(LlmStreamEvent.FinalAnswer(answer))
                    }
                }
            } catch (error: Exception) {
                trySend(
                    LlmStreamEvent.Error(
                        message = error.message ?: "Failed to stream chat completion from $providerName",
                        cause = error,
                    )
                )
            } finally {
                stream.close()
                close()
            }
        }

        awaitClose {
            stream.close()
            job.cancel()
        }
    }

    private fun streamViaResponses(
        model: String,
        prompts: PromptParts,
        temperature: Double,
        maxTokens: Int,
        structuredOutput: ProviderStructuredOutput,
        reasoning: ProviderReasoning,
    ): Flow<LlmStreamEvent> = callbackFlow {
        val params = buildResponseParams(
            model = model,
            prompts = prompts,
            temperature = temperature,
            maxTokens = maxTokens,
            structuredOutput = structuredOutput,
        )
        val accumulator = ResponseAccumulator.create()
        val stream = client.responses().createStreaming(params)
        val job = launch(Dispatchers.IO) {
            try {
                stream.stream().forEach { event ->
                    accumulator.accumulate(event)
                    emitResponseReasoningDelta(this@callbackFlow, event, reasoning, maxTokens)
                }
                val response = accumulator.response()
                val answerText = extractResponseOutputText(response)
                val answer = parseLlmAnswer(answerText)
                trySend(LlmStreamEvent.ReasoningEnded)
                trySend(LlmStreamEvent.FinalAnswer(answer))
            } catch (error: Exception) {
                trySend(
                    LlmStreamEvent.Error(
                        message = error.message ?: "Failed to stream response from $providerName",
                        cause = error,
                    )
                )
            } finally {
                stream.close()
                close()
            }
        }
        awaitClose {
            stream.close()
            job.cancel()
        }
    }

    private fun emitResponseReasoningDelta(
        scope: ProducerScope<LlmStreamEvent>,
        event: ResponseStreamEvent,
        reasoning: ProviderReasoning,
        maxTokens: Int,
    ) {
        when (reasoning) {
            ProviderReasoning.NONE -> return

            ProviderReasoning.RAW_REASONING_FIELD -> {
                val delta = event.reasoningTextDelta().getOrNull()?.delta()
                if (delta.isNullOrEmpty()) return
                val emittedTokenCount = estimateTokenCount(delta)
                scope.trySend(
                    LlmStreamEvent.ReasoningDelta(
                        deltaText = delta,
                        emittedTokenCount = emittedTokenCount,
                        totalTokenCount = maxTokens,
                    )
                )
            }

            ProviderReasoning.SUMMARY_ONLY,
            ProviderReasoning.AUTO -> {
                val delta = event.reasoningSummaryTextDelta().getOrNull()?.delta()
                if (delta.isNullOrEmpty()) return
                val emittedTokenCount = estimateTokenCount(delta)
                scope.trySend(
                    LlmStreamEvent.ReasoningDelta(
                        deltaText = delta,
                        emittedTokenCount = emittedTokenCount,
                        totalTokenCount = maxTokens,
                    )
                )
            }
        }
    }

    private fun extractRawReasoningDelta(delta: ChatCompletionChunk.Choice.Delta): String? {
        val additional = delta._additionalProperties()
        for (field in RAW_REASONING_FIELDS) {
            val value = additional[field] ?: continue
            val text = value.asString().getOrNull()
            if (text != null) return text
        }
        return null
    }

    private fun buildChatParams(
        model: String,
        prompts: PromptParts,
        temperature: Double,
        maxTokens: Int,
        structuredOutput: ProviderStructuredOutput,
    ): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .addSystemMessage(prompts.system)
            .addUserMessage(prompts.user)
            .temperature(temperature)
            .maxCompletionTokens(maxTokens.toLong())

        when (structuredOutput) {
            ProviderStructuredOutput.JSON_SCHEMA -> builder.responseFormat(buildChatJsonSchemaFormat())
            ProviderStructuredOutput.JSON_OBJECT -> builder.responseFormat(
                ResponseFormatJsonObject.builder().build()
            )

            ProviderStructuredOutput.NONE -> Unit
        }

        extraBody?.forEach { (key, value) ->
            builder.putAdditionalBodyProperty(key, toJsonValue(value))
        }

        return builder.build()
    }

    private fun buildResponseParams(
        model: String,
        prompts: PromptParts,
        temperature: Double,
        maxTokens: Int,
        structuredOutput: ProviderStructuredOutput,
    ): ResponseCreateParams {
        // For Responses API, combine system and user prompts into input
        val combinedInput = "${prompts.system}\n\n${prompts.user}"
        val builder = ResponseCreateParams.builder().apply {
            model(ResponsesModel.ofString(model))
            input(combinedInput)
            temperature(temperature)
            maxOutputTokens(maxTokens.toLong())
        }

        buildResponseTextConfig(structuredOutput)?.let { builder.text(it) }

        extraBody?.forEach { (key, value) ->
            builder.putAdditionalBodyProperty(key, toJsonValue(value))
        }

        return builder.build()
    }

    private fun buildResponseTextConfig(
        structuredOutput: ProviderStructuredOutput
    ): ResponseTextConfig? {
        return when (structuredOutput) {
            ProviderStructuredOutput.JSON_SCHEMA -> {
                val schema = buildResponseJsonSchemaConfig()
                ResponseTextConfig.builder().format(schema).build()
            }

            ProviderStructuredOutput.JSON_OBJECT -> {
                val jsonObject = ResponseFormatJsonObject.builder().build()
                ResponseTextConfig.builder().format(jsonObject).build()
            }

            ProviderStructuredOutput.NONE -> null
        }
    }

    private fun buildChatJsonSchemaFormat(): ResponseFormatJsonSchema {
        val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
        llmAnswerJsonSchema().forEach { (key, value) -> schema.putAdditionalProperty(key, value) }

        val jsonSchema = ResponseFormatJsonSchema.JsonSchema.builder()
            .name(JSON_SCHEMA_NAME)
            .description("LlmAnswer response schema")
            .schema(schema.build())
            .strict(true)
            .build()

        return ResponseFormatJsonSchema.builder()
            .jsonSchema(jsonSchema)
            .build()
    }

    private fun buildResponseJsonSchemaConfig(): ResponseFormatTextJsonSchemaConfig {
        val schema = ResponseFormatTextJsonSchemaConfig.Schema.builder()
        llmAnswerJsonSchema().forEach { (key, value) -> schema.putAdditionalProperty(key, value) }

        return ResponseFormatTextJsonSchemaConfig.builder()
            .name(JSON_SCHEMA_NAME)
            .description("LlmAnswer response schema")
            .schema(schema.build())
            .strict(true)
            .build()
    }

    private fun extractChatCompletionContent(completion: ChatCompletion): String {
        val message = completion.choices().firstOrNull()?.message()
        return message?.content()?.getOrNull().orEmpty()
    }

    private fun extractResponseOutputText(response: Response): String {
        val texts = response.output()
            .mapNotNull { it.message().getOrNull() }
            .flatMap { it.content() }
            .mapNotNull { it.outputText().getOrNull()?.text() }

        return texts.joinToString("")
    }

    private fun parseLlmAnswer(text: String): LlmAnswer {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Empty response payload from $providerName")
        }

        runCatching { json.decodeFromString(LlmAnswer.serializer(), trimmed) }
            .getOrNull()
            ?.let { return it }

        var start = trimmed.indexOf('{')
        while (start != -1) {
            val candidate = extractJsonObjectStartingAt(trimmed, start)
            if (candidate != null) {
                runCatching { json.decodeFromString(LlmAnswer.serializer(), candidate) }
                    .getOrNull()
                    ?.let { return it }
            }
            start = trimmed.indexOf('{', start + 1)
        }

        throw IllegalArgumentException(
            "Failed to locate a valid LlmAnswer JSON object in response from $providerName"
        )
    }

    private fun extractJsonObjectStartingAt(text: String, start: Int): String? {
        if (start !in text.indices || text[start] != '{') return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val ch = text[index]

            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }

        return null
    }

    private fun buildPromptParts(
        prompt: String,
        choices: List<String>?,
        expectedKind: ExpectedAnswerKind,
    ): PromptParts {
        val user = buildString {
            append(prompt.trim())

            if (!choices.isNullOrEmpty()) {
                append("\n\nChoices (0-based index):\n")
                choices.forEachIndexed { index, choice ->
                    append(index).append(") ").append(choice).append('\n')
                }
            }
        }.trim()

        val system = buildString {
            appendLine("You are the opponent player in a quiz game.")
            appendLine("Return ONLY valid JSON (no markdown, no code fences, no extra text).")
            appendLine("Output in this format:")
            appendLine()

            when (expectedKind) {
                ExpectedAnswerKind.MULTIPLE_CHOICE -> {
                    appendLine("""{"finalAnswer":{"type":"multiple_choice","choiceIndex":0}}""")
                    appendLine()
                    appendLine("Rules:")
                    appendLine("- choiceIndex MUST be a 0-based index into the provided choices.")
                }

                ExpectedAnswerKind.INTEGER -> {
                    appendLine("""{"finalAnswer":{"type":"integer","value":0}}""")
                    appendLine()
                    appendLine("Rules:")
                    appendLine("- value must be an integer.")
                }

                ExpectedAnswerKind.FREE_TEXT -> {
                    appendLine("""{"finalAnswer":{"type":"free_text","text":"..."}}""")
                    appendLine()
                    appendLine("Rules:")
                    appendLine("- text must be a plain string answer.")
                }
            }
        }.trim()

        return PromptParts(system = system, user = user)
    }

    private fun llmAnswerJsonSchema(): Map<String, JsonValue> {
        val multipleChoice = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "type" to mapOf("enum" to listOf("multiple_choice")),
                "choiceIndex" to mapOf("type" to "integer", "minimum" to 0),
            ),
            "required" to listOf("type", "choiceIndex"),
            "additionalProperties" to false,
        )
        val integerAnswer = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "type" to mapOf("enum" to listOf("integer")),
                "value" to mapOf("type" to "integer"),
            ),
            "required" to listOf("type", "value"),
            "additionalProperties" to false,
        )
        val freeText = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "type" to mapOf("enum" to listOf("free_text")),
                "text" to mapOf("type" to "string"),
            ),
            "required" to listOf("type", "text"),
            "additionalProperties" to false,
        )

        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "finalAnswer" to mapOf(
                    "type" to "object",
                    "oneOf" to listOf(multipleChoice, integerAnswer, freeText),
                ),
            ),
            "required" to listOf("finalAnswer"),
            "additionalProperties" to false,
        )

        return JsonValue.from(schema).asObject().orElseThrow {
            IllegalStateException("Failed to build JSON schema for LlmAnswer")
        }
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isEmpty()) return 0
        return ceil(text.length / CHARS_PER_TOKEN_ESTIMATE).toInt().coerceAtLeast(1)
    }

    private fun resolveApiProtocol(): ProviderApiProtocol {
        return when (compat.apiProtocol) {
            ProviderApiProtocol.AUTO -> ProviderApiProtocol.RESPONSES
            else -> compat.apiProtocol
        }
    }

    private fun resolveStructuredOutput(): ProviderStructuredOutput = compat.structuredOutput

    private fun resolveReasoning(): ProviderReasoning = compat.reasoning

    private fun shouldUseRawReasoning(reasoning: ProviderReasoning): Boolean {
        return reasoning == ProviderReasoning.RAW_REASONING_FIELD || reasoning == ProviderReasoning.AUTO
    }

    private fun toJsonValue(element: kotlinx.serialization.json.JsonElement): JsonValue {
        return JsonValue.from(element.toRawValue())
    }

    private fun kotlinx.serialization.json.JsonElement.toRawValue(): Any? {
        return when (this) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (isString) content
                else {
                    val contentLow = content.lowercase()
                    when (contentLow) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
                    }
                }
            }

            is kotlinx.serialization.json.JsonObject -> this.mapValues { it.value.toRawValue() }
            is kotlinx.serialization.json.JsonArray -> this.map { it.toRawValue() }
        }
    }
}
