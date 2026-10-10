package maven

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*

class GitHubDispatch(
    private val token: String, repository: String, private val workflow: String,
    private val apiBase: String = "https://api.github.com"
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private val repositoryPath: String

    init {
        require(token.isNotBlank()) { "GitHub Actions dispatch token is required" }
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid Actions repository" }
        require(workflow.matches(Regex("[A-Za-z0-9_.-]+\\.ya?ml"))) { "Invalid Actions workflow filename" }
        repositoryPath = "/repos/$repository"
    }

    private fun request(method: String, path: String, body: String? = null): JsonObject {
        val builder = HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
        if (body != null) builder.header("Content-Type", "application/json")
        val response = client.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() == 200) {
            "GitHub $method failed (HTTP ${response.statusCode()}); staged data is retained. Check Actions permissions/API version."
        }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    fun dispatch(payload: MavenPayload, ref: String? = null): String {
        payload.validate()
        val branch = ref?.takeIf { it.isNotBlank() } ?: request("GET", repositoryPath).get("default_branch").asString
        val encoded = Base64.getEncoder().encodeToString(payload.json().toByteArray(Charsets.UTF_8))
        val result = request(
            "POST", "$repositoryPath/actions/workflows/$workflow/dispatches",
            Gson().toJson(mapOf("ref" to branch, "inputs" to mapOf("payload" to encoded)))
        )
        return result.get("workflow_run_id")?.asString?.also {
            require(it.matches(Regex("[1-9][0-9]{0,18}"))) { "Invalid workflow run ID" }
        } ?: error("Dispatch accepted without a run ID; inspect GitHub Actions before retrying")
    }

    fun waitForCompletion(runId: String, timeout: Duration, pollInterval: Duration = Duration.ofSeconds(10)) {
        require(runId.matches(Regex("[1-9][0-9]{0,18}")) && !timeout.isNegative && !timeout.isZero) { "Invalid workflow wait parameters" }
        require(!pollInterval.isNegative && !pollInterval.isZero && pollInterval <= Duration.ofSeconds(60)) { "Invalid poll interval" }
        val started = System.nanoTime()
        while (Duration.ofNanos(System.nanoTime() - started) < timeout) {
            val run = request("GET", "$repositoryPath/actions/runs/$runId")
            if (run.get("status")?.asString == "completed") {
                require(run.get("conclusion")?.asString == "success") { "Maven Actions run $runId finished with ${run.get("conclusion")}; inspect its logs before retrying" }
                return
            }
            Thread.sleep(pollInterval.toMillis().coerceAtLeast(1))
        }
        error("Timed out waiting for Maven Actions run $runId; it may still finish. Do not assume publishing failed.")
    }
}
