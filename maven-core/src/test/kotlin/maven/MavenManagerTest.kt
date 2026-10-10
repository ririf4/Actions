package maven

import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class MemoryStore : ObjectStore {
    val objects = linkedMapOf<String, StoredObject>()
    val writes = mutableListOf<String>()
    var rejectMetadata = false
    var failDeleteOnce = false
    override fun get(key: String) = objects[key]
    override fun list(prefix: String) = objects.keys.filter { it.startsWith(prefix) }
    override fun put(key: String, bytes: ByteArray, previous: StoredObject?) {
        if (previous?.etag != objects[key]?.etag || (rejectMetadata && key.endsWith("maven-metadata.xml") && "/1.0/" !in key)) throw Conflict()
        objects[key] = StoredObject(bytes, digest(bytes))
        writes.add(key)
    }

    override fun delete(key: String) {
        if (failDeleteOnce) {
            failDeleteOnce = false; error("Simulated interruption")
        }
        objects.remove(key)
        writes.add("DELETE $key")
    }

    fun seed(key: String, value: String) {
        objects[key] = StoredObject(value.toByteArray(), digest(value.toByteArray()))
    }
}

class MavenManagerTest {
    @TempDir
    lateinit var source: Path
    private val now = Instant.parse("2026-10-10T12:34:56Z")
    private fun payload(version: String = "1.0", operation: String = "publish", dryRun: Boolean = false) = MavenPayload(
        operation, if (version.endsWith("-SNAPSHOT")) "snapshots" else "releases", "dev.swiftstorm", "demo", version,
        dryRun, "ririf4/demo", "123", "maven-publication", "dev.swiftstorm:demo:$version"
    )

    private fun publication(p: MavenPayload, content: String = "jar", timestamp: String = "1.0-20261010.123456-1") {
        val dir = Files.createDirectories(source.resolve(p.versionPath))
        val fileVersion = if (p.snapshot) timestamp else p.version
        Files.writeString(
            dir.resolve("demo-$fileVersion.pom"),
            "<project><groupId>dev.swiftstorm</groupId><artifactId>demo</artifactId><version>${p.version}</version></project>"
        )
        Files.writeString(dir.resolve("demo-$fileVersion.jar"), content)
        if (p.snapshot) Files.writeString(
            dir.resolve("maven-metadata.xml"), """
            <metadata><groupId>dev.swiftstorm</groupId><artifactId>demo</artifactId><version>${p.version}</version>
            <versioning><lastUpdated>20261010123456</lastUpdated><snapshot><timestamp>20261010.123456</timestamp><buildNumber>1</buildNumber></snapshot>
            <snapshotVersions><snapshotVersion><extension>jar</extension><value>$timestamp</value><updated>20261010123456</updated></snapshotVersion>
            <snapshotVersion><extension>pom</extension><value>$timestamp</value><updated>20261010123456</updated></snapshotVersion></snapshotVersions>
            </versioning></metadata>""".trimIndent()
        )
    }

    private val rootKey = "releases/dev/swiftstorm/demo/maven-metadata.xml"

    @Test
    fun `SemVer publish and delete maintain highest pointers and metadata checksums`() {
        val store = MemoryStore()
        val manager = MavenManager(store) { now }
        for (value in listOf("4.10.0", "4.2.0", "4.11.0-beta.2")) {
            val p = payload(value)
            publication(p)
            manager.execute(p, source)
        }
        fun check(latest: String, release: String) {
            val xml = store.objects.getValue(rootKey).bytes
            val root = parseXml(xml).documentElement.child("versioning")!!
            assertEquals(latest, root.child("latest")?.textContent)
            assertEquals(release, root.child("release")?.textContent)
            assertEquals(digest(xml, "SHA-256"), store.objects.getValue("$rootKey.sha256").bytes.toString(Charsets.UTF_8))
        }
        check("4.11.0-beta.2", "4.10.0")
        manager.execute(payload("4.10.0", operation = "delete"), null)
        check("4.11.0-beta.2", "4.2.0")
    }

    @Test
    fun `GenCal publication preserves full coordinates and delete preserves other build variants`() {
        val first = payload("126.3.5.r105-beta.2+env26.1.2.sha67b5b073")
        val second = payload("126.3.5.r105-beta.2+env26.2.shaabcdef")
        val store = MemoryStore()
        val manager = MavenManager(store) { now }
        for (p in listOf(first, second)) {
            publication(p)
            manager.execute(p, source)
            assertTrue(store.objects.containsKey(p.prefix + "demo-${p.version}.jar"))
        }
        manager.execute(first.copy(operation = "delete"), null)
        assertTrue(store.list(first.prefix).isEmpty())
        assertTrue(store.objects.containsKey(second.prefix + "demo-${second.version}.jar"))
        assertEquals(second.version, parseXml(store.objects.getValue(rootKey).bytes).getElementsByTagName("latest").item(0).textContent)
    }

    private fun oldMetadata() =
        "<metadata><groupId>dev.swiftstorm</groupId><artifactId>demo</artifactId><versioning><latest>0.9</latest><release>0.9</release><versions><version>0.9</version></versions></versioning><plugins><plugin><prefix>demo</prefix></plugin></plugins></metadata>"

    @Test
    fun `publish merges metadata and regenerates checksums`() {
        val store = MemoryStore(); store.seed(rootKey, oldMetadata())
        val p = payload(); publication(p)
        MavenManager(store) { now }.execute(p, source)
        val xml = store.objects.getValue(rootKey).bytes
        val doc = parseXml(xml)
        assertEquals(
            listOf("0.9", "1.0"),
            (0 until doc.getElementsByTagName("version").length).map { doc.getElementsByTagName("version").item(it).textContent })
        assertEquals("1.0", doc.getElementsByTagName("latest").item(0).textContent)
        assertEquals("demo", doc.getElementsByTagName("prefix").item(0).textContent)
        assertEquals(digest(xml, "SHA-1"), store.objects.getValue("$rootKey.sha1").bytes.toString(Charsets.UTF_8))
        assertEquals("20261010123456", doc.getElementsByTagName("lastUpdated").item(0).textContent)
        assertTrue(store.objects.containsKey(p.prefix + "demo-1.0.jar.sha512"))
    }

    @Test
    fun `dry run does not mutate store`() {
        val p = payload(dryRun = true); publication(p)
        val store = MemoryStore(); MavenManager(store).execute(p, source)
        assertTrue(store.objects.isEmpty()); assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `release republish is idempotent and checksum whitespace is accepted`() {
        val store = MemoryStore();
        val p = payload(); publication(p)
        val manager = MavenManager(store) { now }; manager.execute(p, source)
        val key = p.prefix + "demo-1.0.jar.sha1"
        store.seed(key, store.objects.getValue(key).bytes.toString(Charsets.UTF_8).uppercase() + "\n")
        store.writes.clear(); manager.execute(p, source)
        assertTrue(store.writes.none { it.startsWith(p.prefix) })
    }

    @Test
    fun `changed release is rejected before writes`() {
        val p = payload(); publication(p);
        val store = MemoryStore();
        val manager = MavenManager(store)
        manager.execute(p, source); store.writes.clear(); publication(p, "changed jar")
        assertFailsWith<IllegalArgumentException> { manager.execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `adding files to published release is forbidden`() {
        val p = payload(); publication(p);
        val store = MemoryStore();
        val manager = MavenManager(store)
        manager.execute(p, source); store.writes.clear()
        Files.writeString(source.resolve(p.versionPath).resolve("demo-1.0-sources.jar"), "sources")
        assertFailsWith<IllegalArgumentException> { manager.execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `delete only removes selected version and can resume interruption`() {
        val p = payload(); publication(p);
        val store = MemoryStore();
        val manager = MavenManager(store) { now }
        store.seed(rootKey, oldMetadata()); store.seed("releases/dev/swiftstorm/demo/0.9/demo-0.9.jar", "old")
        store.seed("releases/dev/swiftstorm/demo/1.01/demo-1.01.jar", "other")
        manager.execute(p, source); store.failDeleteOnce = true
        val delete = payload(operation = "delete")
        assertFailsWith<IllegalStateException> { manager.execute(delete, null) }
        manager.execute(delete, null)
        assertTrue(store.list(p.prefix).isEmpty())
        assertEquals("0.9", parseXml(store.objects.getValue(rootKey).bytes).getElementsByTagName("latest").item(0).textContent)
        assertTrue(store.objects.containsKey("releases/dev/swiftstorm/demo/1.01/demo-1.01.jar"))
        assertTrue(store.objects.containsKey("releases/dev/swiftstorm/demo/0.9/demo-0.9.jar"))
    }

    @Test
    fun `last version deletion clears latest and release`() {
        val p = payload(); publication(p);
        val store = MemoryStore();
        val manager = MavenManager(store)
        manager.execute(p, source); manager.execute(payload(operation = "delete"), null)
        val doc = parseXml(store.objects.getValue(rootKey).bytes)
        assertEquals(0, doc.getElementsByTagName("latest").length)
        assertEquals(0, doc.getElementsByTagName("release").length)
        assertEquals(0, doc.getElementsByTagName("version").length)
    }

    @Test
    fun `update snapshots preserves previous timestamped artifacts`() {
        val p = payload("1.0-SNAPSHOT", "update"); publication(p)
        val store = MemoryStore(); store.seed(p.prefix + "demo-1.0-20261009.120000-1.jar", "old")
        MavenManager(store).execute(p, source)
        assertTrue(store.objects.containsKey(p.prefix + "demo-1.0-20261009.120000-1.jar"))
        val dataWrite = store.writes.indexOf(p.prefix + "demo-1.0-20261010.123456-1.jar")
        val metadataWrite = store.writes.indexOf(p.prefix + "maven-metadata.xml")
        assertTrue(dataWrite < metadataWrite)
        assertEquals(0, parseXml(store.objects.getValue("snapshots/dev/swiftstorm/demo/maven-metadata.xml").bytes).getElementsByTagName("release").length)
    }

    @Test
    fun `snapshot metadata missing an artifact is rejected before writes`() {
        val p = payload("1.0-SNAPSHOT", "update"); publication(p)
        Files.delete(source.resolve(p.versionPath).resolve("demo-1.0-20261010.123456-1.jar"))
        val store = MemoryStore()
        assertFailsWith<IllegalArgumentException> { MavenManager(store).execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `snapshot timestamp cannot be overwritten`() {
        val p = payload("1.0-SNAPSHOT", "update"); publication(p)
        val store = MemoryStore();
        val manager = MavenManager(store); manager.execute(p, source)
        store.writes.clear(); publication(p, "changed")
        assertFailsWith<IllegalArgumentException> { manager.execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `older snapshot cannot roll discovery metadata back`() {
        val p = payload("1.0-SNAPSHOT", "update"); publication(p)
        val store = MemoryStore();
        val manager = MavenManager(store); manager.execute(p, source)
        val key = p.prefix + "maven-metadata.xml"
        store.seed(key, store.objects.getValue(key).bytes.toString(Charsets.UTF_8).replace("20261010123456", "20261011123456"))
        store.writes.clear()
        assertFailsWith<IllegalArgumentException> { manager.execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `files for a different version cannot be uploaded under the requested version`() {
        val p = payload(); publication(p)
        Files.writeString(source.resolve(p.versionPath).resolve("demo-2.0.jar"), "wrong version")
        val store = MemoryStore()
        assertFailsWith<IllegalArgumentException> { MavenManager(store).execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `POM coordinates must match the requested publication`() {
        val p = payload(); publication(p)
        Files.writeString(
            source.resolve(p.versionPath).resolve("demo-1.0.pom"),
            "<project><groupId>wrong</groupId><artifactId>demo</artifactId><version>1.0</version></project>"
        )
        val store = MemoryStore()
        assertFailsWith<IllegalArgumentException> { MavenManager(store).execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `older snapshot build at the same timestamp cannot replace metadata`() {
        val p = payload("1.0-SNAPSHOT", "update"); publication(p)
        val store = MemoryStore();
        val manager = MavenManager(store); manager.execute(p, source)
        val key = p.prefix + "maven-metadata.xml"
        store.seed(key, store.objects.getValue(key).bytes.toString(Charsets.UTF_8).replace("<buildNumber>1</buildNumber>", "<buildNumber>2</buildNumber>"))
        store.writes.clear()
        assertFailsWith<IllegalArgumentException> { manager.execute(p, source) }
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `invalid and dangerous payloads are rejected`() {
        for (p in listOf(
            payload().copy(groupId = "../dev"), payload().copy(version = "../1"),
            payload().copy(repository = "snapshots"), payload(operation = "update"),
            payload(operation = "delete").copy(confirmDelete = null), payload().copy(sourceArtifact = "name\ninjected=yes")
        )) {
            assertFailsWith<IllegalArgumentException> { p.validate() }
        }
        val path = source.resolve("payload.json")
        Files.writeString(path, """{"operation":"publish","unexpected":true}""")
        assertFailsWith<IllegalArgumentException> { MavenPayload.read(path) }
    }

    @Test
    fun `malformed parent metadata and XML entities never trigger writes`() {
        val p = payload(); publication(p)
        val store = MemoryStore(); store.seed(rootKey, "<metadata><groupId>wrong</groupId></metadata>")
        assertFailsWith<IllegalArgumentException> { MavenManager(store).execute(p, source) }
        assertTrue(store.writes.isEmpty())
        assertFails { parseXml("<!DOCTYPE metadata [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><metadata>&x;</metadata>".toByteArray()) }
    }

    @Test
    fun `metadata conflict prevents deletion`() {
        val store = MemoryStore(); store.seed(rootKey, oldMetadata()); store.seed(payload().prefix + "demo-1.0.jar", "jar")
        store.rejectMetadata = true
        assertFailsWith<Conflict> { MavenManager(store).execute(payload(operation = "delete"), null) }
        assertTrue(store.objects.containsKey(payload().prefix + "demo-1.0.jar"))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `encoding preserves RFC3986 unreserved bytes only`() {
        assertEquals("a%20b%2Bc%2F%E6%97%A5", encode("a b+c/日"))
    }

    @Test
    fun `signature includes conditional headers and payload digest`() {
        val headers = signatureHeaders(
            "PUT", URI("https://example.r2.cloudflarestorage.com/bucket/a.jar"),
            "hello".toByteArray(), mapOf("if-none-match" to "*"), "test", "secret", Instant.parse("2026-10-10T00:00:00Z")
        )
        assertEquals(digest("hello".toByteArray()), headers["x-amz-content-sha256"])
        assertEquals("20261010T000000Z", headers["x-amz-date"])
        assertEquals("*", headers["if-none-match"])
        assertTrue(headers.getValue("authorization").contains("SignedHeaders=host;if-none-match;x-amz-content-sha256;x-amz-date"))
        // Independently calculated with Python's hashlib/hmac, rather than the production signer.
        assertTrue(headers.getValue("authorization").endsWith("Signature=a7fc46edcb081ec5b97b0de57a1df01b9b996f44a4a9510c632ed3c1d686c741"))
    }
}
