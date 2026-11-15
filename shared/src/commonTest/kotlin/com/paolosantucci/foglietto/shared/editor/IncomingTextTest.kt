package com.paolosantucci.foglietto.shared.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the shared inbound-text rule `acceptIncomingText(raw: String?): String?`
 * (FR-01…FR-04, NFR-05; spec §5.1.1, §7.1).
 *
 * TDD: written FIRST — before `IncomingText.kt` exists the symbol is unresolved, so this
 * class cannot compile (RED). Green only when the four ordered steps hold:
 *
 *   1. `null`                            -> `null`
 *   2. every CRLF (U+000D U+000A) -> LF   (one left-to-right pass; a lone CR survives)
 *   3. `result.trim().isEmpty()`         -> `null`
 *   4. otherwise                         -> `result` **verbatim** (never trimmed, no cap)
 *
 * The `trim()` predicate is Kotlin's `Char.isWhitespace`, i.e. exactly the predicate
 * `ShareGate.canShare` uses, so the two verdicts cannot diverge (FR-03: one table).
 * The rule is a total function: no input throws, including a lone surrogate.
 */
class IncomingTextTest {

    // ── acceptance: absent and blank payloads are rejected ─────────────────────

    @Test
    fun givenNullPayload_whenAccept_thenNull() {
        assertNull(acceptIncomingText(null))
    }

    @Test
    fun givenEmptyOrWhitespaceOnlyPayload_whenAccept_thenNull() {
        for (blank in listOf("", "   ", "\t", "\r\n", "\r\n\t ", "\n\n")) {
            assertNull(acceptIncomingText(blank), "expected blank payload to be rejected: $blank")
        }
    }

    @Test
    fun givenSingleVisibleCharacter_whenAccept_thenThatCharacter() {
        assertEquals("a", acceptIncomingText("a"))
    }

    // ── CRLF rewriting (FR-02, EC-05, EC-07) ───────────────────────────────────

    @Test
    fun givenCrlfTerminatedLines_whenAccept_thenTrailingNewlineSurvives() {
        assertEquals("a\nb\n", acceptIncomingText("a\r\nb\r\n"))
    }

    @Test
    fun givenLoneCrBeforeACrlf_whenAccept_thenOnlyOnePairIsCollapsed() {
        // Single left-to-right pass: "a" + "\r" + "\r\n" + "b" -> "a\r\nb".
        assertEquals("a\r\nb", acceptIncomingText("a\r\r\nb"))
    }

    @Test
    fun givenStandaloneCrWithoutLf_whenAccept_thenUnchanged() {
        assertEquals("a\rb", acceptIncomingText("a\rb"))
    }

    @Test
    fun givenConsecutiveCrlfPairs_whenAccept_thenBothBecomesLf() {
        assertEquals("a\n\nb", acceptIncomingText("a\r\n\r\nb"))
    }

    @Test
    fun givenCrlfThenLoneLf_whenAccept_thenNoDoubleCollapse() {
        assertEquals("a\n\nb", acceptIncomingText("a\r\n\nb"))
    }

    @Test
    fun givenCrlfOnlyPayload_whenAccept_thenNull() {
        assertNull(acceptIncomingText("\r\n"))
    }

    // ── verbatim preservation (FR-04, EC-09) ───────────────────────────────────

    @Test
    fun givenPaddedPayload_whenAccept_thenNeverTrimmed() {
        assertEquals(" x \n", acceptIncomingText(" x \n"))
    }

    @Test
    fun givenOneMebibytePayload_whenAccept_thenAcceptedWholeWithOnlyCrlfCollapsed() {
        // 10_000 CRLF pairs ("a\r\n" -> "a\n") plus a 50-newline trailing run.
        val filler = "x".repeat(1_048_576 - 30_000 - 50)
        val input = filler + "a\r\n".repeat(10_000) + "\n".repeat(50)
        assertEquals(1_048_576, input.length, "fixture must be exactly one mebibyte")

        val result = acceptIncomingText(input)
        assertNotNull(result, "a one-mebibyte payload must be accepted whole (no cap)")
        assertEquals(input.length - 10_000, result.length)
        assertTrue(result.endsWith("\n".repeat(50)), "the trailing newline run must survive")
        assertTrue(!result.contains('\r'), "no CR may remain in the normalized result")
    }

    // ── Unicode (FR-04, EC-08) ─────────────────────────────────────────────────

    @Test
    fun givenAstralPairCombiningMarkAndNbsp_whenAccept_thenAllPreserved() {
        for (payload in listOf("\uD83D\uDE00", "e\u0301", "x\u00A0", "\uD83D\uDE00e\u0301\u00A0")) {
            assertEquals(payload, acceptIncomingText(payload), "payload must round-trip verbatim: $payload")
        }
    }

    @Test
    fun givenNbspOnlyPayload_whenAccept_thenRejected() {
        // Kotlin's Char.isWhitespace is Character.isWhitespace || Character.isSpaceChar, and
        // U+00A0 is a space separator — so NBSP-only IS blank, exactly as ShareGate says.
        // The one-table rule (FR-03) is binding here: the inbound verdict may never diverge
        // from Share enablement for the same input.
        assertNull(acceptIncomingText("\u00A0"))
    }

    // ── one-table rule (FR-03): acceptance verdict == ShareGate.canShare ───────

    @Test
    fun givenCorpus_whenAcceptAndCanShare_thenVerdictsAgree() {
        for (raw in corpus) {
            assertEquals(
                canShare(raw),
                acceptIncomingText(raw) != null,
                "inbound verdict and Share enablement diverged for: $raw",
            )
        }
    }

    // ── totality (FR-01) ──────────────────────────────────────────────────────

    @Test
    fun givenEveryCorpusEntryIncludingLoneSurrogate_whenAccept_thenNothingThrows() {
        for (raw in corpus + "\uD800") {
            val outcome = runCatching { acceptIncomingText(raw) }
            assertTrue(outcome.isSuccess, "acceptIncomingText threw for: $raw")
        }
        assertNotNull(acceptIncomingText("\uD800"), "a lone surrogate is content, not blank")
    }

    private companion object {
        /** Every vector exercised above, reused by the one-table and totality rules. */
        val corpus: List<String> = listOf(
            "",
            "   ",
            "\t",
            "\r\n",
            "\r\n\t ",
            "\n\n",
            "a",
            "a\r\nb\r\n",
            "a\r\r\nb",
            "a\rb",
            "a\r\n\r\nb",
            "a\r\n\nb",
            " x \n",
            "\uD83D\uDE00",
            "e\u0301",
            "\u00A0",
        )
    }

    // -----------------------------------------------------------------------
    // normalizeLineEndings — the same CRLF rule, without the blank rejection
    // -----------------------------------------------------------------------

    /// Paste and restore carry line endings from elsewhere; they normalize with this
    /// rule, so the buffer never holds a CRLF the continuation/indent math rejects.
    @Test
    fun given_crlf_text_when_normalizeLineEndings_then_onlyLineEndingsChange() {
        assertEquals("a\nb\nc", normalizeLineEndings("a\r\nb\r\nc"))
        assertEquals("a\rb", normalizeLineEndings("a\rb"), "a lone CR survives untouched")
        assertEquals("\n", normalizeLineEndings("\r\n"))
        assertEquals("", normalizeLineEndings(""))
    }

    /// Unlike acceptIncomingText, blank input is NOT rejected: pasting spaces still
    /// pastes them, it just normalizes the endings.
    @Test
    fun given_blank_text_when_normalizeLineEndings_then_textSurvives() {
        assertEquals("  \n ", normalizeLineEndings("  \r\n "))
        assertEquals("\t", normalizeLineEndings("\t"))
    }
}
