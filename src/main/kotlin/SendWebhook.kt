import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant

fun main(args: Array<String>) {
	val validEvents = listOf("commit", "issues", "pull-request")
	if (args.isEmpty() || args[0] !in validEvents) {
		println("Invalid or missing argument. Use one of: ${validEvents.joinToString(", ")}")
		return
	}

	val commitWebhook = System.getenv("DISCORD_COMMIT_WEBHOOK")
	val issueWebhook = System.getenv("DISCORD_ISSUE_WEBHOOK")
	val prWebhook = System.getenv("DISCORD_PR_WEBHOOK")

	val repo = System.getenv("REPO") ?: "Unknown"
	val branch = System.getenv("BRANCH") ?: "unknown-branch"

	when (args[0]) {
		"commit" -> sendCommitWebhook(commitWebhook, repo, branch)
		"issues" -> sendIssueWebhook(issueWebhook, repo, branch)
		"pull-request" -> sendPullRequestWebhook(prWebhook, repo, branch)
		else -> println("Unknown argument: ${args[0]}. Use commit, issues, or pull-request")
	}
}

fun sendCommitWebhook(webhookUrl: String, repo: String, branch: String) {
	if (webhookUrl.isEmpty()) return println("Webhook URL is empty. Skipping commit webhook.")
	val commitSha = System.getenv("SHA") ?: "Unknown"
	val commitMessage = System.getenv("MESSAGE") ?: "No commit message"
	val commitAuthor = System.getenv("ACTOR") ?: "Unknown"
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

	val payload = WebhookPayload(
		username = "GitHub",
		avatar_url = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, payload)
}

fun sendIssueWebhook(webhookUrl: String?, repo: String, branch: String) {
	if (webhookUrl.isNullOrEmpty()) {
		println("Webhook URL is empty. Skipping issue webhook.")
		return
	}

	val issueTitle = System.getenv("ISSUE_TITLE") ?: "Unknown Issue"
	val issueBody = System.getenv("ISSUE_BODY").orEmpty()
	val issueUrl = System.getenv("ISSUE_URL") ?: "Unknown URL"
	val issueNumber = System.getenv("ISSUE_NUMBER") ?: "Unknown"
	val issueAuthor = System.getenv("ISSUE_AUTHOR") ?: "Unknown Author"
	val authorUrl = "https://github.com/$issueAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$issueAuthor"
	val issueState = System.getenv("ISSUE_STATE") ?: "Unknown State"
	val eventType = System.getenv("EVENT")?.replaceFirstChar { it.uppercaseChar() } ?: "Updated"

	val embedColor = when (issueState) {
		"open" -> 0x00FF00
		"edited", "reopened" -> 0x00CC99
		"closed" -> 0x0099FF
		else -> 0x808080
	}

	val truncatedBody = if (issueBody.length > 550) issueBody.take(550) + "..." else issueBody

	val embed = Embed(
		title = "[${repo}:${branch}] $eventType: #$issueNumber $issueTitle",
		url = issueUrl,
		description = truncatedBody,
		color = embedColor,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub Issues"),
		author = Author(issueAuthor, authorUrl, authorAvatar)
	)

	val payload = WebhookPayload(
		username = "GitHub",
		avatar_url = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, payload)
}

fun sendPullRequestWebhook(webhookUrl: String, repo: String, branch: String) {
	if (webhookUrl.isEmpty()) return println("Webhook URL is empty. Skipping PR webhook.")
	val prTitle = System.getenv("PR_TITLE") ?: "Unknown PR"
	val prBody = System.getenv("PR_BODY") ?: "No description"
	val prUrl = System.getenv("PR_URL") ?: "Unknown URL"
	val prNumber = System.getenv("PR_NUMBER") ?: "Unknown"
	val prAuthor = System.getenv("PR_AUTHOR") ?: "Unknown Author"
	val authorUrl = "https://github.com/$prAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$prAuthor"
	val eventType = System.getenv("EVENT")?.replaceFirstChar { it.uppercaseChar() } ?: "Updated"

	val isMerged = System.getenv("PR_MERGED")?.toBoolean() == true
	val prState = when {
		isMerged -> "merged"
		System.getenv("PR_STATE") == "closed" -> "closed"
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
		title = "[${repo}:${branch}] $eventType: #$prNumber $prTitle",
		url = prUrl,
		description = truncatedBody,
		color = embedColor,
		timestamp = Instant.now().toString(),
		footer = Footer("GitHub PR"),
		author = Author(prAuthor, authorUrl, authorAvatar)
	)

	val payload = WebhookPayload(
		username = "GitHub",
		avatar_url = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
		embeds = listOf(embed)
	)

	sendDiscordWebhook(webhookUrl, payload)
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
