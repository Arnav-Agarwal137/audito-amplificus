package com.arnavagarwal.sound.core

/**
 * Parser for AutoEq "ParametricEQ.txt" files, the format verified in research/data-models-standards.md:
 *
 *   Preamp: -6.4 dB
 *   Filter 1: ON LSC Fc 105 Hz Gain 5.3 dB Q 0.70
 *   Filter 2: ON PK Fc 2000 Hz Gain -2.1 dB Q 1.41
 *
 * The user imports their own earbud's file (the measurement data has no stated licence, so we do not
 * bundle it - ARCHITECTURE.md). Lines that are OFF, blank or comments are skipped; anything else that
 * cannot be read is reported, never silently dropped.
 */
data class AutoEqProfile(val name: String, val preampDb: Double, val filters: List<ParametricFilter>) {
    fun curve(): Curve = Curve.of(filters, preampDb)

    companion object {
        private val preampRe = Regex("""^\s*Preamp:\s*(-?[0-9.]+)\s*dB""", RegexOption.IGNORE_CASE)
        private val filterRe = Regex(
            """^\s*Filter\s*\d+:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([0-9.]+)\s*Hz(?:\s+Gain\s+(-?[0-9.]+)\s*dB)?(?:\s+Q\s+([0-9.]+))?""",
            RegexOption.IGNORE_CASE,
        )

        class ParseResult(val profile: AutoEqProfile?, val problems: List<String>)

        fun parse(name: String, text: String): ParseResult {
            var preamp = 0.0
            val filters = mutableListOf<ParametricFilter>()
            val problems = mutableListOf<String>()
            text.lines().forEachIndexed { n, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                preampRe.find(line)?.let { preamp = it.groupValues[1].toDouble(); return@forEachIndexed }
                val m = filterRe.find(line)
                if (m == null) { problems += "line ${n + 1} not understood: $line"; return@forEachIndexed }
                if (m.groupValues[1].equals("OFF", true)) return@forEachIndexed
                val type = when (m.groupValues[2].uppercase()) {
                    "PK", "PEQ" -> FilterType.PEAK
                    "LSC", "LS", "LSQ" -> FilterType.LOW_SHELF
                    "HSC", "HS", "HSQ" -> FilterType.HIGH_SHELF
                    "LP", "LPQ" -> FilterType.LOW_PASS
                    "HP", "HPQ" -> FilterType.HIGH_PASS
                    else -> { problems += "line ${n + 1}: unknown filter type ${m.groupValues[2]}"; return@forEachIndexed }
                }
                val fc = m.groupValues[3].toDouble()
                val gain = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
                val q = m.groupValues[5].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.707
                if (fc <= 0 || fc > 24_000 || q <= 0) { problems += "line ${n + 1}: value out of range"; return@forEachIndexed }
                filters += ParametricFilter(type, fc, gain, q)
            }
            val profile = if (filters.isEmpty()) null else AutoEqProfile(name, preamp, filters)
            if (profile == null) problems += "no usable filters found"
            return ParseResult(profile, problems)
        }
    }
}
