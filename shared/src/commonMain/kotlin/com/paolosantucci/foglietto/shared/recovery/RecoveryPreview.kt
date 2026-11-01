package com.paolosantucci.foglietto.shared.recovery

/**
 * Pure, dependency-free preview truncation helper.
 *
 * Port of Dart `RecoveryPreview` (abstract final class with static fns).
 * Kotlin equivalent: `object` with pure functions.
 *
 * ## Invariants of [truncate] (FR-04, FR-05, FR-06)
 *
 *   - `result.length <= MAX_LENGTH` (UTF-16 code units — Kotlin `String.length` == code units)
 *   - `result` contains no `'\n'`
 *   - every `\s*\n+\s*` run is collapsed to a single `' '`
 *   - No surrogate pair is ever split (UTF-16-safe truncation)
 *   - No ellipsis is appended — that is a UI concern
 *   - An input shorter than [MAX_LENGTH] code units with no `'\n'` is returned unchanged
 *
 * ## Dialect notes (assessment)
 *
 * - `split("\n")` only — NEVER `lines()` / `lineSequence()` (they also split on `\r\n`/`\r`,
 *   breaking the verbatim port). Here we use `replaceAll` on the `\s*\n+\s*` run pattern
 *   with a plain regex — equivalent to the Dart `replaceAll(_newlineRun, ' ')` path.
 * - Kotlin `String.length` counts UTF-16 code units, identical to Dart's `String.length`.
 * - `Char.isHighSurrogate()` is the Kotlin equivalent of the Dart `_isHighSurrogate` check.
 *
 * ## PREVIEW_READ_LIMIT and lenient head decode
 *
 * [PREVIEW_READ_LIMIT] (`512` bytes) is declared here alongside [MAX_LENGTH] so that
 * `TASK-04` (`FileRecoveryRepository`) can import it from the recovery package without
 * a circular dependency.  The okio `Buffer.readUtf8()` call that decodes the 512-byte
 * head leniently (tolerating a mid-UTF-8 window) lives in `FileRecoveryRepository.list`
 * (TASK-04, out of scope here). The [PORTING-ADDITION] test in [RecoveryPreviewTest]
 * drives the decode via an inline `Buffer.readUtf8()` call to prove that [truncate]
 * itself never throws on the resulting (potentially replacement-character) string.
 *
 * Spec refs: §5.1.1, FR-04, FR-05, FR-06; assessment R-A2, NFR-04.
 */
object RecoveryPreview {

    /** Maximum preview length in UTF-16 code units (FR-05, R-A2). */
    const val MAX_LENGTH: Int = 80

    /**
     * Bytes read from the head of a recovery file before truncation to [MAX_LENGTH]
     * UTF-16 code units. Declared here so [FileRecoveryRepository] can use it without
     * a separate constant file. (FR-06)
     */
    const val PREVIEW_READ_LIMIT: Int = 512

    /**
     * Collapses every `\s*\n+\s*` run in [head] to a single space, then hard-cuts
     * the result to at most [MAX_LENGTH] UTF-16 code units without splitting a
     * surrogate pair. Never appends an ellipsis.
     *
     * Uses `split("\n")` only — never `lines()` / `lineSequence()` (NFR-04 dialect gate).
     * The actual collapse is done via [Regex.replace] which is equivalent to Dart's
     * `replaceAll` and is `'\n'`-only clean.
     */
    fun truncate(head: String): String {
        val collapsed = collapseNewlines(head)
        return safeTruncate(collapsed)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Replaces every sequence of `\s*\n+\s*` (optional whitespace, one or more
     * newlines, optional whitespace) with a single space.
     *
     * The check `if (!head.contains('\n')) return head` provides the identity path
     * for inputs with no newlines — matching the Dart oracle's fast path.
     */
    private val newlineRun = Regex("""\s*\n+\s*""")

    private fun collapseNewlines(text: String): String {
        if (!text.contains('\n')) return text
        return newlineRun.replace(text, " ")
    }

    /**
     * Hard-cuts [text] to at most [MAX_LENGTH] UTF-16 code units.
     *
     * If the cut point lands on a HIGH SURROGATE (0xD800..0xDBFF), steps back one
     * code unit so the surrogate pair is never split (FR-05, NFR-04).
     *
     * A high surrogate at position `cutAt - 1` (the last char before the cut) means
     * we are about to cut between the two halves of a 4-byte emoji — step back to
     * `cutAt - 1` so both surrogates are dropped rather than leaving a lone high
     * surrogate in the output.
     */
    private fun safeTruncate(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        var cutAt = MAX_LENGTH
        // If the code unit just before the cut is a high surrogate, step back so
        // the pair is not split.
        if (text[cutAt - 1].isHighSurrogate()) {
            cutAt -= 1
        }
        return text.substring(0, cutAt)
    }
}
