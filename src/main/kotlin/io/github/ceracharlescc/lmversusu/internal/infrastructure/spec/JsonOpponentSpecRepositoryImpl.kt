package io.github.ceracharlescc.lmversusu.internal.infrastructure.spec

import io.github.ceracharlescc.lmversusu.internal.domain.entity.OpponentSpec
import io.github.ceracharlescc.lmversusu.internal.domain.repository.OpponentSpecRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Loads opponent specifications from JSON files in the LLM-Configs directory.
 */
@Singleton
internal class JsonOpponentSpecRepositoryImpl @Inject constructor() : OpponentSpecRepository {

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

    private fun loadFromDisk(): List<OpponentSpec>? {
        val configDir = resolveConfigDirectory() ?: return null
        val llmConfigsDir = configDir.resolve("LLM-Configs")

        if (!Files.isDirectory(llmConfigsDir)) {
            return null
        }

        return try {
            Files.list(llmConfigsDir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.extension == "json" }
                    .map { parseSpec(it) }
                    .filter { it != null }
                    .map { it!! }
                    .toList()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSpec(path: Path): OpponentSpec? {
        return try {
            val content = path.readText()
            json.decodeFromString<OpponentSpec>(content)
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveConfigDirectory(): Path? {
        val configDirProperty = System.getProperty("lmversusu.configDir")
        return if (!configDirProperty.isNullOrBlank()) {
            Paths.get(configDirProperty)
        } else {
            null
        }
    }
}
