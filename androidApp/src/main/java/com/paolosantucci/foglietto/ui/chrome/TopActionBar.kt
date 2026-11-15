package com.paolosantucci.foglietto.ui.chrome

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
 * The top bar's action cluster: **Share alone**, in the same rounded-square tonal container the rest
 * of the chrome uses (FR-20, FR-33):
 *
 * ```
 *                                                          (Share)
 * ```
 *
 * ## What moved, and why
 *
 * This bar used to carry Undo, Redo, Share and the ⋮. The current reference (Google Keep's note
 * editor, as re-drawn for this product) puts the whole editing surface in **one** bottom bar — Copy,
 * Paste, Outdent, Indent, Clear on the left, Undo, Redo and the ⋮ on the right — so those three moved
 * to [EditorActionRow] and the title-less top bar keeps only the action that leaves the app. That is
 * also why the ⋮ menu is anchored at the bottom: its anchor is the bottom bar's own overflow.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Components 1 "App shell" (mobile adaptation): title-less bar, minimal chrome, the editor is
 *    the screen — this row is the bar's whole content.
 *  - §Accessibility: the control is icon-only, so it carries a string-resource content description
 *    ("Share") and a ≥48dp target — both owned by [TonalActionButton], never restated here.
 *
 * The rounded-square container is the one deliberate departure from the canon's circular chrome:
 * Keep's Material 3 Expressive toolbar draws each action in its own rounded square. The *metrics*
 * live in [TonalActionButton], so the shape cannot drift per call site.
 *
 * @param canShare drives Share's enabled state; the caller passes the shared predicate
 *   (`EditorStateHolder.shareEnabled`, FR-08/EC-30) and re-reads it at click time.
 * @param onShare shares the current buffer.
 * @param modifier the host's slot, applied once to the row.
 * @param motion the resolved chrome motion; injectable for previews/tests, production reads the
 *   system animator scale once per composition.
 */
@Composable
fun TopActionBar(
    canShare: Boolean,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    motion: MotionSpec = rememberMotionSpec(),
) {
    Row(
        modifier = modifier.padding(end = EndPadding),
        horizontalArrangement = Arrangement.spacedBy(ButtonSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TonalActionButton(
            labelRes = R.string.share,
            imageVector = Icons.Filled.Share,
            enabled = canShare,
            onClick = onShare,
            motion = motion,
        )
    }
}

/** Keep's top-right cluster sits 8dp clear of the window edge. */
private val EndPadding = 8.dp

/**
 * One action, so spacing cannot be observed — kept at 0 to match the rounded squares' own 8dp
 * optical gap the moment a second control returns to this bar.
 */
private val ButtonSpacing = 0.dp

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────

/** A populated buffer: Share enabled, which is the state that differs visually. */
@Preview(name = "Top action bar — light", showBackground = true)
@Preview(name = "Top action bar — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TopActionBarPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            TopActionBar(canShare = true, onShare = {})
        }
    }
}

/** Keep's empty/whitespace-only state: Share is dimmed but the bar keeps its seat. */
@Preview(name = "Top action bar — empty", showBackground = true)
@Composable
private fun TopActionBarEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            TopActionBar(canShare = false, onShare = {})
        }
    }
}
