package com.paolosantucci.foglietto.shared.editor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the promoted `canShare(text)` predicate (FR-08, spec §5.1.a).
 *
 * TDD: written FIRST — before the implementation exists, `canShare` is unresolved,
 * so this class cannot compile (RED).
 *
 * Contract: `text.trim().isNotEmpty()` — identical to Swift's
 * `!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty` in
 * `iosApp/iosApp/Chrome/ChromeOverlay.swift` (`update(text:…)`) and identical to the
 * trim-guard at the top of `SaveNoteToRecovery.invoke`, so whitespace **and**
 * newlines are trimmed. Total function — no error cases.
 */
class ShareGateTest {

    // ── happy path: any non-blank buffer is shareable ───────────────────────────

    @Test
    fun givenPlainText_whenCanShare_thenTrue() {
        assertTrue(canShare("hello"))
    }

    @Test
    fun givenTextPaddedWithSpaces_whenCanShare_thenTrue() {
        assertTrue(canShare("  x  "))
    }

    // ── failure path: blank buffers are not shareable ───────────────────────────

    @Test
    fun givenEmptyText_whenCanShare_thenFalse() {
        assertFalse(canShare(""))
    }

    @Test
    fun givenWhitespaceAndNewlinesOnly_whenCanShare_thenFalse() {
        assertFalse(canShare("\n\t "))
    }

    // ── vectors transplanted from the iOS suite ────────────────────────────────
    //
    // RecoveryListViewModelTests / MenuViewModelTests.testShareTracksWhitespaceAndEditedNote:
    //   " \n\t" → disabled, "Note" → enabled, "" → disabled

    @Test
    fun givenIosShareEnablementVectors_whenCanShare_thenMatches() {
        assertFalse(canShare(" \n\t"))
        assertTrue(canShare("Note"))
        assertFalse(canShare(""))
    }

    // ── parity with the SaveNoteToRecovery trim-guard ──────────────────────────
    //
    // FR-08 requires exactly the save-side trim semantics, so every input the save
    // guard rejects must also be un-shareable, and vice versa. Includes a buffer
    // that is only blank *ignorable* whitespace (vertical tab / form feed / CR).

    @Test
    fun givenSaveGuardRejectedBuffers_whenCanShare_thenFalse() {
        listOf("", " ", "   ", "\n", "\r\n", "\t", " \n\t ", "\u000B\u000C").forEach { blank ->
            assertFalse(canShare(blank), "blank buffer must not be shareable: ${blank.map { it.code }}")
        }
    }

    @Test
    fun givenSaveGuardAcceptedBuffers_whenCanShare_thenTrue() {
        listOf("a", " a ", "\na\n", "\u000Ba").forEach { nonBlank ->
            assertTrue(canShare(nonBlank), "non-blank buffer must be shareable: ${nonBlank.map { it.code }}")
        }
    }
}
