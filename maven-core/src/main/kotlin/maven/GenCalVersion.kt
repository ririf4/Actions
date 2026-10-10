package maven

import java.math.BigInteger

/** GenCal-SemVer precedence; build metadata is retained but does not affect comparison. */
class GenCalVersion private constructor(
    val generation: BigInteger,
    val year: Int,
    val minor: BigInteger,
    val patch: BigInteger,
    val revision: BigInteger,
    val preRelease: List<String>,
    val buildMetadata: String?
) : Comparable<GenCalVersion> {
    val stable get() = preRelease.isEmpty()

    // YY alone cannot identify a century. Public display uses the current 2000–2099 convention.
    val publicVersion get() = "${2000 + year}.$minor.$patch"

    override fun compareTo(other: GenCalVersion): Int {
        for ((left, right) in listOf(
            generation to other.generation, year.toBigInteger() to other.year.toBigInteger(),
            minor to other.minor, patch to other.patch, revision to other.revision
        )) {
            val compared = left.compareTo(right)
            if (compared != 0) return compared
        }
        return comparePreRelease(preRelease, other.preRelease)
    }

    companion object {
        private val pattern =
            Regex("([1-9][0-9]*)([0-9]{2})\\.([1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.r(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?")

        fun parse(value: String): GenCalVersion? {
            if (value.length > 200 || value.endsWith("-SNAPSHOT")) return null
            val match = pattern.matchEntire(value) ?: return null
            val parts = match.groupValues
            val preRelease = parts[6].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (!validPreRelease(preRelease)) return null
            return GenCalVersion(
                parts[1].toBigInteger(), parts[2].toInt(), parts[3].toBigInteger(),
                parts[4].toBigInteger(), parts[5].toBigInteger(), preRelease, parts[7].ifEmpty { null }
            )
        }
    }
}
