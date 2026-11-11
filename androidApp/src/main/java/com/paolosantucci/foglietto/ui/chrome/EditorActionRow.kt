package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
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

/** Which of the row's buttons accept input right now (FR-22, FR-32, EC-03). */
data class ActionRowEnabled(
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
 * The enablement contract of the row (FR-22, FR-32, EC-03): Outdent and Indent are always
 * available — they round-trip the buffer unchanged when there is nothing to do — Clear follows the
 * buffer (`EditorStateHolder.canClear`) and Undo/Redo forward the snapshot-backed history flags.
 *
 * Every flag is read in the row's own subcomposition, so a keystroke or a pure history change
 * invalidates the row and not the editor.
 */
fun actionRowEnabled(canClear: Boolean, canUndo: Boolean, canRedo: Boolean): ActionRowEnabled =
    ActionRowEnabled(
        outdent = true,
        indent = true,
        clear = canClear,
        undo = canUndo,
        redo = canRedo,
    )

/**
 * Whether the row adds the navigation-bar inset: **only while the IME is hidden**, because the
 * IME inset already covers that strip (EC-27 — never both, or the row floats one navigation-bar
 * height too high).
 */
internal fun appliesNavigationBarInset(imeVisible: Boolean): Boolean = !imeVisible

/**
 * Google-Keep-style bottom action row: **two clusters** of circular tonal icon buttons in one
 * `Row`, separated by a weight spacer (FR-22).
 *
 * ```
 * (Outdent) (Indent) (Clear)        ……        (Undo) (Redo) (⋮)
 * ```
 *
 * The left cluster edits the buffer's line structure, the right cluster is history plus the ⋮
 * menu — the same split Keep uses, and the reason Undo/Redo no longer sit in the top bar: they act
 * on the text, so they belong to the text's band.
 *
 * ## Layout and insets (EC-27)
 *
 * The row floats above the open IME and rests above the navigation bar with the keyboard closed.
 * `imePadding()` is the **only** vertical mover: the padding it applies *is* the row's travel, so
 * the composer must not add a second, fraction-driven `translationY` on top of it. That is the bug
 * this row used to have — the row ended one row-height higher than the top of the keyboard with a
 * dead band above it. One owner, one travel.
 *
 * The navigation-bar inset is applied only while the IME is hidden, because the IME inset already
 * covers that strip — applying both unconditionally is exactly the double padding EC-27 forbids.
 * Horizontal safe-drawing insets are the host's (it passes them through [modifier]); the row owns
 * only its bottom band.
 *
 * ## Width budget
 *
 * Six 48dp touch targets plus the two cluster spacings must fit the narrowest supported window, so
 * the row keeps its own horizontal padding at [HorizontalPadding] and puts **no** gap between
 * buttons ([ButtonSpacing]): the 40dp visual circle inside each 48dp target already leaves an 8dp
 * optical gap, which is what lets the whole row fit a 320dp-wide window without clipping.
 *
 * ## Motion (FR-35, NFR-05)
 *
 * The row carries no IME-driven fade or offset of its own: its reveal *is* the inset animation,
 * and it stays fully opaque at every point of it (a row that rests on the navigation bar when the
 * keyboard is closed must never fade out to get there, FR-22). The per-button enabled state is the
 * row's only animation, driven by [MotionSpec] — with `motion.collapsed` (animator scale 0) it
 * resolves to `snap()` in [TonalActionButton] (NFR-05).
 *
 * ## Accessibility (NFR-04)
 *
 * Every icon-only control carries a string-resource `contentDescription` (`outdent` / `indent` /
 * `clear_editor` / `undo` / `redo` / `open_menu`), and its 48dp hit target comes from
 * `FilledTonalIconButton`'s own `minimumInteractiveComponentSize()` — see [TonalActionButton].
 * There is no second menu here, no Copy/Paste (the system selection toolbar owns those) and no
 * hard-coded display copy.
 *
 * @param canClear drives Clear's enabled state (`EditorStateHolder.canClear`).
 * @param canUndo drives Undo's enabled state (`EditorStateHolder.canUndo`, snapshot-backed).
 * @param canRedo drives Redo's enabled state (`EditorStateHolder.canRedo`, snapshot-backed).
 * @param onOutdent removes one indent unit from the affected lines (no-op on an empty buffer).
 * @param onIndent adds one indent unit to the affected lines.
 * @param onClear empties the buffer through the shared clear path.
 * @param onUndo restores the previous snapshot (no-op with an empty stack).
 * @param onRedo restores the snapshot parked by Undo (no-op with an empty stack).
 * @param onOpenMenu opens the shell's ⋮ bottom sheet.
 * @param motion the resolved chrome motion; injectable for previews/tests, production reads the
 *   system animator scale once per composition.
 */
@Composable
fun EditorActionRow(
    canClear: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onOutdent: () -> Unit,
    onIndent: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
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
            .padding(horizontal = HorizontalPadding, vertical = VerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left cluster: the three line-structure actions.
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalActionButton(
                labelRes = R.string.outdent,
                imageVector = Icons.AutoMirrored.Filled.FormatIndentDecrease,
                enabled = enabled.outdent,
                onClick = onOutdent,
                motion = motion,
            )
            TonalActionButton(
                labelRes = R.string.indent,
                imageVector = Icons.AutoMirrored.Filled.FormatIndentIncrease,
                enabled = enabled.indent,
                onClick = onIndent,
                motion = motion,
            )
            TonalActionButton(
                labelRes = R.string.clear_editor,
                imageVector = Icons.Filled.Close,
                enabled = enabled.clear,
                onClick = onClear,
                motion = motion,
            )
        }

        // The slack lives between the clusters: on a wide window the two groups hug the edges.
        Spacer(modifier = Modifier.weight(1f))

        // Right cluster: history plus the ⋮ menu.
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalActionButton(
                labelRes = R.string.undo,
                imageVector = Icons.AutoMirrored.Filled.Undo,
                enabled = enabled.undo,
                onClick = onUndo,
                motion = motion,
            )
            TonalActionButton(
                labelRes = R.string.redo,
                imageVector = Icons.AutoMirrored.Filled.Redo,
                enabled = enabled.redo,
                onClick = onRedo,
                motion = motion,
            )
            TonalActionButton(
                labelRes = R.string.open_menu,
                imageVector = Icons.Filled.MoreVert,
                enabled = enabled.menu,
                onClick = onOpenMenu,
                motion = motion,
            )
        }
    }
}

// Row metrics.
private val HorizontalPadding = 12.dp
private val VerticalPadding = 8.dp

/**
 * No gap between buttons: each target is 48dp around a 40dp circle, so consecutive circles read
 * 8dp apart already — and six 48dp targets plus any added spacing would overflow a 320dp window.
 */
private val ButtonSpacing = 0.dp

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** A populated row: Clear/Undo/Redo enabled, which is the state that differs visually. */
@Preview(name = "Action row — light", showBackground = true)
@Preview(name = "Action row — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Action row — expanded", widthDp = 840, showBackground = true)
@Composable
private fun EditorActionRowPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            EditorActionRow(
                canClear = true,
                canUndo = true,
                canRedo = false,
                onOutdent = {},
                onIndent = {},
                onClear = {},
                onUndo = {},
                onRedo = {},
                onOpenMenu = {},
            )
        }
    }
}

/** The empty-buffer row: every buffer action is disabled, the ⋮ stays available. */
@Preview(name = "Action row — empty", widthDp = 320, showBackground = true)
@Composable
private fun EditorActionRowEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            EditorActionRow(
                canClear = false,
                canUndo = false,
                canRedo = false,
                onOutdent = {},
                onIndent = {},
                onClear = {},
                onUndo = {},
                onRedo = {},
                onOpenMenu = {},
            )
        }
    }
}
