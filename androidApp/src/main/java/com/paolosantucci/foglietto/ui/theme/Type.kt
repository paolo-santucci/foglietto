package com.paolosantucci.foglietto.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/*
 * Typography tokens for the Android front-end.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md` (§Typography).
 *
 * CANON GAP: the canon pins the editor's default family to **monospace** (GNOME system monospace,
 * "Roboto Mono on Android"), with a document-font alternative. FR-17 pins the **platform default**
 * font family instead, matching the tracked `iosApp/` rendering, and FR-19 forbids any font picker
 * or mono/document toggle on Android. FR-17 wins for this wave; the mono family + toggle have no
 * UI affordance to live in. Re-open with the product owner before adding one.
 *
 * CANON GAP: the canon keeps a 21-slot discrete size scale and an open question on whether the OS
 * font-scale stacks on top of it. FR-17 mandates "the system font-size setting" with no in-app
 * override, so only the platform font scale applies and the slot scale is not exposed this wave.
 */

/** Canon §Typography: line-height 1.4, "preserve ... verbatim". */
internal const val EditorLineHeightMultiplier = 1.4f

/** Canon §Typography default size slot (14pt → 14.sp, per the pt→sp mobile mapping). */
internal val EditorFontSize = 14.sp

/**
 * The editor typography slot: the system font family at the platform font scale.
 *
 * `FontFamily.Default` *is* the platform system family on Android (Roboto unless the user/OEM
 * overrides it) — no bundled font file, no family name literal, so the editor follows the system
 * font exactly as FR-17 requires.
 *
 * **No `lineHeight` here on purpose.** The canon's 1.4 multiple is computed in exactly one place —
 * `lineHeightFor` in `ui/editor/EditorMetrics.kt` — and the editor surface applies it at the
 * current density. Declaring it here as well would make two sources for the same number, which is
 * how the two drifted before.
 */
internal val EditorTextStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = EditorFontSize,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Material 3 typography (system family throughout); the editor slot is [EditorTextStyle]. */
internal val FogliettoTypography: Typography = Typography()
