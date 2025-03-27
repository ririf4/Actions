@file:Suppress("propertyName", "DuplicatedCode", "ReplacePrintlnWithLogging")

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
	if (args.isEmpty()) error("Missing payload file path")
	val payloadJson = File(args[0]).readText()
	val payload = Gson().fromJson(payloadJson, Payload::class.java)

	when (payload.event_type) {
		"commit" -> sendCommitWebhook(System.getenv("DISCORD_COMMIT_WEBHOOK"), payload)
		"issues" -> sendIssueWebhook(System.getenv("DISCORD_ISSUE_WEBHOOK"), payload)
		"pull-request" -> sendPullRequestWebhook(System.getenv("DISCORD_PR_WEBHOOK"), payload)
		else -> println("Unknown event_type: ${payload.event_type}")
	}
}

fun sendCommitWebhook(webhookUrl: String?, payload: Payload) {
	if (webhookUrl.isNullOrEmpty()) return println("Webhook URL is empty. Skipping commit webhook.")

	val commitSha = payload.commit_sha ?: "Unknown"
	val commitMessage = payload.commit_message ?: "No commit message"
	val commitAuthor = payload.commit_author ?: "Unknown"
	val repo = payload.repo_name
	val branch = payload.branch_name

	val authorUrl = "https://github.com/$commitAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$commitAuthor"

	val commitTitle = commitMessage.lineSequence().firstOrNull() ?: "No commit message"

	val embed = Embed(
		title = "[${repo}:${branch}] 1 new commit",
		url = "https://github.com/${repo}/commit/${commitSha}",
		description = "[`${commitSha.take(7)}`](https://github.com/${repo}/commit/${commitSha}) $commitTitle",
		color = 0x7289DA,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub Commit"),
		author = Author(commitAuthor, authorUrl, authorAvatar)
	)

	val payloadObj = WebhookPayload(
		username = "GitHub",
		avatar_url = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, payloadObj)
}

fun sendIssueWebhook(webhookUrl: String?, payload: Payload) {
	if (webhookUrl.isNullOrEmpty()) {
		println("Webhook URL is empty. Skipping issue webhook.")
		return
	}

	val issueTitle = payload.issue_title ?: "Unknown Issue"
	val issueBody = payload.issue_body.orEmpty()
	val issueUrl = payload.issue_url ?: "Unknown URL"
	val issueNumber = payload.issue_number?.toString() ?: "Unknown"
	val issueAuthor = payload.issue_author ?: "Unknown Author"
	val authorUrl = "https://github.com/$issueAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$issueAuthor"
	val issueState = payload.issue_state ?: "unknown"
	val eventType = payload.event_type.replaceFirstChar { it.uppercaseChar() }

	val embedColor = when (issueState) {
		"open" -> 0x00FF00
		"edited", "reopened" -> 0x00CC99
		"closed" -> 0x0099FF
		else -> 0x808080
	}

	val truncatedBody = if (issueBody.length > 550) issueBody.take(550) + "..." else issueBody

	val embed = Embed(
		title = "[${payload.repo_name}:${payload.branch_name}] $eventType: #$issueNumber $issueTitle",
		url = issueUrl,
		description = truncatedBody,
		color = embedColor,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub Issues"),
		author = Author(issueAuthor, authorUrl, authorAvatar)
	)

	val payloadObj = WebhookPayload(
		username = "GitHub",
		avatar_url = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, payloadObj)
}

fun sendPullRequestWebhook(webhookUrl: String?, payload: Payload) {
	if (webhookUrl.isNullOrEmpty()) {
		println("Webhook URL is empty. Skipping PR webhook.")
		return
	}

	val prTitle = payload.pr_title ?: "Unknown PR"
	val prBody = payload.pr_body ?: "No description"
	val prUrl = payload.pr_url ?: "Unknown URL"
	val prNumber = payload.pr_number?.toString() ?: "Unknown"
	val prAuthor = payload.pr_author ?: "Unknown Author"
	val authorUrl = "https://github.com/$prAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$prAuthor"
	val eventType = payload.event_type.replaceFirstChar { it.uppercaseChar() }

	val isMerged = payload.pr_merged == true
	val prState = when {
		isMerged -> "merged"
		payload.pr_state == "closed" -> "closed"
		else -> "open"
	}

	val embedColor = when (prState) {
		"open" -> 0x007AFF
		"closed" -> 0xFF0000
		"merged" -> 0x800080
		else -> 0x808080
	}

	val truncatedBody = if (prBody.length > 550) prBody.take(550) + "..." else prBody

	val embed = Embed(
		title = "[${payload.repo_name}:${payload.branch_name}] $eventType: #$prNumber $prTitle",
		url = prUrl,
		description = truncatedBody,
		color = embedColor,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub PR"),
		author = Author(prAuthor, authorUrl, authorAvatar)
	)

	val payloadObj = WebhookPayload(
		username = "GitHub",
		avatar_url = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, payloadObj)
}

fun sendDiscordWebhook(webhookUrl: String, payload: WebhookPayload) {
	val gson = Gson()
	val jsonPayload = gson.toJson(payload)

	val url = URI(webhookUrl).toURL()
	val connection = url.openConnection() as HttpURLConnection
	connection.requestMethod = "POST"
	connection.doOutput = true
	connection.setRequestProperty("Content-Type", "application/json")

	connection.outputStream.use { it.write(jsonPayload.toByteArray(StandardCharsets.UTF_8)) }

	val responseCode = connection.responseCode
	if (responseCode !in 200..299) {
		error("Failed to send webhook: HTTP $responseCode")
	} else {
		println("Webhook sent successfully!")
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

data class Footer(val text: String, val icon_url: String? = null)
data class Author(val name: String, val url: String, val icon_url: String? = null)
