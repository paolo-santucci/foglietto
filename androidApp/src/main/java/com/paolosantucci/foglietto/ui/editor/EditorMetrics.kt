package com.paolosantucci.foglietto.ui.editor

import com.paolosantucci.foglietto.ui.theme.EditorLineHeightMultiplier

/*
 * The pure seam behind [NoteEditorField] (plan TASK-20, FR-17).
 *
 * Plain Kotlin on purpose: it is the only headless proof this surface owns (Tier A, NFR-08 — no
 * Compose UI instrumentation tests this wave), so the *decision* lives here and the composable only
 * binds it to `BasicTextField` state.
 *
 * The former `shouldScrollToTop` predicate was removed with its requester: the value-based
 * `BasicTextField` exposes no scroll control on this Compose version, so the seam could not scroll
 * anything (see [NoteEditorField]'s "Restore" note). FR-29's scroll half is unverified.
 */

/**
 * The canon line height for a font size: exactly 1.4× the size (canon §Typography, "preserve
 * line-height 1.4 verbatim"; the tracked `iosApp/` applies the same multiple).
 *
 * **This is the only place the multiple is computed.** `EditorTextStyle` deliberately carries no
 * `lineHeight` of its own; the editor surface resolves its line height through this function at the
 * current density, and [EditorMetricsTest] unit-pins the ratio so FR-17 cannot drift with a
 * restyle.
 *
 * @param fontSizePx the font size in pixels (already scaled by the system font scale).
 * @return the line height in pixels, same unit as [fontSizePx].
 */
internal fun lineHeightFor(fontSizePx: Float): Float = fontSizePx * EditorLineHeightMultiplier
