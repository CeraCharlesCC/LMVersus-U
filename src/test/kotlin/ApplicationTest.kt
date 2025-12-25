package io.github.ceracharlescc

import io.github.ceracharlescc.lmversusu.internal.module
import io.github.ceracharlescc.lmversusu.internal.TestConfigFactory
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.HeartbeatResponse
import io.github.ceracharlescc.lmversusu.internal.presentation.ktor.api.ModelsResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json

import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

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
        
        client.get("/api/v1/models").apply {
            println(bodyAsText())
            assert(status == HttpStatusCode.OK)
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
