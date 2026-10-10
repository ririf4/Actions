package maven

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublicationBundleTest {
    @TempDir
    lateinit var directory: Path
    private fun payload() = MavenPayload(
        "publish", "releases", "dev.swiftstorm", "demo", "1.0", false,
        sourceBundleKey = "staging/requests/12345678-1234-1234-1234-123456789abc.zip", sourceBundleSha256 = "0".repeat(64)
    )

    private fun zip(name: String, content: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { it.putNextEntry(ZipEntry(name)); it.write(content.toByteArray()); it.closeEntry() }
        return buffer.toByteArray()
    }

    @Test
    fun `bundle round trip is compatible with the repository manager`() {
        val source = Files.createDirectories(directory.resolve("source"))
        val p = payload()
        val version = Files.createDirectories(source.resolve(p.versionPath))
        Files.writeString(
            version.resolve("demo-1.0.pom"),
            "<project><groupId>dev.swiftstorm</groupId><artifactId>demo</artifactId><version>1.0</version></project>"
        )
        Files.writeString(version.resolve("demo-1.0.jar"), "jar")
        val bytes = PublicationBundle.create(p, source)
        val finalPayload = p.copy(sourceBundleSha256 = digest(bytes))
        val destination = Files.createDirectories(directory.resolve("incoming"))
        PublicationBundle.extract(bytes, finalPayload, destination)
        val store = MemoryStore()
        MavenManager(store).execute(finalPayload, destination)
        assertEquals("jar", store.get(p.prefix + "demo-1.0.jar")!!.bytes.toString(Charsets.UTF_8))
        val jsonPath = directory.resolve("payload.json")
        Files.writeString(jsonPath, finalPayload.json())
        assertEquals(finalPayload, MavenPayload.read(jsonPath))
    }

    @Test
    fun `checksum mismatch rejects a bundle before extracting anything`() {
        val bytes = zip(payload().versionPath + "demo-1.0.jar", "jar")
        assertFailsWith<IllegalArgumentException> { PublicationBundle.extract(bytes, payload(), directory) }
        assertEquals(0L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `path traversal other coordinates and backslashes are rejected`() {
        val p = payload()
        for (name in listOf(
            "../../escape", p.versionPath + "../../escape", "dev/swiftstorm/other/1.0/other-1.0.jar",
            p.versionPath + "subdir/demo.jar", p.versionPath + "subdir\\demo.jar", "/" + p.versionPath + "demo.jar"
        )) {
            val bytes = zip(name, "untrusted")
            assertFailsWith<IllegalArgumentException> { PublicationBundle.extract(bytes, p.copy(sourceBundleSha256 = digest(bytes)), directory) }
        }
        assertEquals(0L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `bundle keys cannot read public repository objects or mix sources`() {
        assertFailsWith<IllegalArgumentException> { payload().copy(sourceBundleKey = "releases/dev/demo.jar").validate() }
        assertFailsWith<IllegalArgumentException> { payload().copy(sourceRepository = "ririf4/demo").validate() }
        assertFailsWith<IllegalArgumentException> { payload().copy(sourceBundleSha256 = null).validate() }
    }

    @Test
    fun `extraction refuses an existing target`() {
        val p = payload()
        val path = directory.resolve(p.versionPath + "demo-1.0.jar")
        Files.createDirectories(path.parent); Files.writeString(path, "existing")
        val bytes = zip(p.versionPath + "demo-1.0.jar", "new")
        assertFailsWith<IllegalArgumentException> { PublicationBundle.extract(bytes, p.copy(sourceBundleSha256 = digest(bytes)), directory) }
        assertEquals("existing", Files.readString(path))
    }
}
