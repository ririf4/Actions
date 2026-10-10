package maven

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal fun parseXml(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    isXIncludeAware = false
    isExpandEntityReferences = false
}.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

internal fun Element.child(name: String): Element? = (0 until childNodes.length)
    .map { childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == name }

internal fun Element.set(name: String, value: String?) {
    val old = child(name)
    if (value == null) {
        old?.let { removeChild(it) }; return
    }
    (old ?: ownerDocument.createElement(name).also { appendChild(it) }).textContent = value
}

internal fun metadata(old: ByteArray?, payload: MavenPayload, now: Instant): ByteArray {
    val doc = parseXml(old ?: "<metadata/>".toByteArray())
    val root = doc.documentElement
    require(root.tagName == "metadata") { "Invalid Maven metadata root" }
    for ((name, value) in listOf("groupId" to payload.groupId, "artifactId" to payload.artifactId)) {
        require(root.child(name)?.textContent.let { it == null || it == value }) { "Metadata coordinate mismatch" }
        root.set(name, value)
    }
    require(root.child("version") == null) { "Expected artifact-level metadata" }
    val versioning = root.child("versioning") ?: doc.createElement("versioning").also { root.appendChild(it) }
    val versions = versioning.child("versions") ?: doc.createElement("versions").also { versioning.appendChild(it) }
    val values = (0 until versions.childNodes.length).map { versions.childNodes.item(it) }
        .filterIsInstance<Element>().filter { it.tagName == "version" }.map { it.textContent }.toMutableList()
    val previousLatest = versioning.child("latest")?.textContent
    val previousRelease = versioning.child("release")?.textContent
    val newlyAdded = payload.version !in values
    if (payload.operation == "delete") values.removeAll { it == payload.version }
    else if (newlyAdded) values.add(payload.version)
    while (versions.hasChildNodes()) versions.removeChild(versions.firstChild)
    val genCal = values.distinct().mapNotNull { value -> GenCalVersion.parse(value)?.let { value to it } }
    val semVer = values.distinct().mapNotNull { value -> SemVerVersion.parse(value)?.let { value to it } }
    // Preserve GenCal priority when formats are mixed; incomparable legacy values keep their order.
    val recognized = (genCal.map { it.first } + semVer.map { it.first }).toSet()
    val legacy = values.distinct().filter { it !in recognized }
    val ordered = legacy + semVer.sortedBy { it.second }.map { it.first } + genCal.sortedBy { it.second }.map { it.first }
    ordered.forEach { value -> versions.appendChild(doc.createElement("version").apply { textContent = value }) }
    fun <T : Comparable<T>> highest(candidates: List<Pair<String, T>>, previous: String?): String? {
        val best = candidates.maxByOrNull { it.second } ?: return null
        return candidates.firstOrNull { it.first == previous && it.second.compareTo(best.second) == 0 }?.first ?: best.first
    }

    val latest = if (genCal.isNotEmpty()) highest(genCal, previousLatest)
    else if (semVer.isNotEmpty()) highest(semVer, previousLatest)
    else if (payload.operation != "delete" && newlyAdded) payload.version
    else previousLatest?.takeIf { it in values } ?: values.lastOrNull()
    val release = if (genCal.isNotEmpty() || semVer.isNotEmpty()) highest(genCal.filter { it.second.stable }, previousRelease)
        ?: highest(semVer.filter { it.second.stable }, previousRelease)
        ?: previousRelease?.takeIf { it in legacy && !it.endsWith("-SNAPSHOT") }
        ?: legacy.lastOrNull { !it.endsWith("-SNAPSHOT") }
    else if (payload.operation != "delete" && newlyAdded && !payload.snapshot) payload.version
    else previousRelease?.takeIf { it in values } ?: values.lastOrNull { !it.endsWith("-SNAPSHOT") }
    versioning.set("latest", latest)
    versioning.set("release", release)
    versioning.set("lastUpdated", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(now))
    val transformer = TransformerFactory.newInstance().apply {
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    }.newTransformer()
    return ByteArrayOutputStream().also { transformer.transform(DOMSource(doc), StreamResult(it)) }.toByteArray()
}
