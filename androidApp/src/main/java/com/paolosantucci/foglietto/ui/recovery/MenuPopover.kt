package com.paolosantucci.foglietto.ui.recovery

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paolosantucci.foglietto.R
import com.paolosantucci.foglietto.state.RecoveryRow
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/*
 * The ⋮ menu's body — the ONE menu (FR-21, FR-26..FR-30, FR-34).
 *
 * The menu is no longer a sheet: the ⋮ in the bottom bar anchors a Material 3 popover (the
 * `DropdownMenu` inside `EditorActionRow`) and this file supplies the card's content, in Google
 * Keep's overflow-card anatomy: a header line, a hairline, the recent-note rows, a hairline, then an
 * icon + label row. That is why the old two-level shape is gone as well — the notes are visible on
 * open, not behind a second tap.
 *
 * Traced to `.claude/docs/canon/ui-design-bible.md`:
 *  - §Design ethos ("subtract visual weight"): a header, the rows, two hairlines, one action row —
 *    no cards, no icons on the notes, no delete/swipe affordance, no search (FR-30).
 *  - §Motion: `Modifier.animateItem()` on the rows is the canon's "crossfade is the universal
 *    transition" applied to list changes (FR-35); the popover's own enter/exit is the stock
 *    Material 3 menu motion. No hand-rolled animation, no scale/parallax.
 *  - §Accessibility: rows are ≥48dp targets with a merged label (`restore_note_label`, FR-27); the
 *    About row is a ≥56dp full-width target whose visible text is its label; the empty state is a
 *    text node, never a colour-only cue.
 *  - CANON GAP: the canon documents the desktop menu surface (`Adw` popover + the bottom-sheet
 *    recommendation) but no recovery-list anatomy, so the section order here is the spec's
 *    FR-21/FR-26 shape flagged for the canon owner rather than invented as canon.
 */

/**
 * The ⋮ popover's content: "Recent notes" header, the recovery rows inline, a hairline, then the
 * About row. It renders into the host's menu `Column` — the card itself (shape, width, elevation) is
 * the popover's, so this composable owns no root and takes no `Modifier`.
 *
 * ## Composition contract — read this before hosting it
 *
 * The host **must compose this only while the menu is open** (the `DropdownMenu` composes its
 * content only while expanded), never keep it mounted behind a visibility flag. Because of that,
 * [LaunchedEffect] with a constant key is a true per-open signal: entering the composition *is*
 * opening the menu, so `model.open()` runs exactly once per open and re-fetches the list on every
 * open — never at launch, never from a cache (FR-26). [RecoverySheetModelTest] pins the seam this
 * depends on (`open()` refreshes; constructing it does not).
 *
 * The refresh is the holder's synchronous `refresh()` (a handful of small files, ms-scale — EC-29),
 * so no dispatch hop is added here and the list is never shown half-updated.
 *
 * ## Height
 *
 * The rows are emitted eagerly into a fixed-height scroll window ([NotesMaxHeight], three rows).
 * Two constraints meet here: the card must stay short enough to open *above* the bottom bar (the
 * ⋮ it anchors to sits at the bottom edge, so a taller card is clipped), and it must not contain a
 * lazy list — a Material 3 `DropdownMenu` content column is queried for intrinsic measurements and
 * a `SubcomposeLayout` child throws at measure time. Eager rows plus a plain scroll column satisfy
 * both; ten notes is the whole store, so eager rendering costs nothing.
 *
 * ## Restore and About
 *
 * A row tap invokes [onRestore] with the row (the host wires `holder.select(row.path)` and closes
 * the menu). A vanished file is a silent no-op inside the holder (EC-04) — this composable never
 * inspects the read result.
 *
 * @param model the open/refresh + rows seam; normally built once from the holder:
 *   `RecoverySheetModel(rowSource = { holder.rows }, onRefresh = holder::refresh)`.
 * @param onRestore invoked with the tapped row; the buffer is populated by the holder, so this
 *   composable owns no editor state (FR-29).
 * @param onOpenAbout opens the About sheet (FR-34); the host also closes the menu.
 */
@Composable
internal fun MenuPopoverContent(
    model: RecoverySheetModel,
    onRestore: (RecoveryRow) -> Unit,
    onOpenAbout: () -> Unit,
) {
    val rows = model.rows

    LaunchedEffect(Unit) {
        model.open()
    }

    Text(
        text = stringResource(R.string.recent_notes),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleMedium,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = NotesMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        RecentNoteRows(
            rows = rows,
            isEmpty = rows.isEmpty(),
            onSelectRow = onRestore,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    MenuActionRow(
        label = stringResource(R.string.about),
        onClick = onOpenAbout,
    )
}

/**
 * One menu action row — the popover's About entry (FR-34).
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

/**
 * The tallest the note list may get inside the card: three 72dp rows. Kept below the free space
 * above the ⋮ so the whole card (header, list, About) opens upward without clipping.
 */
private val NotesMaxHeight = 216.dp

// ── Previews (TASK-25, I6) ───────────────────────────────────────────────────
// `ui-tooling-preview` is already on the classpath; rendering these in Android Studio needs
// `debugImplementation(libs.androidx.compose.ui.tooling)`, which this module does not declare.

/** The card body as the popover draws it: the same surface, shape and width. */
@Preview(name = "Menu popover — light", showBackground = true)
@Preview(name = "Menu popover — dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Menu popover — expanded", widthDp = 840, showBackground = true)
@Composable
private fun MenuPopoverContentPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            MenuPopoverCard {
                MenuPopoverContent(
                    model = RecoverySheetModel.of(PREVIEW_MENU_ROWS),
                    onRestore = {},
                    onOpenAbout = {},
                )
            }
        }
    }
}

/** The empty variant: no notes yet, About still reachable. */
@Preview(name = "Menu popover — empty", showBackground = true)
@Composable
private fun MenuPopoverContentEmptyPreview() {
    FogliettoTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            MenuPopoverCard {
                MenuPopoverContent(
                    model = RecoverySheetModel.of(emptyList()),
                    onRestore = {},
                    onOpenAbout = {},
                )
            }
        }
    }
}

/** The popover's card, for previews only: `DropdownMenu` has no window to attach to in a preview. */
@Composable
private fun MenuPopoverCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(PREVIEW_CARD_WIDTH)
            .padding(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(PREVIEW_CARD_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = PREVIEW_CARD_ELEVATION,
        ) {
            Column(content = { content() })
        }
    }
}

private val PREVIEW_CARD_WIDTH = 320.dp
private val PREVIEW_CARD_CORNER = 28.dp
private val PREVIEW_CARD_ELEVATION = 3.dp

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
