package maven

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class StoredObject(val bytes: ByteArray, val etag: String)
class Conflict : IllegalStateException("Object changed concurrently; retry the workflow")

interface ObjectStore {
    fun get(key: String): StoredObject?
    fun list(prefix: String): List<String>
    fun put(key: String, bytes: ByteArray, previous: StoredObject?)
    fun delete(key: String)
}

fun digest(bytes: ByteArray, algorithm: String = "SHA-256"): String =
    MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

internal fun encode(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach {
        val n = it.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n in listOf(45, 46, 95, 126)) append(n.toChar())
        else append("%%%02X".format(n))
    }
}

internal fun signatureHeaders(
    method: String, uri: URI, bytes: ByteArray, extra: Map<String, String>,
    accessKey: String, secret: String, now: Instant
): Map<String, String> {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
    val date = stamp.take(8)
    val headers = (extra + mapOf(
        "host" to uri.rawAuthority, "x-amz-date" to stamp,
        "x-amz-content-sha256" to digest(bytes)
    )).toSortedMap()
    val names = headers.keys.joinToString(";")
    val canonical = listOf(
        method, uri.rawPath, uri.rawQuery ?: "",
        headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }, names, digest(bytes)
    ).joinToString("\n")
    val scope = "$date/auto/s3/aws4_request"
    val toSign = "AWS4-HMAC-SHA256\n$stamp\n$scope\n${digest(canonical.toByteArray())}"
    fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray())
    }

    val signingKey = hmac(hmac(hmac(hmac(("AWS4$secret").toByteArray(), date), "auto"), "s3"), "aws4_request")
    val signature = hmac(signingKey, toSign).joinToString("") { "%02x".format(it) }
    return (headers - "host") + ("authorization" to
            "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$names, Signature=$signature")
}

class R2Store(
    account: String, private val bucket: String, private val accessKey: String, private val secret: String,
    endpointOverride: String? = null
) : ObjectStore {
    private val endpoint: String
    private val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build()

    init {
        require(account.matches(Regex("[a-f0-9]{32}"))) { "Invalid R2_ACCOUNT_ID" }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))) { "Invalid R2_BUCKET" }
        endpoint = endpointOverride ?: "https://$account.r2.cloudflarestorage.com"
    }

    private fun request(
        method: String, key: String, bytes: ByteArray = byteArrayOf(),
        query: Map<String, String> = emptyMap(), extra: Map<String, String> = emptyMap()
    ): HttpResponse<ByteArray> {
        val path = "/${encode(bucket)}/" + key.split('/').joinToString("/") { encode(it) }
        val qs = query.entries.map { encode(it.key) to encode(it.value) }.sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }
        val uri = URI.create(endpoint + path + if (qs.isEmpty()) "" else "?$qs")
        val headers = signatureHeaders(method, uri, bytes, extra, accessKey, secret, Instant.now())
        val builder = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofMinutes(5))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = client.send(
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )
        if (response.statusCode() == 409 || response.statusCode() == 412) throw Conflict()
        require(response.statusCode() in 200..299 || (method == "GET" && response.statusCode() == 404)) {
            "R2 $method failed (HTTP ${response.statusCode()}) for $key"
        }
        return response
    }

    override fun get(key: String): StoredObject? = request("GET", key).let {
        if (it.statusCode() == 404) null else StoredObject(it.body(), it.headers().firstValue("etag").orElseThrow())
    }

    override fun list(prefix: String): List<String> = buildList {
        var token: String? = null
        val seen = mutableSetOf<String>()
        do {
            val query = mutableMapOf("list-type" to "2", "prefix" to prefix)
            token?.let { query["continuation-token"] = it }
            val doc = parseXml(request("GET", "", query = query).body())
            val keys = doc.getElementsByTagName("Key")
            for (i in 0 until keys.length) {
                val key = keys.item(i).textContent
                require(key.startsWith(prefix)) { "Unexpected key outside requested prefix" }
                add(key)
            }
            token = if (doc.getElementsByTagName("IsTruncated").item(0)?.textContent == "true") {
                doc.getElementsByTagName("NextContinuationToken").item(0)?.textContent
                    ?.also { require(it.isNotBlank() && seen.add(it)) { "Invalid R2 pagination token" } }
                    ?: error("Missing R2 pagination token")
            } else null
        } while (token != null)
    }

    override fun put(key: String, bytes: ByteArray, previous: StoredObject?) {
        val condition = if (previous == null) "if-none-match" to "*" else "if-match" to previous.etag
        request(
            "PUT", key, bytes, extra = mapOf(
                condition,
                "content-type" to if (key.endsWith(".xml") || key.endsWith(".pom")) "application/xml" else "application/octet-stream",
                "cache-control" to "no-cache"
            )
        )
    }

    override fun delete(key: String) {
        request("DELETE", key)
    }
}
