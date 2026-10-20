package com.otterworks.notification.routes

import com.otterworks.notification.service.NotificationService
import com.otterworks.notification.websocket.WebSocketManager
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the contract the web app (frontend/client-app/src/lib/api.ts) relies on for
 * requests proxied by api-gateway, which identifies the caller via X-User-ID.
 */
class RoutesTest {

    private val userId = "11111111-2222-3333-4444-555555555555"
    private val notificationService = mockk<NotificationService>(relaxed = true)

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            install(WebSockets)
            install(Koin) {
                modules(
                    module {
                        single { notificationService }
                        single { mockk<WebSocketManager>(relaxed = true) }
                    }
                )
            }
            configureRouting(PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
        }
        block()
    }

    @Test
    fun `unread-count returns the count field the web app reads`() = withApp {
        coEvery { notificationService.getUnreadCount(userId) } returns 3

        val response = client.get("/api/v1/notifications/unread-count") {
            header("X-User-ID", userId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(3, body["count"]?.jsonPrimitive?.int)
    }

    @Test
    fun `list honours the pageSize query parameter the web app sends`() = withApp {
        coEvery { notificationService.getNotifications(userId, 2, 5) } returns Pair(emptyList(), 7)

        val response = client.get("/api/v1/notifications?page=2&pageSize=5") {
            header("X-User-ID", userId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(5, body["pageSize"]?.jsonPrimitive?.int)
        coVerify { notificationService.getNotifications(userId, 2, 5) }
    }

    @Test
    fun `list still accepts page_size`() = withApp {
        coEvery { notificationService.getNotifications(userId, 1, 10) } returns Pair(emptyList(), 0)

        val response = client.get("/api/v1/notifications?page=1&page_size=10") {
            header("X-User-ID", userId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { notificationService.getNotifications(userId, 1, 10) }
    }

    @Test
    fun `requests without a user id are rejected with 400`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/notifications/unread-count").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/notifications?page=1&pageSize=20").status)
    }
}
