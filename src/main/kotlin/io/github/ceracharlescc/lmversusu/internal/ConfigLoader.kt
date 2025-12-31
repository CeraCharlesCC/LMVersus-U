package io.github.ceracharlescc.lmversusu.internal

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

@Suppress("MaxLineLength")
internal object ConfigLoader {

    private const val CONFIG_FILE_NAME = "config.toml"
    private const val DEFAULT_CONFIG_RESOURCE = "config.default.toml"

    private const val ENV_PREFIX = "ENV:"

    /**
     * Loads the application configuration from a TOML file.
     *
     * @param configFile The config file name or path. Defaults to the system property
     *                   `lmversusu.config` or `config.toml`.
     * @return The parsed and validated [AppConfig].
     */
    fun load(configFile: String = System.getProperty("lmversusu.config") ?: CONFIG_FILE_NAME): AppConfig {
        val baseDir = resolveConfigDirectory()
        val configPath = Paths.get(configFile)
        val file = if (configPath.isAbsolute) configPath.toFile() else baseDir.resolve(configPath).toFile()

        val configDirectory = file.parentFile?.toPath()?.normalize() ?: baseDir

        if (!file.exists()) {
            copyDefaultConfig(file)
            copyDefaultLlmConfigs(configDirectory)
            showFirstRunMessage(file)
        }

        val configText = file.readText()

        val parsedConfig = runCatching {
            Toml.decodeFromString<AppConfig>(configText)
        }.getOrElse { throwable ->
            failStartup(
                buildString {
                    appendLine("Failed to parse ${file.name}: ${throwable.message}")
                    appendLine(
                        "Check the file for TOML syntax errors or delete it to regenerate from the default template."
                    )
                }
            )
        }

        val resolvedConfig = validateAndResolve(parsedConfig, configDirectory)

        System.setProperty("lmversusu.configDir", configDirectory.toString())

        return resolvedConfig
    }

    private fun validateAndResolve(config: AppConfig, configDirectory: Path): AppConfig {
        val reasons = mutableListOf<String>()

        val resolvedCrypto = resolveCryptoConfig(
            cryptoConfig = config.sessionCrypto,
            reasons = reasons,
        )

        if (reasons.isNotEmpty()) {
            val message = buildString {
                appendLine("Configuration is invalid:")
                reasons.forEach(::appendLine)
                appendLine()
                appendLine("Edit config.toml and restart the application.")
            }
            failStartup(message)
        }

        return config.copy(
            sessionCrypto = resolvedCrypto,
        )
    }

    private fun resolveCryptoConfig(
        cryptoConfig: AppConfig.SessionCryptoConfig,
        reasons: MutableList<String>,
    ): AppConfig.SessionCryptoConfig {
        val encryptionKeyHex = resolveSecret(cryptoConfig.encryptionKeyHex, "sessionCrypto.encryptionKeyHex", reasons)
        val signKeyHex = resolveSecret(cryptoConfig.signKeyHex, "sessionCrypto.signKeyHex", reasons)

        if (encryptionKeyHex.isBlank()) {
            reasons += "- 'sessionCrypto.encryptionKeyHex' must be set (32 hex characters for 16 bytes / AES-128)."
        } else if (encryptionKeyHex.length != 32) {
            reasons += "- 'sessionCrypto.encryptionKeyHex' must be 32 hex characters (16 bytes / AES-128), got ${encryptionKeyHex.length}."
        }

        if (signKeyHex.isBlank()) {
            reasons += "- 'sessionCrypto.signKeyHex' must be set (64 hex characters for 32 bytes / HMAC-SHA256)."
        } else if (signKeyHex.length != 64) {
            reasons += "- 'sessionCrypto.signKeyHex' must be 64 hex characters (32 bytes / HMAC-SHA256), got ${signKeyHex.length}."
        }

        return cryptoConfig.copy(
            encryptionKeyHex = encryptionKeyHex,
            signKeyHex = signKeyHex,
        )
    }

    private fun resolveConfigDirectory(): Path {
        val codeSource = ConfigLoader::class.java.protectionDomain.codeSource
        val locationPath = Paths.get(codeSource.location.toURI())

        if (!Files.isDirectory(locationPath)) {
            return locationPath.parent ?: Paths.get(".").toAbsolutePath().normalize()
        }

        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
    }

    private fun copyDefaultConfig(targetFile: File) {
        val resourceStream = ConfigLoader::class.java.classLoader
            .getResourceAsStream(DEFAULT_CONFIG_RESOURCE)
            ?: failStartup(
                "Default config resource '$DEFAULT_CONFIG_RESOURCE' is missing from the classpath. " +
                        "Make sure it exists under src/main/resources."
            )

        targetFile.parentFile?.mkdirs()

        resourceStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyDefaultLlmConfigs(configDirectory: Path) {
        val classLoader = ConfigLoader::class.java.classLoader
        val resourceRoot = classLoader.getResource("LLM-Configs") ?: return

        val targetDir = configDirectory.resolve("LLM-Configs")
        Files.createDirectories(targetDir)

        when (resourceRoot.protocol) {
            "file" -> {
                val sourceDir = Paths.get(resourceRoot.toURI())
                if (!Files.isDirectory(sourceDir)) return

                Files.walk(sourceDir).use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                        .forEach { sourcePath ->
                            val relative = sourceDir.relativize(sourcePath)
                            val targetPath = targetDir.resolve(relative.toString())
                            if (!Files.exists(targetPath)) {
                                Files.createDirectories(targetPath.parent)
                                Files.copy(sourcePath, targetPath)
                            }
                        }
                }
            }

            "jar" -> {
                val connection = resourceRoot.openConnection()
                if (connection is JarURLConnection) {
                    val jarFile = connection.jarFile
                    jarFile.use { jar ->
                        val prefix = "LLM-Configs/"
                        jar.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".json") }
                            .forEach { entry ->
                                val relative = entry.name.removePrefix(prefix)
                                val targetPath = targetDir.resolve(relative)
                                if (!Files.exists(targetPath)) {
                                    Files.createDirectories(targetPath.parent)
                                    jar.getInputStream(entry).use { input ->
                                        Files.newOutputStream(targetPath).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            }
                    }
                }
            }

            else -> {
                // Unknown protocol
            }
        }
    }

    private fun resolveSecret(raw: String, fieldName: String, reasons: MutableList<String>): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith(ENV_PREFIX)) {
            val envName = trimmed.removePrefix(ENV_PREFIX).trim()
            if (envName.isEmpty()) {
                reasons += "- '$fieldName' uses ENV: prefix but no environment variable name was given."
                return ""
            }

            val value = System.getenv(envName)?.trim().orEmpty()
            if (value.isEmpty()) {
                reasons += "- '$fieldName' refers to environment variable '$envName', but it is not set or is empty."
            }

            return value
        }

        return trimmed
    }

    private fun showFirstRunMessage(configFile: File): Nothing {
        println("===================================================")
        println(" LMVersus-U – first run")
        println("===================================================")
        println("No configuration file was found.")
        println("A default configuration template has been written to:")
        println("  ${configFile.absolutePath}")
        println()
        println("Please open this file in a text editor and set at least:")
        println("  - llmConfig.primary.apiKey            (LLM API key)")
        println("  - sessionCrypto.encryptionKeyHex      (16-byte hex for session encryption)")
        println("  - sessionCrypto.signKeyHex            (32-byte hex for session signing)")
        println()
        println("You may also adjust:")
        println("  - llmConfig.primary.apiUrl/model      (custom LLM endpoint and model)")
        println("  - llmConfig.primary.provider          (LLM provider name)")
        println("  - rateLimitConfig.*                   (rate limiting settings)")
        println("  - logConfig.*                         (logging settings)")
        println()
        println("When you are done editing, start the application again.")
        exit()
    }

    private fun failStartup(message: String): Nothing {
        println("===================================================")
        println(" LMVersus-U – configuration error")
        println("===================================================")
        println(message.trim())
        println()
        exit()
    }

    private fun exit(): Nothing {
        exitProcess(1)
    }
}
