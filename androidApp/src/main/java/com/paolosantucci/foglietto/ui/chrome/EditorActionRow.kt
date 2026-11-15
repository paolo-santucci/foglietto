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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
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

/** Which of the bottom bar's buttons accept input right now (FR-22, FR-32, EC-03). */
data class ActionRowEnabled(
    /** Copy is never gated: iOS copies even an empty buffer — the share surface is what is gated. */
    val copy: Boolean,
    /** Paste is never gated either; an empty clipboard is a silent no-op at the holder (EC-03). */
    val paste: Boolean,
    val outdent: Boolean,
    val indent: Boolean,
    val clear: Boolean,
)

/**
 * The enablement contract of the bottom bar (FR-22, FR-32, EC-03).
 *
 * Copy, Paste, Outdent and Indent are always available:
 *  - Outdent and Indent round-trip the buffer unchanged when there is nothing to do;
 *  - Copy copies the whole buffer whether or not it holds anything (iOS parity — "the share
 *    surface, not copy, is gated");
 *  - Paste is enabled unconditionally; a clipboard with nothing usable in it is a silent no-op.
 *
 * Clear follows the buffer (`EditorStateHolder.canClear`). Undo, Redo and the ⋮ are not this bar's
 * control any more: they are the top bar's tiles ([TopActionBar]), enabled from `canUndo`,
 * `canRedo` and `true` respectively.
 *
 * Every flag is read in the bar's own subcomposition, so a keystroke invalidates the bar and not
 * the editor.
 */
fun actionRowEnabled(canClear: Boolean): ActionRowEnabled =
    ActionRowEnabled(
        copy = true,
        paste = true,
        outdent = true,
        indent = true,
        clear = canClear,
    )

/**
 * Whether the bar adds the navigation-bar inset: **only while the IME is hidden**, because the IME
 * inset already covers that strip (EC-27 — never both, or the bar floats one navigation-bar height
 * too high).
 */
internal fun appliesNavigationBarInset(imeVisible: Boolean): Boolean = !imeVisible

/**
 * Google-Keep-style bottom bar: **one floating pill** holding the five editing actions, centred:
 *
 * ```
 *   ╭─────────────────────────────────────────╮
 *   │      (Copy)(Paste)(Outdent)(Indent)(Clear) │
 *   ╰─────────────────────────────────────────╯
 * ```
 *
 * Undo, Redo and the ⋮ are the top bar's tiles ([TopActionBar]), so this row holds no history and
 * no menu: five 48dp targets centred in the pill's full width, no weight spacer, nothing to
 * distribute. All five are always visible — the product owner asked for Copy, Paste and Clear to
 * stay in the bar, so there is no keyboard-conditional reveal.
 *
 * The pill is `surfaceContainerLow` (the white sheet tone) with a small elevation: the canvas is the
 * tinted editor tone, so the pill reads as a raised control layer exactly as it does in Keep. The
 * controls themselves are bare glyphs ([GlyphActionButton]) over that one shared surface.
 *
 * ## Layout and insets (EC-27)
 *
 * The band floats above the open IME and rests above the navigation bar with the keyboard closed.
 * `imePadding()` is the **only** vertical mover: the padding it applies *is* the band's travel, so
 * the composer must not add a second, fraction-driven `translationY` on top of it.
 *
 * The navigation-bar inset is applied only while the IME is hidden, because the IME inset already
 * covers that strip — applying both unconditionally is exactly the double padding EC-27 forbids.
 * Horizontal safe-drawing insets are the host's (it passes them through [modifier]); the band owns
 * only its bottom edge.
 *
 * ## Width budget — read before adding a sixth control
 *
 * Five 48dp targets are 240dp, and the band's [BandHorizontalPadding] adds 16dp: **256dp of
 * content**. The narrowest window this app supports is 320dp, so the five controls sit centred with
 * 64dp to spare at the very worst width. A sixth target would need 304dp — still inside 320dp, but
 * only just (16dp total slack) — so re-read this paragraph before adding one, and note that a
 * `Row` measures its children against the *remaining* width: the overflow would clip the trailing
 * glyph and destroy its hit target rather than paint outside the pill.
 *
 * ## Accessibility (NFR-04)
 *
 * Every icon-only control carries a string-resource `contentDescription` (`copy` / `paste` /
 * `outdent` / `indent` / `clear_editor`) and its 48dp hit target comes from [GlyphActionButton]. No
 * hard-coded display copy.
 *
 * @param canClear drives Clear's enabled state (`EditorStateHolder.canClear`).
 * @param onCopy copies the whole buffer through the host's clipboard gateway.
 * @param onPaste inserts the clipboard text at the caret through the host's clipboard gateway.
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
    onCopy: () -> Unit,
    onPaste: () -> Unit,
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
            .padding(horizontal = BandHorizontalPadding, vertical = BandVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = PillShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = PillElevation,
            modifier = Modifier
                .fillMaxWidth()
                .height(PillHeight),
        ) {
            // Centred, not distributed: the five targets are the pill's whole content, so they sit
            // together in the middle and the pill keeps the band's full width. No horizontal
            // padding of its own — the band's BandHorizontalPadding is the only 8dp inset, and the
            // stadium's clip only clips drawing, never a 48dp touch target.
            Row(
                modifier = Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphActionButton(
                    labelRes = R.string.copy,
                    imageVector = Icons.Filled.ContentCopy,
                    enabled = enabled.copy,
                    onClick = onCopy,
                    motion = motion,
                )
                GlyphActionButton(
                    labelRes = R.string.paste,
                    imageVector = Icons.Filled.ContentPaste,
                    enabled = enabled.paste,
                    onClick = onPaste,
                    motion = motion,
                )
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

/** Keep's chrome sits 8dp clear of the window edge, horizontally. */
private val BandHorizontalPadding = 8.dp

private val PillShape = CircleShape
private val PillElevation = 3.dp

/** Breathing room above the navigation bar (and above the IME when it is open). */
private val BandVerticalPadding = 12.dp

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** A populated buffer: Clear enabled, which is the state that differs visually. */
@Preview(name = "Action bar — light", showBackground = true)
@Preview(name = "Action bar — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Action bar — reference device", widthDp = 411, showBackground = true)
@Composable
private fun EditorActionRowPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PreviewActionRow(canClear = true)
        }
    }
}

/** The empty-buffer bar: Clear is disabled; Copy and Paste stay available. */
@Preview(name = "Action bar — empty", widthDp = 411, showBackground = true)
@Composable
private fun EditorActionRowEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PreviewActionRow(canClear = false)
        }
    }
}

/** The narrowest supported case: 320dp, where the five targets still fit with 64dp to spare. */
@Preview(name = "Action bar — narrow", widthDp = 320, showBackground = true)
@Composable
private fun EditorActionRowNarrowPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PreviewActionRow(canClear = true)
        }
    }
}

@Composable
private fun PreviewActionRow(canClear: Boolean) {
    EditorActionRow(
        canClear = canClear,
        onCopy = {},
        onPaste = {},
        onOutdent = {},
        onIndent = {},
        onClear = {},
    )
}
