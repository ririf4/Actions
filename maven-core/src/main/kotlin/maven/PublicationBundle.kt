package maven

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PublicationBundle {
    private const val MAX_FILE = 128L * 1024 * 1024
    private const val MAX_TOTAL = 512L * 1024 * 1024

    fun create(payload: MavenPayload, source: Path): ByteArray {
        payload.validate()
        val root = source.toAbsolutePath().normalize()
        val directory = root.resolve(payload.versionPath).normalize()
        require(directory.startsWith(root) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Missing publication ${payload.versionPath}" }
        var ancestor: Path? = directory
        while (ancestor != null && ancestor.startsWith(root)) {
            require(!Files.isSymbolicLink(ancestor)) { "Symlinks are forbidden" }
            ancestor = ancestor.parent
        }
        var total = 0L
        var count = 0
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            Files.walk(directory).use { paths ->
                paths.sorted().forEach { path ->
                    require(!Files.isSymbolicLink(path)) { "Symlinks are forbidden" }
                    if (Files.isDirectory(path)) require(path == directory) { "Nested publication directories are forbidden" }
                    else {
                        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Unsupported publication file" }
                        val size = Files.size(path)
                        total += size
                        require(size <= MAX_FILE && total <= MAX_TOTAL && ++count <= 1024) { "Publication exceeds bundle limits" }
                        zip.putNextEntry(ZipEntry(root.relativize(path).toString().replace('\\', '/')))
                        Files.newInputStream(path).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
        require(count > 0 && buffer.size().toLong() <= MAX_TOTAL) { "Empty or oversized publication bundle" }
        return buffer.toByteArray()
    }

    fun extract(bytes: ByteArray, payload: MavenPayload, destination: Path) {
        payload.validate()
        require(bytes.size.toLong() <= MAX_TOTAL && digest(bytes) == payload.sourceBundleSha256) { "Publication bundle checksum/size mismatch" }
        val root = destination.toAbsolutePath().normalize()
        require(Files.isDirectory(root) && !Files.isSymbolicLink(root)) { "Invalid bundle destination" }
        var total = 0L
        var count = 0
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && name.startsWith(payload.versionPath) && '\\' !in name && names.add(name) && ++count <= 1024) {
                    "Invalid or duplicate bundle entry"
                }
                val filename = name.removePrefix(payload.versionPath)
                require(filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) { "Bundle entry outside the publication" }
                val target = root.resolve(name).normalize()
                require(target.startsWith(root) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Unsafe bundle entry" }
                var ancestor: Path? = target.parent
                while (ancestor != null && ancestor.startsWith(root)) {
                    require(!Files.isSymbolicLink(ancestor)) { "Symlink extraction path" }
                    ancestor = ancestor.parent
                }
                Files.createDirectories(target.parent)
                var size = 0L
                Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        require(size <= MAX_FILE && total <= MAX_TOTAL) { "Expanded bundle exceeds limits" }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        require(count > 0) { "Publication bundle is empty" }
    }
}
