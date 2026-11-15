package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/** Which of the pill's buttons accept input right now (FR-22, FR-32, EC-03). */
data class ActionRowEnabled(
    val outdent: Boolean,
    val indent: Boolean,
    val clear: Boolean,
)

/**
 * The enablement contract of the bottom pill (FR-22, FR-32, EC-03): Outdent and Indent are always
 * available — they round-trip the buffer unchanged when there is nothing to do — and Clear follows
 * the buffer (`EditorStateHolder.canClear`).
 *
 * Undo, Redo, Share and the ⋮ are **not** part of this contract: they live in the top bar
 * ([topBarEnabled]) since the Keep-style split.
 *
 * Every flag is read in the pill's own subcomposition, so a keystroke or a pure history change
 * invalidates the pill and not the editor.
 */
fun actionRowEnabled(canClear: Boolean): ActionRowEnabled =
    ActionRowEnabled(
        outdent = true,
        indent = true,
        clear = canClear,
    )

/**
 * Whether the pill adds the navigation-bar inset: **only while the IME is hidden**, because the
 * IME inset already covers that strip (EC-27 — never both, or the pill floats one navigation-bar
 * height too high).
 */
internal fun appliesNavigationBarInset(imeVisible: Boolean): Boolean = !imeVisible

/**
 * Google-Keep-style bottom bar: **one floating pill** holding the buffer controls, drawn the way
 * the tracked Keep editor draws its toolbar (FR-22):
 *
 * ```
 *                       ╭─────────────────────────╮
 *                       │  (Outdent) (Indent) (Clear)  │
 *                       ╰─────────────────────────╯
 * ```
 *
 * The pill is centred, wraps its three 48dp targets and rides above the open IME. Its surface is
 * `surfaceContainerLow` (the white sheet tone) with a small elevation — the canvas is the tinted
 * editor tone, so the pill reads as a raised control layer exactly as it does in Keep. The controls
 * themselves are bare glyphs ([GlyphActionButton]), not the circles the previous chrome drew.
 *
 * ## Layout and insets (EC-27)
 *
 * The band floats above the open IME and rests above the navigation bar with the keyboard closed.
 * `imePadding()` is the **only** vertical mover: the padding it applies *is* the band's travel, so
 * the composer must not add a second, fraction-driven `translationY` on top of it. That was the bug
 * this row used to have — the row ended one row-height higher than the top of the keyboard with a
 * dead band above it. One owner, one travel.
 *
 * The navigation-bar inset is applied only while the IME is hidden, because the IME inset already
 * covers that strip — applying both unconditionally is exactly the double padding EC-27 forbids.
 * Horizontal safe-drawing insets are the host's (it passes them through [modifier]); the band owns
 * only its bottom edge.
 *
 * ## Width budget
 *
 * Three 48dp targets plus the pill's own padding must fit the narrowest supported window; at
 * [PillHorizontalPadding] + [GlyphSpacing] the pill is ~176dp wide, so it fits a 320dp window with
 * room to spare (the previous six-button row was the tight case).
 *
 * ## Motion (FR-35, NFR-05)
 *
 * The band carries no IME-driven fade or offset of its own: its reveal *is* the inset animation, and
 * it stays fully opaque at every point of it. The per-button enabled state is the band's only
 * animation, driven by [MotionSpec] — with `motion.collapsed` (animator scale 0) it resolves to
 * `snap()` in [GlyphActionButton] (NFR-05).
 *
 * ## Accessibility (NFR-04)
 *
 * Every icon-only control carries a string-resource `contentDescription` (`outdent` / `indent` /
 * `clear_editor`) and its 48dp hit target comes from [GlyphActionButton]. There is no second menu
 * here, no Copy/Paste (the system selection toolbar owns those) and no hard-coded display copy.
 *
 * @param canClear drives Clear's enabled state (`EditorStateHolder.canClear`).
 * @param onOutdent removes one indent unit from the affected lines (no-op on an empty buffer).
 * @param onIndent adds one indent unit to the affected lines.
 * @param onClear empties the buffer through the shared clear path.
 * @param modifier the host's slot (the shell hands it the horizontal safe-drawing inset).
 * @param motion the resolved chrome motion; injectable for previews/tests, production reads the
 *   system animator scale once per composition.
 */
@Composable
fun EditorActionRow(
    canClear: Boolean,
    onOutdent: () -> Unit,
    onIndent: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
) {
    val enabled = actionRowEnabled(canClear = canClear)

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val navigationInset =
        if (appliesNavigationBarInset(imeVisible)) Modifier.navigationBarsPadding() else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .then(navigationInset)
            .padding(vertical = BandVerticalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = PillShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = PillElevation,
            modifier = Modifier.height(PillHeight),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = PillHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(GlyphSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphActionButton(
                    labelRes = R.string.outdent,
                    imageVector = Icons.AutoMirrored.Filled.FormatIndentDecrease,
                    enabled = enabled.outdent,
                    onClick = onOutdent,
                    motion = motion,
                )
                GlyphActionButton(
                    labelRes = R.string.indent,
                    imageVector = Icons.AutoMirrored.Filled.FormatIndentIncrease,
                    enabled = enabled.indent,
                    onClick = onIndent,
                    motion = motion,
                )
                GlyphActionButton(
                    labelRes = R.string.clear_editor,
                    imageVector = Icons.Filled.Close,
                    enabled = enabled.clear,
                    onClick = onClear,
                    motion = motion,
                )
            }
        }
    }
}

// Pill metrics — Keep's floating toolbar: 64dp tall, fully rounded ends, glyphs a few dp apart.
private val PillHeight = 64.dp
private val PillHorizontalPadding = 8.dp
private val GlyphSpacing = 4.dp
private val PillShape = CircleShape
private val PillElevation = 3.dp

/** Breathing room above the navigation bar (and above the IME when it is open). */
private val BandVerticalPadding = 12.dp

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** A populated buffer: Clear enabled, which is the state that differs visually. */
@Preview(name = "Action pill — light", showBackground = true)
@Preview(name = "Action pill — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Action pill — expanded", widthDp = 840, showBackground = true)
@Composable
private fun EditorActionRowPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            EditorActionRow(
                canClear = true,
                onOutdent = {},
                onIndent = {},
                onClear = {},
            )
        }
    }
}

/** The empty-buffer pill: Clear is disabled, Outdent and Indent stay available. */
@Preview(name = "Action pill — empty", widthDp = 320, showBackground = true)
@Composable
private fun EditorActionRowEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            EditorActionRow(
                canClear = false,
                onOutdent = {},
                onIndent = {},
                onClear = {},
            )
        }
    }
}
