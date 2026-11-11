package com.paolosantucci.foglietto.ui.recovery

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.state.RecoveryRow
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/*
 * The Recent-notes *list* — restore-only by construction (FR-26..FR-30).
 *
 * It is a `LazyListScope` extension rather than a composable, because the list is one section of
 * the shell's ⋮ sheet (header, notes, divider, About row) and the sheet owns the single
 * `LazyColumn`. Extracting the items keeps the row rendering — preview, subtitle, tap target,
 * accessibility label, `animateItem()` — in exactly one place for any future host.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Design ethos ("every adaptation must subtract visual weight"): no cards, no icons, no
 *    delete/swipe affordance, no search — a header, a divider-free list, one empty state (FR-30).
 *  - §Motion: `Modifier.animateItem()` on the rows is the canon's "crossfade is the universal
 *    transition" applied to list changes (FR-35). No hand-rolled animation, no scale/parallax; the
 *    sheet's own slide/scrim is the stock Material 3 `ModalBottomSheet` motion.
 *  - §Accessibility: every row is a single merged semantics node labelled `restore_note_label`
 *    (preview + subtitle) for TalkBack (FR-27, NFR-04); rows are ≥ 48dp hit targets
 *    (§Touch-target note) and the empty state is a text node, not a colour-only cue.
 *  - CANON GAP: the canon documents the desktop menu surface (`Adw` popover + bottom-sheet
 *    recommendation) but no recovery-list anatomy, so this is the spec's FR-26..FR-30 shape
 *    flagged for the canon owner rather than invented as canon.
 */

/**
 * Appends the Recent-notes section to a host's lazy list: one row per note (newest-first, in the
 * holder's order) or the localized empty state when there is nothing to restore (FR-28).
 *
 * @param rows the holder's rows, already previewed and subtitled by `shared` (FR-27).
 * @param isEmpty `true` renders [NoRecentNotes] instead of the rows.
 * @param onSelectRow the row tap, before any dismissal animation; the host wires
 *   `holder.select(row.path)` so this file owns no editor state (FR-29).
 */
internal fun LazyListScope.recentNoteItems(
    rows: List<RecoveryRow>,
    isEmpty: Boolean,
    onSelectRow: (RecoveryRow) -> Unit,
) {
    if (isEmpty) {
        item(key = "no-recent-notes") { NoRecentNotes() }
    } else {
        items(items = rows, key = { row -> row.path }) { row ->
            RecoveryNoteRow(
                row = row,
                modifier = Modifier.animateItem(),
                onClick = { onSelectRow(row) },
            )
        }
    }
}

/** The FR-28 empty state: localized text, secondary ink, and never inferred from a null list. */
@Composable
private fun NoRecentNotes() {
    Text(
        text = stringResource(R.string.no_recent_notes),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One restore row: the shared preview (single line, ellipsised only as a rendering guard — the
 * shared producer already guarantees a ≤80-UTF-16 single-line preview, FR-27) over the shared
 * `YYYY-MM-DD HH:MM` subtitle as secondary ink. The whole row is the target: `ListItem`'s stock
 * minimum height is 56 dp for one-line and 72 dp with supporting text, and `heightIn(min = 48.dp)`
 * keeps the NFR-04 floor explicit.
 *
 * The label is the rendered accessibility text: `restore_note_label` filled with (preview,
 * subtitle), so TalkBack announces exactly what a restore would bring back. No delete affordance
 * exists on the row — there is nothing to long-press or swipe (FR-30).
 */
@Composable
private fun RecoveryNoteRow(
    row: RecoveryRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = rowLabel(
        labelTemplate = stringResource(R.string.restore_note_label),
        preview = row.preview,
        subtitle = row.subtitle,
    )

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = row.preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = row.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

@Preview(name = "Recent notes — light", showBackground = true)
@Preview(name = "Recent notes — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecentNoteItemsPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                recentNoteItems(rows = PREVIEW_ROWS, isEmpty = false, onSelectRow = {})
            }
        }
    }
}

/** The FR-28 empty state, which is a state of its own and must not be inferred from the list. */
@Preview(name = "Recent notes — empty", showBackground = true)
@Composable
private fun RecentNoteItemsEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                recentNoteItems(rows = emptyList(), isEmpty = true, onSelectRow = {})
            }
        }
    }
}

/** Row-shaped fixtures for the previews — the same fields the holder maps from `shared`. */
private val PREVIEW_ROWS = listOf(
    RecoveryRow(
        path = "preview-1",
        preview = "Groceries — milk, bread, coffee",
        subtitle = "2026-11-11 09:07",
    ),
    RecoveryRow(
        path = "preview-2",
        preview = "Draft: an ephemeral note",
        subtitle = "2026-11-10 18:42",
    ),
)
