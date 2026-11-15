package com.paolosantucci.foglietto.shared.editor

/**
 * Shared inbound-text acceptance and normalization rule (FR-01…FR-04, NFR-05; spec §5.1.1).
 *
 * Both front-ends hand the raw inbound payload to this single function before touching the
 * editor buffer; neither platform normalizes locally, so iOS and Android produce
 * byte-identical text for the same input (NFR-05).
 *
 * Semantics, in order:
 *  1. `null` means "absent payload" (no extra, unreadable inbox) -> `null`.
 *  2. every CRLF (U+000D U+000A) becomes LF (U+000A) in one left-to-right pass, so a
 *     CRLF pair is collapsed exactly once and a standalone CR survives untouched.
 *  3. a result that is blank after `trim()` -> `null`.
 *  4. otherwise the normalized result is returned **verbatim** — never trimmed again and
 *     never truncated by an app-imposed length cap.
 *
 * The `trim()` predicate is Kotlin's `Char.isWhitespace`, i.e. exactly the predicate
 * [canShare] uses, so the inbound verdict and Share enablement cannot diverge (FR-03).
 *
 * Total function: no throw, no I/O, no regex, no platform import; the only allocation is
 * the returned [String] (and nothing at all when the payload holds no CR).
 *
 * Swift consumes this as `IncomingTextKt.acceptIncomingText(_:)`.
 *
 * @param raw the inbound payload exactly as the platform received it, or `null` if absent.
 * @return the normalized payload, or `null` when absent or blank.
 */
fun acceptIncomingText(raw: String?): String? {
    if (raw == null) return null
    val normalized = collapseCrlf(raw)
    return if (normalized.trim().isEmpty()) null else normalized
}

/**
 * Collapses every CRLF pair to a single LF in one left-to-right pass.
 *
 * A lone CR (one not immediately followed by LF) is copied through unchanged, and a
 * payload without any CR is returned as the same instance (no allocation).
 */
private fun collapseCrlf(raw: String): String {
    if (!raw.contains('\r')) return raw
    val normalized = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        val nextIndex = index + 1
        if (char == '\r' && nextIndex < raw.length && raw[nextIndex] == '\n') {
            normalized.append('\n')
            index += 2
        } else {
            normalized.append(char)
            index = nextIndex
        }
    }
    return normalized.toString()
}
