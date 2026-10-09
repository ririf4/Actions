@file:Suppress("propertyName", "ReplacePrintlnWithLogging")

import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

fun sendDiscordWebhook(
    webhookUrl: String,
    payload: WebhookPayload
) {
    val jsonPayload = Gson().toJson(payload)

    val connection = URI(webhookUrl)
        .toURL()
        .openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000

        connection.setRequestProperty(
            "Content-Type",
            "application/json; charset=UTF-8"
        )

        connection.outputStream.use { output ->
            output.write(
                jsonPayload.toByteArray(StandardCharsets.UTF_8)
            )
        }

        val responseCode = connection.responseCode

        println("HTTP $responseCode")
        println("Retry-After: ${connection.getHeaderField("Retry-After")}")
        println("X-RateLimit-Limit: ${connection.getHeaderField("X-RateLimit-Limit")}")
        println("X-RateLimit-Remaining: ${connection.getHeaderField("X-RateLimit-Remaining")}")
        println("X-RateLimit-Reset-After: ${connection.getHeaderField("X-RateLimit-Reset-After")}")
        println("X-RateLimit-Bucket: ${connection.getHeaderField("X-RateLimit-Bucket")}")
        println("X-RateLimit-Scope: ${connection.getHeaderField("X-RateLimit-Scope")}")
        println("X-RateLimit-Global: ${connection.getHeaderField("X-RateLimit-Global")}")

        if (responseCode !in 200..299) {
            val responseBody = connection.errorStream
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            error(
                buildString {
                    append("Failed to send webhook: HTTP ")
                    append(responseCode)

                    if (responseBody.isNotBlank()) {
                        appendLine()
                        append(responseBody)
                    }
                }
            )
        }

        println("Webhook sent successfully!")
    } finally {
        connection.disconnect()
    }
}

data class WebhookPayload(
    val username: String,
    val avatar_url: String,
    val embeds: List<Embed>
)

data class Embed(
    val title: String,
    val url: String? = null,
    val description: String,
    val color: Int,
    val timestamp: String,
    val footer: Footer,
    val author: Author
)

data class Footer(
    val text: String,
    val icon_url: String? = null
)

data class Author(
    val name: String,
    val url: String,
    val icon_url: String? = null
)
