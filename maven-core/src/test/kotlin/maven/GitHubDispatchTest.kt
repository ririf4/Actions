package maven

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubDispatchTest {
    @Test
    fun `dispatch receives an exact run id and waits for that run`() {
        val paths = mutableListOf<String>()
        var encodedPayload: String? = null
        var ref: String? = null
        var apiVersion: String? = null
        var polls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.path)
            apiVersion = exchange.requestHeaders.getFirst("X-GitHub-Api-Version")
            val response = when {
                exchange.requestMethod == "POST" -> {
                    val body = JsonParser.parseString(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).asJsonObject
                    encodedPayload = body.getAsJsonObject("inputs").get("payload").asString
                    ref = body.get("ref").asString
                    """{"workflow_run_id":123,"html_url":"https://untrusted.invalid/","run_url":"https://untrusted.invalid/"}"""
                }

                exchange.requestURI.path.endsWith("/runs/123") -> if (++polls == 1) """{"status":"queued"}""" else """{"status":"completed","conclusion":"success"}"""
                else -> """{"default_branch":"master"}"""
            }.toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }; exchange.close()
        }
        server.start()
        try {
            val github = GitHubDispatch("test-token", "ririf4/SendToDiscord", "maven-repository.yml", "http://127.0.0.1:${server.address.port}")
            val payload = MavenPayload("delete", "releases", "dev.swiftstorm", "demo", "1.0", confirmDelete = "dev.swiftstorm:demo:1.0")
            val run = github.dispatch(payload)
            assertEquals("123", run)
            github.waitForCompletion(run, Duration.ofSeconds(5), Duration.ofMillis(1))
            assertEquals("master", ref)
            assertEquals("2026-03-10", apiVersion)
            assertEquals(payload.json(), Base64.getDecoder().decode(encodedPayload).toString(Charsets.UTF_8))
            assertEquals(
                listOf(
                    "/repos/ririf4/SendToDiscord", "/repos/ririf4/SendToDiscord/actions/workflows/maven-repository.yml/dispatches",
                    "/repos/ririf4/SendToDiscord/actions/runs/123", "/repos/ririf4/SendToDiscord/actions/runs/123"
                ), paths
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `remote workflow failure is propagated`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = """{"status":"completed","conclusion":"failure"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        server.start()
        try {
            assertFailsWith<IllegalArgumentException> {
                GitHubDispatch("token", "owner/repo", "maven.yml", "http://127.0.0.1:${server.address.port}")
                    .waitForCompletion("1", Duration.ofSeconds(1))
            }
        } finally {
            server.stop(0)
        }
    }
}
