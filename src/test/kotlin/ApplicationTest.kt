import io.github.ceracharlescc.lmversusu.internal.TestConfigFactory
import io.github.ceracharlescc.lmversusu.internal.module
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.HeartbeatResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    companion object {
        private const val CONFIG_DIR_PROPERTY = "lmversusu.configDir"
        private var originalConfigDir: String? = null

        @JvmStatic
        @BeforeAll
        fun setUpOnce() {
            originalConfigDir = System.getProperty(CONFIG_DIR_PROPERTY)

            val cl = Thread.currentThread().contextClassLoader
            val llmConfigsUrl = cl.getResource("LLM-Configs")
                ?: error("Missing test resource folder: src/test/resources/LLM-Configs")

            val configDir = Paths.get(llmConfigsUrl.toURI()).parent
                ?: error("Could not resolve parent directory for LLM-Configs")

            System.setProperty(CONFIG_DIR_PROPERTY, configDir.toString())
        }

        @JvmStatic
        @AfterAll
        fun tearDownOnce() {
            if (originalConfigDir != null) {
                System.setProperty(CONFIG_DIR_PROPERTY, originalConfigDir!!)
            } else {
                System.clearProperty(CONFIG_DIR_PROPERTY)
            }
        }
    }

    @Test
    fun testRoot() = testApplication {
        application {
            module(TestConfigFactory.createTestConfig())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    }
                )
            }
            defaultRequest {
                accept(ContentType.Application.Json)
            }
        }

        client.get("/api/v1/heartbeat").apply {
            assertEquals(HttpStatusCode.OK, status)
            body<HeartbeatResponse>().also {
                assertEquals("ok", it.status)
            }
        }

    }

    @Test
    fun `GET models endpoint is reachable`() = testApplication {
        application {
            module(TestConfigFactory.createTestConfig())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            defaultRequest {
                accept(ContentType.Application.Json)
            }
        }

        val resp = client.get("/api/v1/models")
        val body = resp.bodyAsText()

        println("STATUS: ${resp.status}")
        println("BODY:\n$body")

        assert(resp.status == HttpStatusCode.OK) {
            "Expected 200 OK, got ${resp.status}. Body:\n$body"
        }
    }

    @Test
    fun `GET models with invalid mode returns BadRequest`() = testApplication {
        application {
            module(TestConfigFactory.createTestConfig())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    }
                )
            }
            defaultRequest {
                accept(ContentType.Application.Json)
            }
        }

        client.get("/api/v1/models?mode=INVALID").apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }
}
