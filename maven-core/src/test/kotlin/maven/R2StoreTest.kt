package maven

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.*

class R2StoreTest {
    private fun server(handler: (HttpExchange) -> Unit, test: (R2Store) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            try {
                handler(request)
            } finally {
                request.close()
            }
        }
        server.start()
        try {
            test(R2Store("a".repeat(32), "test-bucket", "test-key", "test-secret", "http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, body: String = "") {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.write(bytes)
    }

    @Test
    fun `list follows encoded continuation tokens and decodes XML keys`() {
        val queries = mutableListOf<String>()
        server({ request ->
            queries.add(request.requestURI.rawQuery)
            if (queries.size == 1) request.respond(
                200,
                "<ListBucketResult><Contents><Key>releases/a&amp;b.jar</Key></Contents><IsTruncated>true</IsTruncated><NextContinuationToken>a+/=</NextContinuationToken></ListBucketResult>"
            )
            else request.respond(
                200,
                "<ListBucketResult><Contents><Key>releases/c.jar</Key></Contents><IsTruncated>false</IsTruncated></ListBucketResult>"
            )
        }) { store ->
            assertEquals(listOf("releases/a&b.jar", "releases/c.jar"), store.list("releases/"))
            assertEquals("list-type=2&prefix=releases%2F", queries[0])
            assertEquals("continuation-token=a%2B%2F%3D&list-type=2&prefix=releases%2F", queries[1])
        }
    }

    @Test
    fun `put signs and sends conditional headers with exact content`() {
        var condition: String? = null
        var body: String? = null
        var authorization: String? = null
        var path: String? = null
        server({ request ->
            condition = request.requestHeaders.getFirst("If-Match")
            authorization = request.requestHeaders.getFirst("Authorization")
            path = request.requestURI.rawPath
            body = request.requestBody.readAllBytes().toString(Charsets.UTF_8)
            request.respond(200)
        }) { store ->
            store.put("releases/a+b.jar", "hello".toByteArray(), StoredObject(byteArrayOf(), "\"old\""))
            assertEquals("\"old\"", condition)
            assertEquals("hello", body)
            assertEquals("/test-bucket/releases/a%2Bb.jar", path)
            assertTrue(authorization!!.contains("if-match"))
        }
    }

    @Test
    fun `missing objects are null and conditional failures are conflicts`() {
        server({ request -> request.respond(if (request.requestMethod == "GET") 404 else 412) }) { store ->
            assertNull(store.get("missing"))
            assertFailsWith<Conflict> { store.put("missing", byteArrayOf(), null) }
        }
    }

    @Test
    fun `authorization failure is never interpreted as missing`() {
        server({ request -> request.respond(403) }) { store ->
            assertFailsWith<IllegalArgumentException> { store.get("private") }
        }
    }

    @Test
    fun `truncated listings without continuation tokens fail`() {
        server({ request -> request.respond(200, "<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>") }) { store ->
            assertFailsWith<IllegalStateException> { store.list("releases/") }
        }
    }
}
