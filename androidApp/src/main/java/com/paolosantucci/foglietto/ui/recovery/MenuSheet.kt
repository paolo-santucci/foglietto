package com.paolosantucci.foglietto.ui.recovery

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.state.RecoveryRow
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme
import kotlinx.coroutines.launch

/*
 * The shell's ⋮ sheet — the ONE menu (FR-21, FR-26..FR-30, FR-34).
 *
 * Google Keep's note editor puts the overflow menu in the bottom bar and opens it as a sheet whose
 * first section *is* the recent-notes list. So the old two-level shape (a `DropdownMenu` with a
 * "Recent notes" row that opened a second sheet) is gone: one `ModalBottomSheet`, notes visible on
 * open, one divider, one About row.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Design ethos ("subtract visual weight"): a header, the rows, one hairline, one action row —
 *    no cards, no icons on the notes, no delete/swipe affordance, no search (FR-30).
 *  - §Motion: the stock Material 3 `ModalBottomSheet` slide/scrim is the only animation the sheet
 *    authors, plus `Modifier.animateItem()` on the rows (the canon's "crossfade is the universal
 *    transition" applied to list changes, FR-35). No hand-rolled animation, no scale/parallax.
 *  - §Accessibility: rows are ≥48dp targets with a merged label (`restore_note_label`, FR-27); the
 *    About row is a ≥56dp full-width target whose visible text is its label; the empty state is a
 *    text node, never a colour-only cue.
 *  - CANON GAP: the canon documents the desktop menu surface (`Adw` popover + the bottom-sheet
 *    recommendation) but no recovery-list anatomy, so the section order here is the spec's
 *    FR-21/FR-26 shape flagged for the canon owner rather than invented as canon.
 */

/**
 * The ⋮ `ModalBottomSheet`: "Recent notes" header, the recovery rows inline, a hairline divider,
 * then the About row.
 *
 * ## Composition contract — read this before hosting it
 *
 * The host **must compose this only while the sheet is open** (`if (openSheet == ShellSheet.Menu)
 * { MenuSheet(...) }`), never keep it mounted behind a visibility flag. Because of that,
 * [LaunchedEffect] with a constant key is a true per-open signal: entering the composition *is*
 * opening the sheet, so `model.open()` runs exactly once per open and re-fetches the list on
 * every open — never at launch, never from a cache (FR-26). [RecoverySheetModelTest] pins the seam
 * this depends on (`open()` refreshes; constructing it does not).
 *
 * The refresh is the holder's synchronous `refresh()` (a handful of small files, ms-scale —
 * EC-29), so no dispatch hop is added here and the list is never shown half-updated.
 *
 * ## Restore and About
 *
 * A row tap invokes [onRestore] with the row (the host wires `holder.select(row.path)`), then
 * dismisses the sheet through the stock M3 hide animation. A vanished file is a silent no-op
 * inside the holder (EC-04) — this composable never inspects the read result.
 *
 * The About row likewise hides first and only then hands over to [onOpenAbout], so the shell swaps
 * one sheet for the other without a frame of two scrims.
 *
 * @param model the open/refresh + rows seam; normally built once from the holder:
 *   `RecoverySheetModel(rowSource = { holder.rows }, onRefresh = holder::refresh)`.
 * @param onRestore invoked with the tapped row *before* dismissal; the buffer is populated by the
 *   holder, so this composable owns no editor state (FR-29).
 * @param onOpenAbout opens the About sheet (FR-34) once this sheet has hidden.
 * @param onDismissRequest the host's "close the sheet" signal — scrim tap, back gesture, or a
 *   completed restore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    model: RecoverySheetModel,
    onRestore: (RecoveryRow) -> Unit,
    onOpenAbout: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val rows = model.rows

    LaunchedEffect(Unit) {
        model.open()
    }

    /**
     * Plays the stock hide animation, then runs [after] on the main thread once the sheet is
     * actually invisible — the same shape the restore path uses, so a cancelled hide (a drag that
     * snaps back) never fires the follow-up action.
     */
    fun hideThen(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) after()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        MenuSheetBody(
            rows = rows,
            isEmpty = rows.isEmpty(),
            onSelectRow = { row ->
                onRestore(row)
                hideThen(onDismissRequest)
            },
            onOpenAbout = { hideThen(onOpenAbout) },
        )
    }
}

/**
 * The sheet's content: header, the recovery rows (or the localized empty state), a hairline, the
 * About row.
 *
 * Split out of [MenuSheet] so it can be previewed and inspected without a dialog window (a
 * `ModalBottomSheet` has no window to attach to inside a `@Preview`), and so the section order is
 * one plainly readable block.
 *
 * @param rows the holder's rows, newest-first, already previewed and subtitled by `shared`.
 * @param isEmpty `true` renders the localized empty state instead of the rows (FR-28).
 * @param onSelectRow the row tap, before any dismissal animation.
 * @param onOpenAbout the About row tap.
 */
@Composable
internal fun MenuSheetBody(
    rows: List<RecoveryRow>,
    isEmpty: Boolean,
    onSelectRow: (RecoveryRow) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item(key = "recent-notes-header") {
            Text(
                text = stringResource(R.string.recent_notes),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        recentNoteItems(rows = rows, isEmpty = isEmpty, onSelectRow = onSelectRow)

        item(key = "menu-divider") {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        item(key = "menu-about") {
            MenuActionRow(
                label = stringResource(R.string.about),
                onClick = onOpenAbout,
            )
        }
    }
}

/**
 * One menu action row — the ⋮ sheet's About entry (FR-34).
 *
 * The whole row is the target: `heightIn(min = 56.dp)` keeps it above the NFR-04 floor and
 * `clickable` is applied **before** the content padding so the ripple and the touch area cover the
 * full row, not just the text. The label is visible text, so the leading icon is decorative
 * (`contentDescription = null`).
 */
@Composable
private fun MenuActionRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

@Preview(name = "Menu sheet — light", showBackground = true)
@Preview(name = "Menu sheet — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Menu sheet — expanded", widthDp = 840, showBackground = true)
@Composable
private fun MenuSheetBodyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            MenuSheetBody(
                rows = PREVIEW_MENU_ROWS,
                isEmpty = false,
                onSelectRow = {},
                onOpenAbout = {},
            )
        }
    }
}

/** The empty variant: no notes yet, About still reachable. */
@Preview(name = "Menu sheet — empty", showBackground = true)
@Composable
private fun MenuSheetBodyEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            MenuSheetBody(rows = emptyList(), isEmpty = true, onSelectRow = {}, onOpenAbout = {})
        }
    }
}

/** Row-shaped fixtures for the previews — the same fields the holder maps from `shared`. */
private val PREVIEW_MENU_ROWS = listOf(
    RecoveryRow(
        path = "preview-menu-1",
        preview = "Groceries — milk, bread, coffee",
        subtitle = "2026-11-11 09:07",
    ),
    RecoveryRow(
        path = "preview-menu-2",
        preview = "Draft: an ephemeral note",
        subtitle = "2026-11-10 18:42",
    ),
)
