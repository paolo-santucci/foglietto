package com.paolosantucci.foglietto.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paolosantucci.foglietto.shared.recovery.RecoveryNote
import com.paolosantucci.foglietto.shared.recovery.RecoveryRepository

/**
 * One rendered Recent-notes row — restore-only, never destructive (FR-30).
 *
 * @property path     the note's stable identity, forwarded verbatim to `read(path)`.
 * @property preview  the shared description preview (`RecoveryNote.preview`), **not** re-truncated
 *                    here: the shared producer already guarantees ≤80 single-line UTF-16 units
 *                    (FR-27, EC-19).
 * @property subtitle the promoted shared `RecoveryInstant.formatSubtitle()` output,
 *                    `"YYYY-MM-DD HH:MM"` — never formatted locally (FR-27).
 */
data class RecoveryRow(
    val path: String,
    val preview: String,
    val subtitle: String,
)

/**
 * State holder for the "Recent notes" sheet (FR-26..FR-30, §5.1.c).
 *
 * Behaviour lives here, in plain testable Kotlin; the sheet composable only renders [rows].
 *
 * ## Contracts
 *
 *   - [refresh] calls [RecoveryRepository.list] on **every** invocation — the list is never
 *     cached at launch, so a note saved while the app is running appears the next time the
 *     sheet opens (FR-26). `list()` is newest-first and ≤10 by the repository's own contract,
 *     and the holder preserves that order without re-sorting.
 *   - Each note is mapped to a [RecoveryRow] by forwarding `path` and the shared
 *     `preview` and `savedAt.formatSubtitle()` — no local truncation, no local date formatting
 *     (FR-27; a date pattern is not UI copy).
 *   - [select] reads the note and hands a **non-null** text to [populate] exactly once;
 *     the editor's `populate` resets the caret to zero and signals scroll-to-top (FR-29).
 *     A `null` read — the file vanished between listing and tap — is a silent no-op: no throw,
 *     no buffer change, rows unchanged (EC-04).
 *
 * ## Wiring
 *
 * The editor is injected as the populate callback rather than a concrete `EditorStateHolder`:
 * the holder only needs "populate the buffer with this text", so it stays one function wide and
 * independent of the editor's undo/selection internals. The composition root wires
 * `editor::populate`.
 *
 * ## Deliberate absences
 *
 * No delete/`deleteAll` call and no destructive affordance anywhere in this file or in
 * [RecoveryRow] (FR-30) — the recovery surface is restore-only.
 *
 * [rows] is Compose snapshot state so the sheet recomposes when a refresh lands; it is read and
 * written outside any composition, so the holder stays testable with plain JUnit (Tier A).
 */
class RecoveryListStateHolder(
    private val recovery: RecoveryRepository,
    private val populate: (String) -> Unit,
) {

    /** The rows rendered by the sheet, newest-first; empty until the first [refresh]. */
    var rows: List<RecoveryRow> by mutableStateOf(emptyList())
        private set

    /**
     * Re-fetches and re-maps the recovery list. Called on **every** sheet open — never at
     * launch, never cached (FR-26). Total: an absent directory yields empty rows (EC-02).
     */
    fun refresh() {
        rows = recovery.list().map { note -> note.toRow() }
    }

    /**
     * Restores the note at [path] into the editor via [populate].
     *
     * A `null` read is a silent no-op — the row's file may have been removed externally
     * between `list()` and the tap (EC-04). `read`/`list` are synchronous on the caller thread,
     * matching the iOS contract: ms-scale for ≤10 small files (EC-29).
     */
    fun select(path: String) {
        val text = recovery.read(path) ?: return
        populate(text)
    }
}

/** Maps a shared note to its restore-only row, delegating preview and subtitle to `shared`. */
private fun RecoveryNote.toRow(): RecoveryRow = RecoveryRow(
    path = path,
    preview = preview,
    subtitle = savedAt.formatSubtitle(),
)
