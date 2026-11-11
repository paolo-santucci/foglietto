package com.paolosantucci.foglietto.shared.editor

/**
 * Share-enablement predicate for the editor toolbar: `true` iff [text] holds anything that
 * is not whitespace.
 *
 * Promoted from Swift so both front-ends gate Share identically (FR-08): this replaces the
 * inline `!text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty` in
 * `iosApp/iosApp/Chrome/ChromeOverlay.swift` (`update(text:…)`) and is what the Android top
 * bar binds to. Trim semantics are deliberately the same as the trim-guard at the top of
 * `SaveNoteToRecovery.invoke`, so **newlines are trimmed too** — a buffer holding only
 * `"\n\t "` is not shareable.
 *
 * The raw, un-trimmed buffer is what gets shared; this predicate only decides enablement.
 * Total function: no error path.
 *
 * @param text the current editor buffer, verbatim.
 * @return `false` for the empty buffer and for any whitespace/newline-only buffer.
 */
fun canShare(text: String): Boolean = text.trim().isNotEmpty()
