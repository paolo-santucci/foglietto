package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
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
    val undo: Boolean,
    val redo: Boolean,
) {

    /** The ⋮ is chrome, not a buffer action: it is always available (EC-03). */
    val menu: Boolean get() = true
}

/**
 * The enablement contract of the bottom bar (FR-22, FR-32, EC-03).
 *
 * Copy, Paste, Outdent and Indent are always available:
 *  - Outdent and Indent round-trip the buffer unchanged when there is nothing to do;
 *  - Copy copies the whole buffer whether or not it holds anything (iOS parity — "the share
 *    surface, not copy, is gated");
 *  - Paste is enabled unconditionally; a clipboard with nothing usable in it is a silent no-op.
 *
 * Clear follows the buffer (`EditorStateHolder.canClear`), Undo/Redo follow their own stacks
 * independently, and the ⋮ never depends on the buffer.
 *
 * Every flag is read in the bar's own subcomposition, so a keystroke or a pure history change
 * invalidates the bar and not the editor.
 */
fun actionRowEnabled(canClear: Boolean, canUndo: Boolean, canRedo: Boolean): ActionRowEnabled =
    ActionRowEnabled(
        copy = true,
        paste = true,
        outdent = true,
        indent = true,
        clear = canClear,
        undo = canUndo,
        redo = canRedo,
    )

/**
 * Whether the bar adds the navigation-bar inset: **only while the IME is hidden**, because the IME
 * inset already covers that strip (EC-27 — never both, or the bar floats one navigation-bar height
 * too high).
 */
internal fun appliesNavigationBarInset(imeVisible: Boolean): Boolean = !imeVisible

/**
 * Google-Keep-style bottom bar: **one floating pill** holding every editor action, split into an
 * editing cluster on the left and a history cluster on the right:
 *
 * ```
 *   ╭───────────────────────────────────────────────────────╮
 *   │ (Copy)(Paste)(Outdent)(Indent)(Clear)   (Undo)(Redo)(⋮) │
 *   ╰───────────────────────────────────────────────────────╯
 * ```
 *
 * All eight controls are always visible — the product owner asked for Copy, Paste and Clear to
 * stay in the bar, so there is no keyboard-conditional reveal (the iOS *order* is kept, iOS's
 * IME-gated *visibility* is not). The spacer between the two clusters absorbs the slack.
 *
 * The pill is `surfaceContainerLow` (the white sheet tone) with a small elevation: the canvas is the
 * tinted editor tone, so the pill reads as a raised control layer exactly as it does in Keep. The
 * controls themselves are bare glyphs ([GlyphActionButton]) over that one shared surface.
 *
 * ## The ⋮ owns the anchored menu
 *
 * The overflow control is the anchor of the ⋮ menu: [menuOpen], [onMenuDismiss] and [menuContent]
 * let the host own the routing state while this row owns the anchor `Box` the Material 3
 * [DropdownMenu] measures itself against (outside-tap and back dismissal are the component's). The
 * menu content is composed only while open, which is what makes its per-open refresh a true
 * per-open signal. Because the ⋮ now sits at the bottom edge, the popup resolves *above* the bar —
 * the placement the platform picks for an anchor near the window's bottom, with the same
 * [PopoverGap] between glyph and card.
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
 * ## Width budget — read before adding a ninth control
 *
 * Eight 48dp targets are 384dp, and the band's [BandHorizontalPadding] adds 16dp: **400dp of
 * content**. On the 411dp reference device the pill has 11dp of slack and the weight spacer
 * collapses to nothing. Below ~400dp the row no longer fits: at 360dp it is 40dp short and at
 * 320dp 80dp short, and a `Row` measures its non-weighted children against the *remaining* width,
 * so the trailing controls (⋮ first, then Redo) are laid out at a few dp and clip rather than
 * overflowing the surface. That limitation is reported to the caller with the two available fixes
 * (a scrolling band, or reflowing the history cluster elsewhere at compact widths); it is a product
 * decision, not a local padding tweak — the 48dp floor is not negotiable.
 *
 * ## Accessibility (NFR-04)
 *
 * Every icon-only control carries a string-resource `contentDescription` (`copy` / `paste` /
 * `outdent` / `indent` / `clear_editor` / `undo` / `redo` / `open_menu`) and its 48dp hit target
 * comes from [GlyphActionButton]; the menu rows are ≥56dp. No hard-coded display copy.
 *
 * @param canClear drives Clear's enabled state (`EditorStateHolder.canClear`).
 * @param canUndo drives Undo's enabled state (`EditorStateHolder.canUndo`, snapshot-backed).
 * @param canRedo drives Redo's enabled state (`EditorStateHolder.canRedo`, snapshot-backed).
 * @param onCopy copies the whole buffer through the host's clipboard gateway.
 * @param onPaste inserts the clipboard text at the caret through the host's clipboard gateway.
 * @param onOutdent removes one indent unit from the affected lines (no-op on an empty buffer).
 * @param onIndent adds one indent unit to the affected lines.
 * @param onClear empties the buffer through the shared clear path.
 * @param onUndo restores the previous snapshot (no-op with an empty stack).
 * @param onRedo restores the snapshot parked by Undo (no-op with an empty stack).
 * @param menuOpen whether the ⋮ menu is showing; the host owns this state.
 * @param onOpenMenu opens the ⋮ menu (the anchor tap).
 * @param onMenuDismiss closes it — outside tap, back gesture.
 * @param modifier the host's slot (the shell hands it the horizontal safe-drawing inset).
 * @param motion the resolved chrome motion; injectable for previews/tests, production reads the
 *   system animator scale once per composition.
 * @param menuContent the ⋮ menu's body, supplied by the host so routing stays there. Composed only
 *   while [menuOpen].
 */
@Composable
fun EditorActionRow(
    canClear: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onOutdent: () -> Unit,
    onIndent: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onMenuDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
    menuContent: @Composable ColumnScope.() -> Unit = {},
) {
    val enabled = actionRowEnabled(canClear = canClear, canUndo = canUndo, canRedo = canRedo)

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
            // No horizontal padding of its own: the band's BandHorizontalPadding is the only 8dp
            // inset, because eight 48dp targets plus two pill paddings would no longer fit the
            // 411dp window (see "Width budget"). The 48dp targets stay flush; their 24dp glyphs
            // supply the optical margin, and the stadium's clip only clips drawing, never the
            // touch target.
            Row(
                modifier = Modifier
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(ButtonSpacing),
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

                // The slack between the clusters: zero on a narrow window, which is where the row
                // starts to run out of width (see "Width budget").
                Spacer(modifier = Modifier.weight(1f))

                GlyphActionButton(
                    labelRes = R.string.undo,
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    enabled = enabled.undo,
                    onClick = onUndo,
                    motion = motion,
                )
                GlyphActionButton(
                    labelRes = R.string.redo,
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    enabled = enabled.redo,
                    onClick = onRedo,
                    motion = motion,
                )
                Box {
                    GlyphActionButton(
                        labelRes = R.string.open_menu,
                        imageVector = Icons.Filled.MoreVert,
                        enabled = enabled.menu,
                        onClick = onOpenMenu,
                        motion = motion,
                    )
                    // Anchored to the Box above, which is exactly the ⋮ target: Material 3 measures
                    // the popup against it and owns outside-tap/back dismissal.
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = onMenuDismiss,
                        modifier = Modifier.widthIn(max = PopoverWidth),
                        offset = DpOffset(x = 0.dp, y = PopoverGap),
                        shape = PopoverShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                        shadowElevation = PopoverElevation,
                        content = menuContent,
                    )
                }
            }
        }
    }
}

// Pill metrics — Keep's floating toolbar: 64dp tall, fully rounded ends, glyphs a few dp apart.
private val PillHeight = 64.dp

/**
 * No gap between the 48dp targets: each centres a 24dp glyph, so consecutive glyphs read 24dp apart
 * on their own — the same optical spacing the top bar's rounded squares have.
 */
private val ButtonSpacing = 0.dp

/** Keep's chrome sits 8dp clear of the window edge, horizontally. */
private val BandHorizontalPadding = 8.dp

private val PillShape = CircleShape
private val PillElevation = 3.dp

/** Breathing room above the navigation bar (and above the IME when it is open). */
private val BandVerticalPadding = 12.dp

// ⋮ menu metrics — Google Keep's overflow card: wide, softly rounded, one shadow step up.
private val PopoverShape = RoundedCornerShape(28.dp)
private val PopoverWidth = 320.dp
private val PopoverElevation = 3.dp

/** The gap between the ⋮ glyph and the card. */
private val PopoverGap = 8.dp

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
            PreviewActionRow(canClear = true, canUndo = true, canRedo = true)
        }
    }
}

/** The empty-buffer bar: Clear, Undo and Redo are disabled; Copy and Paste stay available. */
@Preview(name = "Action bar — empty", widthDp = 411, showBackground = true)
@Composable
private fun EditorActionRowEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PreviewActionRow(canClear = false, canUndo = false, canRedo = false)
        }
    }
}

/** The narrow case the width budget names: 320dp, where the trailing cluster runs out of room. */
@Preview(name = "Action bar — narrow", widthDp = 320, showBackground = true)
@Composable
private fun EditorActionRowNarrowPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PreviewActionRow(canClear = true, canUndo = true, canRedo = true)
        }
    }
}

@Composable
private fun PreviewActionRow(canClear: Boolean, canUndo: Boolean, canRedo: Boolean) {
    EditorActionRow(
        canClear = canClear,
        canUndo = canUndo,
        canRedo = canRedo,
        onCopy = {},
        onPaste = {},
        onOutdent = {},
        onIndent = {},
        onClear = {},
        onUndo = {},
        onRedo = {},
        menuOpen = false,
        onOpenMenu = {},
        onMenuDismiss = {},
    )
}
