@file:Suppress("DuplicatedCode", "ReplacePrintlnWithLogging")

import java.time.Instant

fun sendReleaseWebhook(
	webhookUrl: String?,
	payload: Payload
) {
	if (webhookUrl.isNullOrBlank()) {
		println("Webhook URL is empty. Skipping release webhook.")
		return
	}

	val releaseTag = payload.release_tag
		?.takeIf { it.isNotBlank() }
		?: "Unknown version"

	val releaseName = payload.release_name
		?.takeIf { it.isNotBlank() }
		?: releaseTag

	val releaseUrl = payload.release_url
		?.takeIf { it.isNotBlank() }
		?: "https://github.com/${payload.repo_name}/releases/tag/$releaseTag"

	val releaseAuthor = payload.release_author
		?.takeIf { it.isNotBlank() }
		?: "Unknown Author"

	val releaseType = if (payload.release_prerelease == true) {
		"Pre-release"
	} else {
		"Release"
	}

	val description = buildString {
		append("**Tag:** [`")
		append(releaseTag)
		append("`](")
		append(releaseUrl)
		appendLine(")")

		val releaseBody = payload.release_body.orEmpty().trim()
		if (releaseBody.isNotEmpty()) {
			appendLine()
			append(
				truncateBody(
					body = releaseBody,
					fallback = "No release notes",
					maxLength = 3_700
				)
			)
		}
	}

	val authorUrl = "https://github.com/$releaseAuthor"
	val authorAvatar = "https://avatars.githubusercontent.com/$releaseAuthor"

	val embed = Embed(
		title = "$releaseName ${if (payload.release_prerelease == true) "pre-released" else "released"}",
		url = releaseUrl,
		description = description,
		color = if (payload.release_prerelease == true) 0xD29922 else 0x3FB950,
		timestamp = payload.release_published_at
			?.takeIf { it.isNotBlank() }
			?: Instant.now().toString(),
		footer = Footer("GitHub $releaseType"),
		author = Author(
			name = releaseAuthor,
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

private const val GITHUB_AVATAR_URL = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png"
