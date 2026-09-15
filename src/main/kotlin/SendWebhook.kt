@file:Suppress(
	"propertyName",
	"DuplicatedCode",
	"ReplacePrintlnWithLogging"
)

import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant

data class Payload(
	// Required
	val repo_name: String,
	val branch_name: String,
	val event_type: String,

	// Event action:
	// opened, edited, reopened, synchronize, closed, etc.
	val event_action: String?,

	// event_type -> commit
	val commit_sha: String?,
	val commit_message: String?,
	val commit_author: String?,

	// event_type -> pull-request
	val pr_title: String?,
	val pr_body: String?,
	val pr_number: Int?,
	val pr_url: String?,
	val pr_author: String?,
	val pr_state: String?,
	val pr_merged: Boolean?,

	// event_type -> issues
	val issue_title: String?,
	val issue_body: String?,
	val issue_number: Int?,
	val issue_url: String?,
	val issue_author: String?,
	val issue_state: String?
)

fun main(args: Array<String>) {
	if (args.isEmpty()) {
		error("Missing payload file path")
	}

	val payloadFile = File(args[0])

	if (!payloadFile.isFile) {
		error("Payload file does not exist: ${payloadFile.absolutePath}")
	}

	val payloadJson = payloadFile.readText()
	val payload = Gson().fromJson(payloadJson, Payload::class.java)

	when (payload.event_type) {
		"commit" -> {
			sendCommitWebhook(
				System.getenv("DISCORD_COMMIT_WEBHOOK"),
				payload
			)
		}

		"issues" -> {
			sendIssueWebhook(
				System.getenv("DISCORD_ISSUE_WEBHOOK"),
				payload
			)
		}

		"pull-request" -> {
			sendPullRequestWebhook(
				System.getenv("DISCORD_PR_WEBHOOK"),
				payload
			)
		}

		else -> {
			println("Unknown event_type: ${payload.event_type}")
		}
	}
}

fun sendCommitWebhook(
	webhookUrl: String?,
	payload: Payload
) {
	if (webhookUrl.isNullOrBlank()) {
		println("Webhook URL is empty. Skipping commit webhook.")
		return
	}

	val commitSha = payload.commit_sha ?: "Unknown"
	val commitMessage = payload.commit_message ?: "No commit message"
	val commitAuthor = payload.commit_author ?: "Unknown"
	val repo = payload.repo_name
	val branch = payload.branch_name

	val authorUrl = "https://github.com/$commitAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$commitAuthor"

	val commitTitle = commitMessage
		.lineSequence()
		.firstOrNull()
		?.takeIf { it.isNotBlank() }
		?: "No commit message"

	val commitUrl = "https://github.com/$repo/commit/$commitSha"

	val embed = Embed(
		title = "[$repo:$branch] New commit",
		url = commitUrl,
		description = "[`${commitSha.take(7)}`]($commitUrl) $commitTitle",
		color = 0x7289DA,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub Commit"),
		author = Author(
			name = commitAuthor,
			url = authorUrl,
			icon_url = authorAvatar
		)
	)

	val webhookPayload = WebhookPayload(
		username = "GitHub",
		avatar_url = GITHUB_AVATAR_URL,
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, webhookPayload)
}

fun sendIssueWebhook(
	webhookUrl: String?,
	payload: Payload
) {
	if (webhookUrl.isNullOrBlank()) {
		println("Webhook URL is empty. Skipping issue webhook.")
		return
	}

	val issueTitle = payload.issue_title
		?.takeIf { it.isNotBlank() }
		?: "Unknown Issue"

	val issueBody = payload.issue_body.orEmpty()

	val issueUrl = payload.issue_url
		?.takeIf { it.isNotBlank() }
		?: "https://github.com/${payload.repo_name}/issues"

	val issueNumber = payload.issue_number
		?.toString()
		?: "Unknown"

	val issueAuthor = payload.issue_author
		?.takeIf { it.isNotBlank() }
		?: "Unknown Author"

	val action = payload.event_action
		?.takeIf { it.isNotBlank() }
		?: "unknown"

	val authorUrl = "https://github.com/$issueAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$issueAuthor"

	val eventLabel = when (action) {
		"opened" -> "Issue opened"
		"reopened" -> "Issue reopened"
		"edited" -> "Issue edited"
		"closed" -> "Issue closed"
		else -> "Issue ${formatAction(action)}"
	}

	val embedColor = when (action) {
		"opened" -> 0x3FB950
		"reopened" -> 0x3FB950
		"edited" -> 0xD29922
		"closed" -> 0x8957E5
		else -> 0x808080
	}

	val description = truncateBody(
		body = issueBody,
		fallback = "No description"
	)

	val embed = Embed(
		title = buildEventTitle(
			repo = payload.repo_name,
			branch = payload.branch_name,
			eventLabel = eventLabel,
			number = issueNumber,
			subject = issueTitle
		),
		url = issueUrl,
		description = description,
		color = embedColor,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub Issues"),
		author = Author(
			name = issueAuthor,
			url = authorUrl,
			icon_url = authorAvatar
		)
	)

	val webhookPayload = WebhookPayload(
		username = "GitHub",
		avatar_url = GITHUB_AVATAR_URL,
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, webhookPayload)
}

fun sendPullRequestWebhook(
	webhookUrl: String?,
	payload: Payload
) {
	if (webhookUrl.isNullOrBlank()) {
		println("Webhook URL is empty. Skipping PR webhook.")
		return
	}

	val prTitle = payload.pr_title
		?.takeIf { it.isNotBlank() }
		?: "Unknown PR"

	val prBody = payload.pr_body.orEmpty()

	val prUrl = payload.pr_url
		?.takeIf { it.isNotBlank() }
		?: "https://github.com/${payload.repo_name}/pulls"

	val prNumber = payload.pr_number
		?.toString()
		?: "Unknown"

	val prAuthor = payload.pr_author
		?.takeIf { it.isNotBlank() }
		?: "Unknown Author"

	val action = payload.event_action
		?.takeIf { it.isNotBlank() }
		?: "unknown"

	val isMerged = payload.pr_merged == true

	val authorUrl = "https://github.com/$prAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$prAuthor"

	val eventLabel = when {
		isMerged -> "PR merged"
		action == "opened" -> "PR opened"
		action == "reopened" -> "PR reopened"
		action == "synchronize" -> "New commits pushed"
		action == "closed" -> "PR closed"
		else -> "PR ${formatAction(action)}"
	}

	val embedColor = when {
		isMerged -> 0x8957E5
		action == "opened" -> 0x3FB950
		action == "reopened" -> 0x3FB950
		action == "synchronize" -> 0x58A6FF
		action == "closed" -> 0xF85149
		else -> 0x808080
	}

	val description = truncateBody(
		body = prBody,
		fallback = "No description"
	)

	val embed = Embed(
		title = buildEventTitle(
			repo = payload.repo_name,
			branch = payload.branch_name,
			eventLabel = eventLabel,
			number = prNumber,
			subject = prTitle
		),
		url = prUrl,
		description = description,
		color = embedColor,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub Pull Request"),
		author = Author(
			name = prAuthor,
			url = authorUrl,
			icon_url = authorAvatar
		)
	)

	val webhookPayload = WebhookPayload(
		username = "GitHub",
		avatar_url = GITHUB_AVATAR_URL,
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, webhookPayload)
}

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

fun buildEventTitle(
	repo: String,
	branch: String,
	eventLabel: String,
	number: String,
	subject: String
): String {
	return "[$repo:$branch] $eventLabel: #$number $subject"
}

fun truncateBody(
	body: String,
	fallback: String,
	maxLength: Int = 550
): String {
	val normalizedBody = body.trim()

	if (normalizedBody.isEmpty()) {
		return fallback
	}

	return if (normalizedBody.length > maxLength) {
		normalizedBody.take(maxLength) + "..."
	} else {
		normalizedBody
	}
}

fun formatAction(action: String): String {
	return action
		.replace('-', ' ')
		.replace('_', ' ')
		.replaceFirstChar { character ->
			character.uppercaseChar()
		}
}

// > ==== For Data Structures ==== < \\

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

private const val GITHUB_AVATAR_URL = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png"