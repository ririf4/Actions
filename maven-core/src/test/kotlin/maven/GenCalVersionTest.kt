package maven

import java.time.Instant
import kotlin.test.*

class GenCalVersionTest {
    private fun version(value: String) = assertNotNull(GenCalVersion.parse(value))
    private fun payload(value: String, operation: String = "publish") = MavenPayload(
        operation, "releases", "dev.swiftstorm", "demo", value,
        sourceRepository = "ririf4/demo", sourceRunId = "123", sourceArtifact = "publication",
        confirmDelete = "dev.swiftstorm:demo:$value"
    )

    private val now = Instant.parse("2026-10-10T12:34:56Z")
    private fun merge(old: ByteArray?, value: String, operation: String = "publish") = metadata(old, payload(value, operation), now)
    private fun pointer(bytes: ByteArray, name: String) = parseXml(bytes).documentElement.child("versioning")?.child(name)?.textContent

    @Test
    fun `document examples retain metadata and derive public version`() {
        val beta = version("126.3.5.r105-beta.2+env26.1.2.sha67b5b073")
        assertEquals("2026.3.5", beta.publicVersion)
        assertEquals("1", beta.generation.toString())
        assertEquals(listOf("beta", "2"), beta.preRelease)
        assertEquals("env26.1.2.sha67b5b073", beta.buildMetadata)
        assertFalse(beta.stable)
        assertTrue(version("126.3.5.r105+env26.1.2.sha67b5b073") > beta)
        assertEquals("2026.3.5", version("1226.3.5.r105").publicVersion)
        assertEquals("12", version("1226.3.5.r105").generation.toString())
    }

    @Test
    fun `precedence is numeric with SemVer prerelease ordering and ignores build metadata`() {
        val ordered = listOf(
            "126.3.5.r9", "126.3.5.r10-alpha.1", "126.3.5.r10-alpha.2", "126.3.5.r10-alpha.10",
            "126.3.5.r10-beta.2", "126.3.5.r10-beta.10", "126.3.5.r10-rc.1", "126.3.5.r10",
            "126.3.6.r0", "126.4.0.r0", "127.1.0.r0", "226.1.0.r0"
        ).map(::version)
        ordered.zipWithNext().forEach { (left, right) -> assertTrue(left < right, "$left should precede $right") }
        assertEquals(0, version("126.3.5.r105-beta.2+env26.1.2.shaaaa").compareTo(version("126.3.5.r105-beta.2+env99.9.shabbb")))
        assertTrue(version("126.3.5.r105-beta.2") < version("126.3.5.r105-beta.2.extra"))
        assertTrue(version("126.3.5.r105-beta.2") < version("126.3.5.r105-beta.a"))
        assertTrue(version("126.3.5.r105-beta.2") < version("126.3.5.r105-beta.-1"))
        assertTrue(version("126.3.5.r99999999999999999999999") > version("126.3.5.r9999999999999999999999"))
    }

    @Test
    fun `invalid and legacy versions do not silently enter GenCal ordering`() {
        for (value in listOf(
            "1.0",
            "126.3.5",
            "126.0.5.r1",
            "126.3.05.r1",
            "126.3.5.r01",
            "126.3.5.r1-beta.02",
            "126.3.5.r1+",
            "126.3.5.r1+env..sha",
            "126.3.5.r1-SNAPSHOT"
        )) {
            assertNull(GenCalVersion.parse(value), value)
        }
    }

    @Test
    fun `out of order publication and deletion select highest latest and stable release`() {
        val stable = "126.3.5.r105+env26.1.2.shaaaa"
        val beta = "126.3.6.r106-beta.2+env26.1.2.shabbb"
        var xml = merge(null, stable)
        xml = merge(xml, beta)
        xml = merge(xml, "126.3.5.r105-beta.10")
        assertEquals(beta, pointer(xml, "latest"))
        assertEquals(stable, pointer(xml, "release"))
        xml = merge(xml, stable, "delete")
        assertNull(pointer(xml, "release"))
        xml = merge(xml, beta, "delete")
        assertEquals("126.3.5.r105-beta.10", pointer(xml, "latest"))
        xml = merge(xml, "126.3.5.r105-beta.10", "delete")
        assertNull(pointer(xml, "latest"))
    }

    @Test
    fun `build variants stay distinct and equal precedence preserves current pointer`() {
        val first = "126.3.5.r105+env26.1.2.shaaaa"
        val second = "126.3.5.r105+env26.2.shabbb"
        val xml = merge(merge(null, first), second)
        assertEquals(first, pointer(xml, "latest"))
        assertEquals(first, pointer(xml, "release"))
        assertEquals(2, parseXml(xml).getElementsByTagName("version").length)
        assertEquals(second, pointer(merge(xml, first, "delete"), "latest"))
    }

    @Test
    fun `mixed metadata retains legacy entries while preferring GenCal and falls back on deletion`() {
        val legacy = merge(null, "1.0")
        val beta = "126.3.5.r105-beta.2"
        var xml = merge(legacy, beta)
        assertEquals(beta, pointer(xml, "latest"))
        assertEquals("1.0", pointer(xml, "release"))
        xml = merge(xml, "0.9")
        assertEquals(beta, pointer(xml, "latest"))
        assertEquals("1.0", pointer(xml, "release"))
        assertEquals(3, parseXml(xml).getElementsByTagName("version").length)
        assertEquals("0.9", pointer(merge(xml, beta, "delete"), "latest"))
    }
}
