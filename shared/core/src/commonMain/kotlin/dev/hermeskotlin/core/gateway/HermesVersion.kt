package dev.hermeskotlin.core.gateway

/**
 * A Hermes Agent release as `GET /api/status` names it in `version` (hermes_cli/version_info.py `base_version`): its
 * leading `major.minor.patch`, with anything after it (`+abc`, a dev suffix) ignored. Never read from
 * `release_date`, which stays the same across releases.
 *
 * `0.0.0` isn't a release: it's the placeholder in hermes-agent's `pyproject.toml`, which commit and channel builds
 * stamp as their version (`0.0.0`, or `0.0.0+commit.<stamp>`). It reads as unknown, like `unknown`.
 */
data class HermesVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<HermesVersion> {

    override fun compareTo(other: HermesVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        /** The oldest Hermes Agent Kyllene supports. Older ones still connect, with a warning. */
        val OLDEST_SUPPORTED = HermesVersion(0, 22, 0)

        /** What a build that isn't a release reports. */
        private val PLACEHOLDER = HermesVersion(0, 0, 0)

        private val LEADING = Regex("""^(\d+)\.(\d+)\.(\d+)""")

        /** [version] as a release, or null when it doesn't start like one (`unknown`, empty, `0.0.0`, anything else). */
        fun parse(version: String?): HermesVersion? {
            val parts = LEADING.find(version?.trim() ?: return null)?.groupValues ?: return null
            return HermesVersion(
                parts[1].toIntOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null,
                parts[3].toIntOrNull() ?: return null,
            ).takeIf { it != PLACEHOLDER }
        }

        /** Whether [version] reads as a release older than [OLDEST_SUPPORTED]; false when it can't be read. */
        fun isUnsupported(version: String?): Boolean = parse(version)?.let { it < OLDEST_SUPPORTED } == true
    }
}
