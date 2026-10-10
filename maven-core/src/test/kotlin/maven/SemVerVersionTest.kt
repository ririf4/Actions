package maven

import java.time.Instant
import kotlin.test.*

class SemVerVersionTest {
    private fun version(value: String) = assertNotNull(SemVerVersion.parse(value), value)
    private fun merge(old: ByteArray?, value: String, operation: String = "publish") = metadata(
        old, MavenPayload(operation, "releases", "dev.swiftstorm", "demo", value), Instant.parse("2026-10-10T12:34:56Z")
    )

    private fun pointer(bytes: ByteArray, name: String) = parseXml(bytes).documentElement.child("versioning")?.child(name)?.textContent

    @Test
    fun `official SemVer precedence examples and numeric components`() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "1.0.1",
            "1.9.0",
            "1.10.0",
            "2.0.0"
        ).map(::version)
        ordered.zipWithNext().forEach { (a, b) -> assertTrue(a < b) }
        assertTrue(version("4.2.0-beta.2") < version("4.2.0-beta.10"))
        assertTrue(version("4.2.0-beta.2") < version("4.2.0-beta.-1"))
        assertTrue(version("4.2.0-A") < version("4.2.0-a"))
        assertTrue(version("99999999999999999999999.0.0") > version("9999999999999999999999.0.0"))
        assertEquals(0, version("4.2.0+001.shaaaa").compareTo(version("4.2.0+999.shabbb")))
    }

    @Test
    fun `strict recognition preserves legacy and Maven snapshot handling`() {
        assertTrue(version("0.0.0").stable)
        assertFalse(version("0.0.0-0").stable)
        for (value in listOf(
            "4.2",
            "v4.2.0",
            "04.2.0",
            "4.02.0",
            "4.2.00",
            "4.2.0-beta.01",
            "4.2.0+",
            "4.2.0-beta..2",
            "4.2.0+env..sha",
            "4.2.0-SNAPSHOT",
            "126.3.5.r105"
        )) {
            assertNull(SemVerVersion.parse(value), value)
        }
    }

    @Test
    fun `older publication cannot roll latest or release back and versions sort numerically`() {
        var xml = merge(null, "4.10.0")
        xml = merge(xml, "4.2.0")
        xml = merge(xml, "4.9.0")
        assertEquals("4.10.0", pointer(xml, "latest"))
        assertEquals("4.10.0", pointer(xml, "release"))
        val entries = parseXml(xml).getElementsByTagName("version")
        assertEquals(listOf("4.2.0", "4.9.0", "4.10.0"), (0 until entries.length).map { entries.item(it).textContent })
    }

    @Test
    fun `latest includes prerelease while release selects only stable and deletion recomputes both`() {
        var xml = merge(null, "4.2.0")
        xml = merge(xml, "4.3.0-beta.10")
        xml = merge(xml, "4.3.0-beta.2")
        assertEquals("4.3.0-beta.10", pointer(xml, "latest"))
        assertEquals("4.2.0", pointer(xml, "release"))
        xml = merge(xml, "4.3.0-beta.10", "delete")
        assertEquals("4.3.0-beta.2", pointer(xml, "latest"))
        xml = merge(xml, "4.2.0", "delete")
        assertNull(pointer(xml, "release"))
        xml = merge(xml, "4.3.0-beta.2", "delete")
        assertNull(pointer(xml, "latest"))
    }

    @Test
    fun `equal build variants retain full identities and current pointers`() {
        val first = "4.2.0+env1.shaaaa"
        val second = "4.2.0+env2.shabbb"
        val xml = merge(merge(null, first), second)
        assertEquals(first, pointer(xml, "latest"))
        assertEquals(first, pointer(xml, "release"))
        assertEquals(2, parseXml(xml).getElementsByTagName("version").length)
        assertEquals(second, pointer(merge(xml, first, "delete"), "latest"))
    }

    @Test
    fun `mixed formats retain GenCal priority and prefer stable SemVer over legacy fallback`() {
        var xml = merge(null, "9.0")
        xml = merge(xml, "4.10.0")
        xml = merge(xml, "126.3.5.r105-beta.2")
        xml = merge(xml, "4.2.0")
        assertEquals("126.3.5.r105-beta.2", pointer(xml, "latest"))
        assertEquals("4.10.0", pointer(xml, "release"))
        xml = merge(xml, "126.3.5.r105-beta.2", "delete")
        assertEquals("4.10.0", pointer(xml, "latest"))
        xml = merge(xml, "4.10.0", "delete")
        xml = merge(xml, "4.2.0", "delete")
        assertEquals("9.0", pointer(xml, "latest"))
        assertEquals("9.0", pointer(xml, "release"))
    }
}
