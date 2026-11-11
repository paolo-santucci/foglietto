package com.paolosantucci.foglietto.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Colour tokens for the Android front-end.
 *
 * Every literal here is traceable to `.claude/docs/canon/ui-design-bible.md` (§Colour & branding
 * tokens, §Scheme matrix). The canon states there is **no bespoke palette** — the app adopts the
 * platform theme — and that the only hard-coded colours in the product are the two branding
 * colours and the two theme-swatch fills.
 *
 * ## The neutral chrome palette
 *
 * The Material 3 *baseline* scheme paints `secondaryContainer` with the lavender seed tone
 * (`#E9DDFF`-family), which turned the editor's circular tonal buttons purple. The app adopts a
 * neutral blue-grey chrome palette instead — the Google-Keep-like surface/ink pair — and the
 * scheme in `Theme.kt` is driven from these tokens only. No seed colour, no dynamic colour, no
 * purple in either mode.
 *
 * Every pair that carries text was contrast-checked: `onSecondaryContainer` on
 * `secondaryContainer` measures 8.5:1 (light) / 7.8:1 (dark), and `onSurface` on `surfaceVariant`
 * 14.8:1 (light) / 7.8:1 (dark) — all above the 4.5:1 floor of WCAG 2.1 AA for body text.
 */

// Branding colours — canon §Colour & branding tokens (`metainfo.xml:42-45`).
// Identity/launch colours ONLY (splash + adaptive icon, TASK-12). The canon is explicit:
// "Branding colours are identity/launch colours, not in-editor UI colours. Do not tint the
// editing surface." They therefore never feed ColorScheme.primary below.
internal val BrandYellow = Color(0xFFF6D32D) // GNOME "Yellow 3" — branding primary (light)
internal val BrandBrown = Color(0xFF873000) // GNOME "Brown 5" — branding primary (dark)

// Theme-swatch literal fills — the only other hard-coded colours (`style.css:55-63`).
internal val SwatchLight = Color(0xFFFFFFFF)
internal val SwatchDark = Color(0xFF202020)

// `--view-bg-color` — the editor canvas. Canon §Scheme matrix: light ≈ `#fff`, dark ≈ `#202020`.
// Doubles as the chrome palette's `surface`/`background` row: the editor and the bars share one
// canvas in both modes, so there is exactly one token per mode.
internal val EditorSurfaceLight = Color(0xFFFFFFFF)
internal val EditorSurfaceDark = Color(0xFF202020)

// Editor ink — canon §Scheme matrix says "platform text colour". Kept verbatim; the live scheme
// now takes the neutral chrome palette's [OnSurfaceLight]/[OnSurfaceDark] (the Keep-style ink),
// so these two record the canon's editor-only value rather than restating it in Theme.kt.
internal val EditorForegroundLight = Color(0xFF1C1B1F)
internal val EditorForegroundDark = Color(0xFFE6E1E5)

// `--border-color` hairline ring. Kept verbatim; the scheme's `outlineVariant` is now the neutral
// palette's [OutlineVariantLight]/[OutlineVariantDark] (the sheet divider and row edge colour).
internal val HairlineLight = Color(0xFFCAC4D0)
internal val HairlineDark = Color(0xFF49454F)

// ── Neutral chrome palette (light) ───────────────────────────────────────────

/** Primary ink on the canvas — Keep's near-black. */
internal val OnSurfaceLight = Color(0xFF1F1F1F)

/** The circular tonal button fill (Share, Undo, Redo, Outdent, Indent, Clear, ⋮). */
internal val SecondaryContainerLight = Color(0xFFE9EEF6)

/** The icon ink inside [SecondaryContainerLight]. */
internal val OnSecondaryContainerLight = Color(0xFF3C4043)

/** The accent: caret, links, selection — Google blue, never the branding yellow. */
internal val PrimaryLight = Color(0xFF1A73E8)
internal val OnPrimaryLight = Color(0xFFFFFFFF)

/** Secondary fill: the sheet's trailing band and any low-emphasis chip. */
internal val SurfaceVariantLight = Color(0xFFF0F3F8)

/** Secondary ink: timestamps, the empty state, supporting lines. */
internal val OnSurfaceVariantLight = Color(0xFF444746)

/** Hairline: dividers and row edges. */
internal val OutlineVariantLight = Color(0xFFDADCE0)

/** The modal bottom sheet's own surface. */
internal val SurfaceContainerLowLight = Color(0xFFFFFFFF)

/** The raised band inside a sheet (drag handle area, pressed rows). */
internal val SurfaceContainerHighLight = Color(0xFFF0F3F8)

// ── Neutral chrome palette (dark) ────────────────────────────────────────────

internal val OnSurfaceDark = Color(0xFFE3E3E3)
internal val SecondaryContainerDark = Color(0xFF3C4043)
internal val OnSecondaryContainerDark = Color(0xFFE3E3E3)
internal val PrimaryDark = Color(0xFFA8C7FA)
internal val OnPrimaryDark = Color(0xFF062E6F)
internal val SurfaceVariantDark = Color(0xFF3C4043)
internal val OnSurfaceVariantDark = Color(0xFFC4C7C5)
internal val OutlineVariantDark = Color(0xFF5F6368)
internal val SurfaceContainerLowDark = Color(0xFF2A2B2E)
internal val SurfaceContainerHighDark = Color(0xFF333538)
