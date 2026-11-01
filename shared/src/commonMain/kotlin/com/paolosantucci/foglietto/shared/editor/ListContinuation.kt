package com.paolosantucci.foglietto.shared.editor

/**
 * Result of a successful list continuation.
 * `null` from [ListContinuation.process] means "no continuation — caller inserts a plain \n".
 *
 * Verbatim port of ContinuationResult from lib/domain/editor/list_continuation.dart (Dart oracle, `main`).
 */
data class ContinuationResult(val text: String, val caret: Int)

// ---------------------------------------------------------------------------
// Canon-verbatim regex constants
// Verbatim from the Dart oracle's _bulletRegex / _orderedRegex.
// ---------------------------------------------------------------------------

/**
 * Bullet-list regex — verbatim from BULLET_LIST_EXPRESSION in the Dart oracle.
 * Alternation ORDER is LOAD-BEARING (E-C2):
 *   "- [ ] " and "- [x] " MUST appear BEFORE "- " so the longer token wins.
 *   Interior/trailing spaces are significant — do NOT reorder or trim.
 */
private val bulletRegex = Regex("""^\s*(- \[ \] |- \[x\] |- |\+ |\* )""")

/**
 * Ordered-list regex for continuation — verbatim from the Dart oracle.
 * Note: uses " +" (one-or-more space) not "[ ]+" to match Dart exactly.
 */
private val orderedRegex = Regex("""^(\s*)([a-zA-Z]{1}|[0-9]+)([\.\)]) +""")

/**
 * Closed bullet token map (trimmed key → continuation token).
 * "- [x] " continues as "- [ ] " (checked → unchecked reset, E-C3).
 * Map lookup mirrors the Dart oracle's _bulletTokens exactly.
 */
private val bulletTokens: Map<String, String> = mapOf(
    "- [ ] " to "- [ ] ",
    "- [x] " to "- [ ] ",
    "- " to "- ",
    "+ " to "+ ",
    "* " to "* ",
)

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Pure Markdown list auto-continuation.
 *
 * Verbatim port of ListContinuation from lib/domain/editor/list_continuation.dart (Dart oracle, `main`).
 *
 * Kotlin dialect pins applied throughout:
 *   - String is UTF-16; lastIndexOf(s, startIndex) backward-from-startIndex inclusive — matches Dart 1:1.
 *   - split("\n") ONLY — never lines()/lineSequence() (NFR-04).
 *   - containsMatchIn (never matches, which is matchEntire — would silently misfire on partial lines).
 *   - find() + groupValues[n] (index parity: groupValues[0] == whole match).
 *   - .clamp() → .coerceIn().
 *   - bulletTokens map lookup for "- [x] " → "- [ ] " (E-C3).
 *   - aboveLineEnd == 0 quirk preserved verbatim (E-I2) — do NOT "fix".
 */
object ListContinuation {

    /**
     * Returns a [ContinuationResult] (full post-continuation buffer text + collapsed caret
     * offset) or `null` when no list rule fires (caller inserts a plain `\n`).
     *
     * Total: never throws at caretOffset == 0 or == text.length.
     */
    fun process(fullText: String, caretOffset: Int): ContinuationResult? {
        if (fullText.isEmpty()) return null

        val lineStart = lineStartBefore(fullText, caretOffset)
        val previousLine = fullText.substring(lineStart, caretOffset)

        val bulletResult = matchBullet(previousLine)
        if (bulletResult != null) {
            return extendBullet(
                fullText = fullText,
                caretOffset = caretOffset,
                spacing = bulletResult.first,
                token = bulletResult.second,
            )
        }

        val orderedResult = matchOrdered(previousLine)
        if (orderedResult != null) {
            return extendOrdered(
                fullText = fullText,
                caretOffset = caretOffset,
                spacing = orderedResult.first,
                index = orderedResult.second,
                marker = orderedResult.third,
            )
        }

        return null
    }

    /**
     * Pure helper: increments or decrements an ordered-list index (E-C4).
     *
     * Verbatim port of calculateOrderedIndex from the Dart oracle.
     *
     * - Numeric: parse via [toIntOrNull], add [delta], return result if > 0, else null.
     * - Alpha single char: case-preserving via code units; Z/z (>= 90 after uppercase) +1 → null;
     *   A/a (<= 65 after uppercase) -1 → null. NO wraparound.
     * - [delta] is +1 or -1.
     */
    fun calculateOrderedIndex(index: String, delta: Int): String? {
        val numericValue = index.toIntOrNull()
        if (numericValue != null) {
            val next = numericValue + delta
            return if (next > 0) next.toString() else null  // >0 guard (E-C4)
        }

        // Alpha single-character path — code-unit math, case-preserving.
        if (index.length == 1) {
            val char = index[0].code
            val upper = index.uppercase()[0].code
            return if (delta > 0) {
                if (upper >= 90) null  // 'Z' ceiling — no wraparound
                else (char + 1).toChar().toString()
            } else {
                if (upper <= 65) null  // 'A' floor — no wraparound
                else (char - 1).toChar().toString()
            }
        }

        return null
    }
}

// ---------------------------------------------------------------------------
// Private helpers (verbatim port of the Dart oracle's top-level functions)
// ---------------------------------------------------------------------------

/**
 * Returns the UTF-16 code-unit offset of the start of the line that contains [caretOffset].
 *
 * VERBATIM port of _lineStartBefore (Dart oracle).
 * The formula `lastIndexOf("\n", caretOffset - 1)` then `if (-1) 0 else it + 1` is
 * pinned exactly — do not alter the `caretOffset - 1` argument (E-C1).
 */
private fun lineStartBefore(text: String, caretOffset: Int): Int {
    if (caretOffset == 0) return 0
    val before = text.lastIndexOf("\n", caretOffset - 1)
    return if (before == -1) 0 else before + 1
}

/**
 * Returns the end-of-line offset (exclusive) starting from [from].
 * If there is no newline after [from], returns text.length.
 *
 * Verbatim port of _lineEndAfter (Dart oracle).
 */
private fun lineEndAfter(text: String, from: Int): Int {
    val next = text.indexOf("\n", from)
    return if (next == -1) text.length else next
}

/**
 * Attempts to match [previousLine] against the bullet grammar.
 * Returns (spacing, continuationToken) or null.
 *
 * Verbatim port of _matchBullet (Dart oracle).
 * Uses find() + groupValues (Dart: firstMatch + group(n); index parity: groupValues[0] == whole match).
 */
private fun matchBullet(previousLine: String): Pair<String, String>? {
    val match = bulletRegex.find(previousLine) ?: return null

    val fullMatch = match.groupValues[0]   // groupValues[0] == whole match (parity with Dart group(0))
    val group1 = match.groupValues[1]      // the captured token

    // spacing = leading whitespace = everything before the token
    val tokenStart = fullMatch.length - group1.length
    val spacing = fullMatch.substring(0, tokenStart)
    val trimmedKey = group1
    val token = bulletTokens[trimmedKey] ?: return null

    return Pair(spacing, token)
}

/**
 * Attempts to match [previousLine] against the ordered grammar.
 * Returns (spacing, index, markerChar) or null.
 *
 * Verbatim port of _matchOrdered (Dart oracle).
 */
private fun matchOrdered(previousLine: String): Triple<String, String, String>? {
    val match = orderedRegex.find(previousLine) ?: return null

    val spacing = match.groupValues[1]
    val index = match.groupValues[2]
    val marker = match.groupValues[3]
    return Triple(spacing, index, marker)
}

/**
 * Executes the bullet-list continuation.
 *
 * Verbatim port of _extendBullet (Dart oracle).
 */
private fun extendBullet(
    fullText: String,
    caretOffset: Int,
    spacing: String,
    token: String,
): ContinuationResult? {
    // Already-continues guard: if caret-to-end-of-current-line already matches a bullet → null.
    // MUST use containsMatchIn NOT matches (matches would be matchEntire — silent bug on partial lines).
    val lineEnd = lineEndAfter(fullText, caretOffset)
    val caretToEol = fullText.substring(caretOffset, lineEnd)
    if (bulletRegex.containsMatchIn(caretToEol)) return null

    // Empty-item-ends-list check.
    val lineStart = lineStartBefore(fullText, caretOffset)
    val previousLine = fullText.substring(lineStart, caretOffset)
    val trimmedLine = previousLine.trim()
    val trimmedToken = (spacing + token).trim()

    if (trimmedLine == trimmedToken) {
        if (twoAboveStartsWith(fullText, lineStart, spacing + token)) {
            // Remove the empty marker line; caret sits at lineStart.
            val newText = fullText.substring(0, lineStart) + fullText.substring(lineEnd)
            return ContinuationResult(newText, lineStart)
        }
    }

    // Normal continuation: insert "\n{spacing}{token}".
    val insertion = "\n$spacing$token"
    val newText =
        fullText.substring(0, caretOffset) +
        insertion +
        fullText.substring(caretOffset)
    val newCaret = caretOffset + insertion.length
    return ContinuationResult(newText, newCaret)
}

/**
 * Executes the ordered-list continuation.
 *
 * Verbatim port of _extendOrdered (Dart oracle).
 */
private fun extendOrdered(
    fullText: String,
    caretOffset: Int,
    spacing: String,
    index: String,
    marker: String,
): ContinuationResult? {
    val lineStart = lineStartBefore(fullText, caretOffset)
    val previousLine = fullText.substring(lineStart, caretOffset)
    val lineEnd = lineEndAfter(fullText, caretOffset)

    val trimmedLine = previousLine.trim()
    val markerText = "$spacing$index$marker "
    val trimmedMarker = markerText.trim()

    if (trimmedLine == trimmedMarker) {
        // Empty-item-ends-list check for ordered lists.
        val prevIndex = ListContinuation.calculateOrderedIndex(index, -1)
        if (prevIndex != null) {
            val prevMarkerText = "$spacing$prevIndex$marker "
            if (twoAboveStartsWith(fullText, lineStart, prevMarkerText)) {
                val newText = fullText.substring(0, lineStart) + fullText.substring(lineEnd)
                return ContinuationResult(newText, lineStart)
            }
        }
        // If prevIndex is null (e.g. "1. " — no item 0), fall through to lone-marker-continues.
    }

    // Compute next index.
    val nextIndex = ListContinuation.calculateOrderedIndex(index, 1) ?: return null

    val insertion = "\n$spacing$nextIndex$marker "
    val newText =
        fullText.substring(0, caretOffset) +
        insertion +
        fullText.substring(caretOffset)
    val newCaret = caretOffset + insertion.length
    return ContinuationResult(newText, newCaret)
}

/**
 * Returns true iff the line two above [lineStart] in [text] starts with [prefix].
 *
 * VERBATIM port of _twoAboveStartsWith (Dart oracle).
 *
 * INTENTIONAL QUIRK (E-I2): `if (aboveLineEnd == 0) return false` is preserved EXACTLY.
 * Do NOT "fix" this check — it is the load-bearing invariant that makes a lone first-line
 * marker continue rather than terminate the list.
 *
 * The quirk: if the line immediately above starts at offset 1 (meaning the very first
 * character is '\n'), aboveLineEnd = lineStart - 1 = 0 → we return false → the empty-item
 * branch is not taken → the marker continues.
 */
private fun twoAboveStartsWith(text: String, lineStart: Int, prefix: String): Boolean {
    if (lineStart == 0) return false
    // The '\n' at lineStart-1 ends the line above. Find that line's start.
    val aboveLineEnd = lineStart - 1  // points at the '\n'
    if (aboveLineEnd == 0) return false  // INTENTIONAL QUIRK — do NOT remove (E-I2)
    val aboveLineStart = lineStartBefore(text, aboveLineEnd)
    val aboveLine = text.substring(aboveLineStart, aboveLineEnd)
    return aboveLine.startsWith(prefix)
}
