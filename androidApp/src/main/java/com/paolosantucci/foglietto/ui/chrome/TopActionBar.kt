package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/**
 * The top bar's action cluster: **Undo · Redo · Share · ⋮**, right-aligned, each in its own
 * rounded-square tonal tile (FR-20, FR-33):
 *
 * ```
 *                                           (↶)(↷)(Share)(⋮)
 * ```
 *
 * ## What lives here, and why
 *
 * This is the product owner's directive: the four chrome actions sit in the upper-right corner,
 * every one of them in a rounded square, over a title-less bar — Google Keep's note-editor toolbar.
 * The editing actions of the bottom pill (Copy, Paste, Outdent, Indent, Clear) stay there, which is
 * where the reference keeps them; nothing is duplicated between the two bands.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Components 1 "App shell" (mobile adaptation): title-less bar, minimal chrome, the editor is
 *    the screen — this row is the bar's whole content.
 *  - §Accessibility: every control is icon-only, so each carries a string-resource content
 *    description (Undo, Redo, Share, Open menu) and a ≥48dp target — both owned by
 *    [TonalActionButton], never restated here.
 *
 * The rounded-square tile is the one deliberate departure from the canon's circular chrome: Keep's
 * Material 3 toolbar draws each action in its own rounded square. The *metrics* live in
 * [TonalActionButton], so the shape cannot drift per call site.
 *
 * ## The ⋮ owns the anchored menu
 *
 * The overflow tile is the anchor of the ⋮ menu: [menuOpen], [onMenuDismiss] and [menuContent] let
 * the host own the routing state while this bar owns the anchor `Box` the Material 3 [DropdownMenu]
 * measures itself against (outside-tap and back dismissal are the component's). The menu content is
 * composed only while open, which is what makes its per-open refresh a true per-open signal. The
 * anchor sits at the window's top-right, so the popup resolves *below* it — the platform's default
 * placement — with the same [PopoverGap] between tile and card.
 *
 * ## Width budget
 *
 * Four 48dp targets (192dp) + three [TileSpacing] gaps (24dp) + the 8dp [EndPadding] = **224dp**,
 * which fits every supported window width — including the 320dp narrowest phone — with room to
 * spare, because the bar carries no title to compete for the space.
 *
 * @param canUndo drives Undo's enabled state (`EditorStateHolder.canUndo`, snapshot-backed).
 * @param canRedo drives Redo's enabled state (`EditorStateHolder.canRedo`, snapshot-backed).
 * @param canShare drives Share's enabled state; the caller passes the shared predicate
 *   (`EditorStateHolder.shareEnabled`, FR-08/EC-30) and re-reads it at click time.
 * @param onUndo restores the previous snapshot (no-op with an empty stack).
 * @param onRedo restores the snapshot parked by Undo (no-op with an empty stack).
 * @param onShare shares the current buffer.
 * @param menuOpen whether the ⋮ menu is showing; the host owns this state.
 * @param onOpenMenu opens the ⋮ menu (the anchor tap).
 * @param onMenuDismiss closes it — outside tap, back gesture.
 * @param modifier the host's slot, applied once to the row.
 * @param motion the resolved chrome motion; injectable for previews/tests, production reads the
 *   system animator scale once per composition.
 * @param menuContent the ⋮ menu's body, supplied by the host so routing stays there. Composed only
 *   while [menuOpen].
 */
@Composable
fun TopActionBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canShare: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShare: () -> Unit,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onMenuDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
    menuContent: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.padding(end = EndPadding),
        horizontalArrangement = Arrangement.spacedBy(TileSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TonalActionButton(
            labelRes = R.string.undo,
            imageVector = Icons.AutoMirrored.Filled.Undo,
            enabled = canUndo,
            onClick = onUndo,
            motion = motion,
        )
        TonalActionButton(
            labelRes = R.string.redo,
            imageVector = Icons.AutoMirrored.Filled.Redo,
            enabled = canRedo,
            onClick = onRedo,
            motion = motion,
        )
        TonalActionButton(
            labelRes = R.string.share,
            imageVector = Icons.Filled.Share,
            enabled = canShare,
            onClick = onShare,
            motion = motion,
        )
        Box {
            TonalActionButton(
                labelRes = R.string.open_menu,
                imageVector = Icons.Filled.MoreVert,
                // The ⋮ is chrome, not a buffer action: it is always available (EC-03).
                enabled = true,
                onClick = onOpenMenu,
                motion = motion,
            )
            // Anchored to the Box above, which is exactly the ⋮ tile: Material 3 measures the
            // popup against it, opens it downward (default placement) and owns outside-tap/back
            // dismissal.
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

/** Keep's chrome sits 8dp clear of the window edge. */
private val EndPadding = 8.dp

/** Keep's toolbar gap between two rounded squares: 8dp of the 48dp targets' slack. */
private val TileSpacing = 8.dp

// ⋮ menu metrics — Google Keep's overflow card: wide, softly rounded, one shadow step up.
private val PopoverShape = RoundedCornerShape(28.dp)
private val PopoverWidth = 320.dp
private val PopoverElevation = 3.dp

/** The gap between the ⋮ tile and the card. */
private val PopoverGap = 8.dp

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────

/** A populated buffer with history: every tile enabled, which is the state that differs visually. */
@Preview(name = "Top action bar — light", showBackground = true)
@Preview(name = "Top action bar — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TopActionBarPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            TopActionBar(
                canUndo = true,
                canRedo = true,
                canShare = true,
                onUndo = {},
                onRedo = {},
                onShare = {},
                menuOpen = false,
                onOpenMenu = {},
                onMenuDismiss = {},
            )
        }
    }
}

/** Keep's empty/whitespace-only state: Undo, Redo and Share are dimmed; the ⋮ stays available. */
@Preview(name = "Top action bar — empty", showBackground = true)
@Preview(name = "Top action bar — narrow", widthDp = 320, showBackground = true)
@Composable
private fun TopActionBarEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            TopActionBar(
                canUndo = false,
                canRedo = false,
                canShare = false,
                onUndo = {},
                onRedo = {},
                onShare = {},
                menuOpen = false,
                onOpenMenu = {},
                onMenuDismiss = {},
            )
        }
    }
}
