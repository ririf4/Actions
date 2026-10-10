package maven

import java.math.BigInteger

/** SemVer 2.0 precedence; build metadata does not affect comparison. */
class SemVerVersion private constructor(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val preRelease: List<String>,
    val buildMetadata: String?
) : Comparable<SemVerVersion> {
    val stable get() = preRelease.isEmpty()

    override fun compareTo(other: SemVerVersion): Int {
        for ((left, right) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
            val result = left.compareTo(right)
            if (result != 0) return result
        }
        return comparePreRelease(preRelease, other.preRelease)
    }

    companion object {
        private val pattern =
            Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?")

        fun parse(value: String): SemVerVersion? {
            // Maven SNAPSHOTs retain the existing timestamp/build-number lifecycle.
            if (value.length > 200 || value.endsWith("-SNAPSHOT")) return null
            val parts = pattern.matchEntire(value)?.groupValues ?: return null
            val preRelease = parts[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (!validPreRelease(preRelease)) return null
            return SemVerVersion(parts[1].toBigInteger(), parts[2].toBigInteger(), parts[3].toBigInteger(), preRelease, parts[5].ifEmpty { null })
        }
    }
}

internal fun validPreRelease(parts: List<String>) = parts.none {
    it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')
}

internal fun comparePreRelease(left: List<String>, right: List<String>): Int {
    if (left.isEmpty() != right.isEmpty()) return if (left.isEmpty()) 1 else -1
    for (i in 0 until minOf(left.size, right.size)) {
        val a = left[i]
        val b = right[i]
        val aNumber = a.takeIf { it.all(Char::isDigit) }?.toBigInteger()
        val bNumber = b.takeIf { it.all(Char::isDigit) }?.toBigInteger()
        val result = when {
            aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
            aNumber != null -> -1
            bNumber != null -> 1
            else -> a.compareTo(b)
        }
        if (result != 0) return result
    }
    return left.size.compareTo(right.size)
}
