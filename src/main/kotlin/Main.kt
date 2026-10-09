@file:Suppress("propertyName", "ReplacePrintlnWithLogging")

import com.google.gson.Gson
import java.io.File

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
	val issue_state: String?,

	// event_type -> release
	val release_name: String?,
	val release_tag: String?,
	val release_body: String?,
	val release_url: String?,
	val release_author: String?,
	val release_prerelease: Boolean?,
	val release_published_at: String?
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

		"release" -> {
			sendReleaseWebhook(
				System.getenv("DISCORD_RELEASE_WEBHOOK"),
				payload
			)
		}

		else -> {
			println("Unknown event_type: ${payload.event_type}")
		}
	}
}
