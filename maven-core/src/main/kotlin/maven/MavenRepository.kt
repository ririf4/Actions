package maven

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

data class MavenPayload(
    val operation: String, val repository: String, val groupId: String, val artifactId: String,
    val version: String, val dryRun: Boolean = true, val sourceRepository: String? = null,
    val sourceRunId: String? = null, val sourceArtifact: String? = null, val confirmDelete: String? = null,
    val sourceBundleKey: String? = null, val sourceBundleSha256: String? = null
) {
    val snapshot get() = version.endsWith("-SNAPSHOT")
    val coordinate get() = "$groupId:$artifactId:$version"
    val artifactPath get() = "${groupId.replace('.', '/')}/$artifactId/"
    val versionPath get() = "$artifactPath$version/"
    val prefix get() = "$repository/$versionPath"

    fun validate(): MavenPayload {
        require(operation in listOf("publish", "update", "delete")) { "operation must be publish, update or delete" }
        require(repository in listOf("releases", "snapshots")) { "repository must be releases or snapshots" }
        require(groupId.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*"))) { "Invalid group_id" }
        val segment = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,199}")
        require(artifactId.matches(segment) && version.matches(segment)) { "Invalid artifact_id/version" }
        require(snapshot == (repository == "snapshots")) { "SNAPSHOT versions must use snapshots; releases must use releases" }
        require(operation != "update" || snapshot) { "Release updates are forbidden; publish a new version" }
        if (operation == "delete") require(confirmDelete == coordinate) { "confirm_delete must equal $coordinate" }
        else if (sourceBundleKey != null || sourceBundleSha256 != null) {
            require(sourceRepository == null && sourceRunId == null && sourceArtifact == null) { "Choose one publication source" }
            require(sourceBundleKey?.matches(Regex("staging/requests/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.zip")) == true) { "Invalid source_bundle_key" }
            require(sourceBundleSha256?.matches(Regex("[a-f0-9]{64}")) == true) { "Invalid source_bundle_sha256" }
        } else {
            require(sourceRepository?.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) == true) { "Invalid source_repository" }
            require(sourceRunId?.matches(Regex("[1-9][0-9]{0,19}")) == true) { "Invalid source_run_id" }
            require(sourceArtifact?.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) == true) { "Invalid source_artifact" }
        }
        return this
    }

    companion object {
        fun read(path: Path): MavenPayload {
            require(Files.size(path) <= 65536) { "Payload too large" }
            val obj = JsonParser.parseString(Files.readString(path)).asJsonObject
            val known = setOf(
                "operation", "repository", "group_id", "artifact_id", "version", "dry_run",
                "source_repository", "source_run_id", "source_artifact", "confirm_delete", "source_bundle_key", "source_bundle_sha256"
            )
            require(obj.keySet().all { it in known }) { "Unknown payload field" }
            fun string(name: String, optional: Boolean = false): String? {
                val value = obj.get(name)
                if (value == null && optional) return null
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
                return value.asString
            }

            val dry = obj.get("dry_run")
            require(dry == null || (dry.isJsonPrimitive && dry.asJsonPrimitive.isBoolean)) { "dry_run must be boolean" }
            return MavenPayload(
                string("operation")!!, string("repository")!!, string("group_id")!!,
                string("artifact_id")!!, string("version")!!, dry?.asBoolean ?: true,
                string("source_repository", true), string("source_run_id", true), string("source_artifact", true),
                string("confirm_delete", true), string("source_bundle_key", true), string("source_bundle_sha256", true)
            ).validate()
        }
    }

    fun json(): String = com.google.gson.Gson().toJson(
        linkedMapOf<String, Any?>(
            "operation" to operation, "repository" to repository, "group_id" to groupId, "artifact_id" to artifactId,
            "version" to version, "dry_run" to dryRun, "source_repository" to sourceRepository, "source_run_id" to sourceRunId,
            "source_artifact" to sourceArtifact, "confirm_delete" to confirmDelete,
            "source_bundle_key" to sourceBundleKey, "source_bundle_sha256" to sourceBundleSha256
        ).filterValues { it != null })
}

class MavenManager(private val store: ObjectStore, private val now: () -> Instant = Instant::now) {
    private val checksumAlgorithms = linkedMapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
    private fun sameContent(key: String, old: ByteArray, fresh: ByteArray): Boolean =
        if (key.substringAfterLast('.') in checksumAlgorithms) old.toString(Charsets.UTF_8).trim().equals(fresh.toString(Charsets.UTF_8), ignoreCase = true)
        else old.contentEquals(fresh)

    private fun files(payload: MavenPayload, source: Path): Map<String, ByteArray> {
        val root = source.toAbsolutePath().normalize()
        val directory = root.resolve(payload.versionPath).normalize()
        require(directory.startsWith(root) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Artifact must contain ${payload.versionPath} at its root"
        }
        var ancestor: Path? = directory
        while (ancestor != null && ancestor.startsWith(root)) {
            require(!Files.isSymbolicLink(ancestor)) { "Symlink directories are forbidden" }
            ancestor = ancestor.parent
        }
        var total = 0L
        val result = linkedMapOf<String, ByteArray>()
        Files.walk(directory).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Symlinks are forbidden" }
                if (Files.isDirectory(path)) require(path == directory) { "Nested directories inside a version are forbidden" }
                else {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Unsupported source file" }
                    val name = path.fileName.toString()
                    require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) { "Invalid artifact filename" }
                    if (name == "maven-metadata-local.xml" || checksumAlgorithms.keys.any { name.endsWith(".$it") }) return@forEach
                    require(name.startsWith("${payload.artifactId}-") || (payload.snapshot && name == "maven-metadata.xml")) {
                        "Unexpected file in version: $name"
                    }
                    if (name != "maven-metadata.xml") {
                        val fileVersion = if (payload.snapshot) Regex.escape(payload.version.removeSuffix("-SNAPSHOT")) + "-\\d{8}\\.\\d{6}-[1-9]\\d*"
                        else Regex.escape(payload.version)
                        require(name.matches(Regex(Regex.escape(payload.artifactId) + "-" + fileVersion + "(?:-[A-Za-z0-9._+-]+)?\\.[A-Za-z0-9.]+"))) {
                            "Artifact filename does not match the requested coordinate: $name"
                        }
                    }
                    val size = Files.size(path)
                    total += size
                    require(size <= 128L * 1024 * 1024 && total <= 512L * 1024 * 1024) { "Artifact exceeds memory limits (128 MiB/file, 512 MiB/publication)" }
                    result[payload.prefix + name] = Files.readAllBytes(path)
                }
            }
        }
        require(result.keys.any { it.endsWith(".pom") }) { "Publication must include a POM" }
        result.filterKeys { it.endsWith(".pom") }.forEach { (_, bytes) ->
            val project = parseXml(bytes).documentElement
            require(project.tagName == "project") { "Invalid POM root" }
            val parent = project.child("parent")
            require(
                (project.child("groupId") ?: parent?.child("groupId"))?.textContent == payload.groupId &&
                        project.child("artifactId")?.textContent == payload.artifactId &&
                        (project.child("version") ?: parent?.child("version"))?.textContent == payload.version
            ) {
                "POM coordinate mismatch (use a resolved publication POM)"
            }
        }
        if (payload.snapshot) {
            val xml = result[payload.prefix + "maven-metadata.xml"] ?: error("SNAPSHOT publication requires version-level maven-metadata.xml")
            val doc = parseXml(xml)
            val metadata = doc.documentElement
            require(
                metadata.tagName == "metadata" && metadata.child("groupId")?.textContent == payload.groupId &&
                        metadata.child("artifactId")?.textContent == payload.artifactId && metadata.child("version")?.textContent == payload.version
            ) {
                "SNAPSHOT metadata coordinate mismatch"
            }
            val entries = doc.getElementsByTagName("snapshotVersion")
            require(entries.length > 0) { "SNAPSHOT metadata must describe timestamped artifacts" }
            for (i in 0 until entries.length) {
                val entry = entries.item(i) as org.w3c.dom.Element
                val value = entry.child("value")?.textContent ?: error("Missing snapshot value")
                require(value.matches(Regex(Regex.escape(payload.version.removeSuffix("-SNAPSHOT")) + "-\\d{8}\\.\\d{6}-[1-9]\\d*"))) {
                    "SNAPSHOT metadata must use timestamped versions"
                }
                val extension = entry.child("extension")?.textContent ?: error("Missing snapshot extension")
                val classifier = entry.child("classifier")?.textContent?.takeIf { it.isNotEmpty() }?.let { "-$it" } ?: ""
                require(payload.prefix + "${payload.artifactId}-$value$classifier.$extension" in result) {
                    "SNAPSHOT metadata references a missing artifact"
                }
            }
        }
        val complete = linkedMapOf<String, ByteArray>()
        result.forEach { (key, bytes) ->
            complete[key] = bytes
            checksumAlgorithms.forEach { (suffix, algorithm) -> complete["$key.$suffix"] = digest(bytes, algorithm).toByteArray() }
        }
        return complete
    }

    fun execute(payload: MavenPayload, source: Path?) {
        payload.validate()
        val metadataKey = "${payload.repository}/${payload.artifactPath}maven-metadata.xml"
        val existingMetadata = store.get(metadataKey)
        // Parse before any writes, so corrupt parent metadata cannot leave a partial publication.
        val updatedMetadata = metadata(existingMetadata?.bytes, payload, now())
        val existingKeys = store.list(payload.prefix)
        require(existingKeys.all { it.startsWith(payload.prefix) }) { "Store returned keys outside the selected version" }
        val uploads = if (payload.operation == "delete") emptyMap() else files(payload, requireNotNull(source))
        val existing = uploads.keys.associateWith { store.get(it) }
        if (payload.operation != "delete" && !payload.snapshot) {
            require(existingKeys.all { it in uploads }) { "Published release has files absent from the incoming artifact" }
            require(existing.all { (key, old) -> old == null || sameContent(key, old.bytes, uploads.getValue(key)) }) {
                "Release content differs; publish a new version"
            }
            val listedVersions = existingMetadata?.let { parseXml(it.bytes).getElementsByTagName("version") }
            val alreadyPublished = listedVersions != null && (0 until listedVersions.length).any { listedVersions.item(it).textContent == payload.version }
            require(!alreadyPublished || uploads.keys.all { it in existingKeys }) { "Cannot add files to an already published release" }
        }
        if (payload.operation != "delete" && payload.snapshot) {
            val versionMetadataKey = payload.prefix + "maven-metadata.xml"
            fun updated(bytes: ByteArray): String {
                val value = parseXml(bytes).documentElement.child("versioning")?.child("lastUpdated")?.textContent
                require(value?.matches(Regex("[0-9]{14}")) == true) { "SNAPSHOT metadata requires a valid lastUpdated timestamp" }
                return value!!
            }

            fun snapshotBuild(bytes: ByteArray): Pair<String, java.math.BigInteger> {
                val snapshot = parseXml(bytes).documentElement.child("versioning")?.child("snapshot")
                    ?: error("Missing SNAPSHOT build metadata")
                val timestamp = snapshot.child("timestamp")?.textContent ?: error("Missing SNAPSHOT timestamp")
                val build = snapshot.child("buildNumber")?.textContent ?: error("Missing SNAPSHOT buildNumber")
                require(timestamp.matches(Regex("[0-9]{8}\\.[0-9]{6}")) && build.matches(Regex("[1-9][0-9]*"))) {
                    "Invalid SNAPSHOT build metadata"
                }
                return timestamp to build.toBigInteger()
            }

            val freshTimestamp = updated(uploads.getValue(versionMetadataKey))
            val fresh = snapshotBuild(uploads.getValue(versionMetadataKey))
            existing[versionMetadataKey]?.let {
                require(freshTimestamp >= updated(it.bytes)) { "Refusing to move SNAPSHOT metadata back to an older publication" }
                val old = snapshotBuild(it.bytes)
                require(fresh.first > old.first || (fresh.first == old.first && fresh.second >= old.second)) {
                    "Refusing to move SNAPSHOT metadata back to an older build"
                }
            }
            require(existing.all { (key, old) ->
                key.substringAfterLast('/').startsWith("maven-metadata.xml") ||
                        old == null || sameContent(key, old.bytes, uploads.getValue(key))
            }) {
                "SNAPSHOT artifacts are immutable; generate a new timestamp/build number"
            }
        }
        println("${if (payload.dryRun) "DRY RUN" else "APPLY"}: ${payload.operation} ${payload.repository} ${payload.coordinate}")
        println("${uploads.size} files to upload/verify; ${if (payload.operation == "delete") existingKeys.size else 0} files to delete; merge $metadataKey")
        if (payload.dryRun) return
        if (payload.operation != "delete") {
            // Version metadata is the commit point for timestamped SNAPSHOT readers.
            uploads.entries.sortedBy { if (it.key.substringAfterLast('/').startsWith("maven-metadata.xml")) 1 else 0 }
                .forEach { (key, bytes) ->
                    if (existing[key]?.let { sameContent(key, it.bytes, bytes) } != true) store.put(key, bytes, existing[key])
                }
        }
        store.put(metadataKey, updatedMetadata, existingMetadata)
        checksumAlgorithms.forEach { (suffix, algorithm) ->
            val key = "$metadataKey.$suffix"
            store.put(key, digest(updatedMetadata, algorithm).toByteArray(), store.get(key))
        }
        // Remove discovery metadata first. A failed deletion can safely be resumed with the same payload.
        if (payload.operation == "delete") existingKeys.forEach { store.delete(it) }
        println("Completed ${payload.operation} ${payload.coordinate}")
    }
}

fun main(args: Array<String>) {
    require(args.size in 1..2) { "Usage: manageMaven --args='payload.json [artifact-directory|--validate]'" }
    val payload = MavenPayload.read(Path.of(args[0]))
    if (args.getOrNull(1) == "--validate") {
        val output = listOf(
            "operation" to payload.operation,
            "source_type" to if (payload.sourceBundleKey == null) "artifact" else "r2",
            "source_repository" to payload.sourceRepository,
            "source_run_id" to payload.sourceRunId,
            "source_artifact" to payload.sourceArtifact
        )
            .filter { it.second != null }.joinToString("\n", postfix = "\n") { "${it.first}=${it.second}" }
        System.getenv("GITHUB_OUTPUT")?.let { Files.writeString(Path.of(it), output, StandardOpenOption.APPEND) }
        println("Validated ${payload.operation} ${payload.coordinate}; dry_run=${payload.dryRun}")
        return
    }
    fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Missing $name")
    val store = R2Store(env("R2_ACCOUNT_ID"), System.getenv("R2_BUCKET")?.takeIf { it.isNotBlank() } ?: "swiftstorm-maven",
        env("R2_ACCESS_KEY_ID"), env("R2_SECRET_ACCESS_KEY"))
    if (payload.sourceBundleKey == null || payload.operation == "delete") {
        MavenManager(store).execute(payload, args.getOrNull(1)?.let { Path.of(it) })
    } else {
        val bundle = store.get(payload.sourceBundleKey) ?: error("Staged publication not found; upload again with the Gradle plugin")
        val directory = Files.createTempDirectory("swiftstorm-maven-")
        try {
            PublicationBundle.extract(bundle.bytes, payload, directory)
            MavenManager(store).execute(payload, directory)
            store.delete(payload.sourceBundleKey)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
