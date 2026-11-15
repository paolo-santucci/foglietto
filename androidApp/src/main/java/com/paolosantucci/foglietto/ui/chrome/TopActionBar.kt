package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/**
 * Which top-bar controls accept input right now (FR-20, EC-03, FR-33).
 *
 * Share follows the shared share predicate, Undo/Redo follow their own stacks independently, and
 * the ⋮ is chrome — it never depends on the buffer.
 */
data class TopBarEnabled(
    val undo: Boolean,
    val redo: Boolean,
    val share: Boolean,
) {

    /** The ⋮ is chrome, not a buffer action: it is always available (EC-03). */
    val menu: Boolean get() = true
}

/**
 * The enablement contract of the top bar (FR-20, FR-32, EC-03, FR-33).
 *
 * Pure and total so it is pinnable from a plain JVM test; the composable below only reads it.
 */
fun topBarEnabled(canUndo: Boolean, canRedo: Boolean, canShare: Boolean): TopBarEnabled =
    TopBarEnabled(
        undo = canUndo,
        redo = canRedo,
        share = canShare,
    )

/**
 * Google-Keep-style top bar actions: **one right-aligned row of rounded-square tonal buttons**,
 * ordered the way the tracked Keep editor orders them (FR-20):
 *
 * ```
 *                                  (Undo) (Redo) (Share) (⋮)
 * ```
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Components 1 "App shell" (mobile adaptation): title-less bar, minimal chrome, the editor is
 *    the screen — this row is the bar's whole content.
 *  - §Accessibility: every icon-only control carries a string-resource content description
 *    ("Undo", "Redo", "Share", "Open menu") and a ≥48dp target — both owned by
 *    [TonalActionButton], never restated here.
 *  - §Iconography: `open-menu-symbolic` → `more_vert`; Undo/Redo stay the auto-mirrored Material
 *    arrows, so an RTL locale flips their direction with the text.
 *
 * The container shape is the one deliberate departure from the canon's circular chrome: Keep's
 * Material 3 Expressive toolbar draws each action in its own rounded square, which is what the
 * product owner asked this front-end to match. The *metrics* live in one place
 * ([TonalActionButton]) so the shape cannot drift per call site.
 *
 * Enablement is read by the caller (the shell reads it inside the bar's own subcomposition), so a
 * keystroke recomposes the bar and not the editor (FR-20).
 *
 * @param canUndo drives Undo's enabled state (`EditorStateHolder.canUndo`, snapshot-backed).
 * @param canRedo drives Redo's enabled state (`EditorStateHolder.canRedo`, snapshot-backed).
 * @param canShare drives Share's enabled state (`EditorStateHolder.shareEnabled`, the shared
 *   predicate).
 * @param onUndo restores the previous snapshot (no-op with an empty stack).
 * @param onRedo restores the snapshot parked by Undo (no-op with an empty stack).
 * @param onShare shares the current buffer; the caller re-reads the predicate at click time
 *   (EC-30), so a stale flag can never leak text out of the app.
 * @param onOpenMenu opens the shell's ⋮ sheet.
 * @param modifier the host's slot, applied once to the row.
 * @param motion the resolved chrome motion; injectable for previews/tests, production reads the
 *   system animator scale once per composition.
 */
@Composable
fun TopActionBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canShare: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShare: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
) {
    val enabled = topBarEnabled(canUndo = canUndo, canRedo = canRedo, canShare = canShare)

    Row(
        modifier = modifier.padding(end = EndPadding),
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
            labelRes = R.string.share,
            imageVector = Icons.Filled.Share,
            enabled = enabled.share,
            onClick = onShare,
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

/** Keep's top-right cluster sits 8dp clear of the window edge. */
private val EndPadding = 8.dp

/**
 * No gap between the 48dp targets: each draws a 40dp-wide container, so consecutive squares read
 * 8dp apart on their own — the same optical spacing Keep's toolbar has.
 */
private val ButtonSpacing = 0.dp

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────

/** A populated buffer: every control enabled, which is the state that differs visually. */
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
                onOpenMenu = {},
            )
        }
    }
}

/** Keep's empty-note state: Undo/Redo/Share are lighter, the ⋮ stays available. */
@Preview(name = "Top action bar — empty", showBackground = true)
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
                onOpenMenu = {},
            )
        }
    }
}
