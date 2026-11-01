package com.paolosantucci.foglietto.shared.recovery

/**
 * Pure, dependency-free filename parser — the inverse of `FileRecoveryRepository`'s stem builder.
 *
 * ## Accepted grammar (millisecond, FR-01, R-05)
 *
 * ```
 * YYYY-MM-DDTHH-MM-SS-mmmZ[.txt]
 * YYYY-MM-DDTHH-MM-SS-mmmZ-<N>[.txt]
 * ```
 *
 * where `mmm` is EXACTLY 3 decimal digits (millisecond precision). A 6-digit (microsecond)
 * fractional component is explicitly rejected (FR-02) — `\d{3}Z` vs `\d{6}Z` is the key
 * discriminator; the regex is fully anchored via [Regex.matchEntire] so a 6-digit variant
 * can never match (the `Z` must immediately follow the 3rd digit).
 *
 * ## Return contract (FR-03)
 *
 * [parse] never throws on any input. Malformed or empty strings, strings with colons
 * (`:`) as separators, truncated stems, and 6-digit fractional variants all return `null`.
 *
 * ## Dialect note (assessment cross-cutting)
 *
 * Dart `RegExp.firstMatch` + `^…$` anchors ≡ Kotlin `Regex.matchEntire` (no anchors needed
 * in the pattern string itself; `matchEntire` requires the whole string to match).
 * `\d` == `[0-9]` in both runtimes.
 *
 * Spec refs: §5.1.1, FR-01, FR-02, FR-03; assessment R-A1, dialect rules.
 */
object RecoveryFilename {

    /**
     * Matches the exact millisecond stem.
     *
     * `\d{3}Z` is the key discriminator that rejects 6-digit (microsecond) variants:
     * since [Regex.matchEntire] requires the ENTIRE string to match, `\d{3}Z` followed
     * by the optional suffix and extension anchor is exact — a `\d{6}Z` string cannot
     * match because after 3 digits the parser expects `Z`, not more digits.
     *
     * Raw string (`"""…"""`) preserves backslashes verbatim — no double-escaping.
     * Groups (7): year, month, day, hour, minute, second, millis.
     */
    private val stemPattern = Regex(
        """(\d{4})-(\d{2})-(\d{2})T(\d{2})-(\d{2})-(\d{2})-(\d{3})Z(?:-\d+)?(?:\.txt)?"""
    )

    /**
     * Parses [filename] and returns the corresponding UTC [RecoveryInstant], or `null`
     * if the name does not match the millisecond grammar.
     *
     * Never throws.
     */
    fun parse(filename: String): RecoveryInstant? {
        val match = stemPattern.matchEntire(filename) ?: return null
        // groupValues[0] == whole match; capture groups start at index 1
        val year    = match.groupValues[1].toInt()
        val month   = match.groupValues[2].toInt()
        val day     = match.groupValues[3].toInt()
        val hour    = match.groupValues[4].toInt()
        val minute  = match.groupValues[5].toInt()
        val second  = match.groupValues[6].toInt()
        val millis  = match.groupValues[7].toInt()
        return RecoveryInstant(year, month, day, hour, minute, second, millis)
    }
}
