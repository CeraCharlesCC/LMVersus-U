package io.github.ceracharlescc.lmversusu.internal.infrastructure.repository

import io.github.ceracharlescc.lmversusu.internal.di.annotation.ConfigDirectory
import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

@Singleton
internal class JsonOpponentSpecRepositoryImpl @Inject constructor(
    @param:ConfigDirectory private val configDirectory: Path
) : OpponentSpecRepository {

    private companion object {
        const val ENV_PREFIX = "ENV:"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var cachedSpecs: List<OpponentSpec>? = null

    override fun getAllSpecs(): List<OpponentSpec>? {
        cachedSpecs?.let { return it }

        return synchronized(this) {
            cachedSpecs ?: loadFromDisk().also { cachedSpecs = it }
        }
    }

    override fun findById(id: String): OpponentSpec? =
        getAllSpecs()?.firstOrNull { it.id == id }

    private fun loadFromDisk(): List<OpponentSpec>? {
        val llmConfigsDir = configDirectory.resolve("LLM-Configs")

        if (!Files.isDirectory(llmConfigsDir)) return null

        return try {
            Files.list(llmConfigsDir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.extension == "json" }
                    .map { parseSpec(it) }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSpec(path: Path): OpponentSpec? {
        return try {
            val content = path.readText()
            val spec = json.decodeFromString<OpponentSpec>(content)
            resolveEnvSecrets(spec)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveEnvSecrets(spec: OpponentSpec): OpponentSpec {
        return when (spec) {
            is OpponentSpec.Lightweight -> spec

            is OpponentSpec.Premium -> {
                val resolvedProvider = spec.provider.copy(
                    providerName = resolveSecret(spec.provider.providerName),
                    apiUrl = resolveSecret(spec.provider.apiUrl),
                    apiKey = resolveSecret(spec.provider.apiKey),
                )

                spec.copy(provider = resolvedProvider)
            }
        }
    }

    private fun resolveSecret(raw: String): String {
        val trimmed = raw.trim()

        if (!trimmed.startsWith(ENV_PREFIX)) return trimmed

        val envName = trimmed.removePrefix(ENV_PREFIX).trim()
        if (envName.isEmpty()) return ""

        return System.getenv(envName)?.trim().orEmpty()
    }
}