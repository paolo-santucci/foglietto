package com.paolosantucci.foglietto.ui.recovery

import com.paolosantucci.foglietto.state.RecoveryRow
import java.util.Locale

/**
 * The pure seam behind [MenuSheet] (FR-26, FR-27, FR-28, NFR-04).
 *
 * The sheet itself is presentation-only; every decision it makes resolves here, so the contract
 * is pinned by a plain-JVM test instead of a Tier C instrumentation test (OQ-20):
 *
 *  - **Open = re-fetch, every time.** [open] invokes the injected refresh (the host wires
 *    `RecoveryListStateHolder::refresh`) on **every** call — constructing this model performs no
 *    fetch, so the list is never loaded at launch, and nothing is cached between opens (FR-26).
 *    A note saved while the app runs appears the next time the sheet opens.
 *  - **Close is inert.** [close] has no side effects: dismissal is the host's business and must
 *    not touch the editor buffer (the sheet is dismissible without selecting a row).
 *  - **Rows are forwarded verbatim.** [rows] returns the holder's list as-is — already newest-first
 *    and ≤10, with the shared ≤80-UTF-16 single-line preview and the shared
 *    `YYYY-MM-DD HH:MM` subtitle. No local truncation, no local date formatting (FR-27,
 *    "never re-derive a shared rule").
 *  - **Empty is a state, not an error.** [isEmpty] drives the localized `no_recent_notes` copy
 *    (FR-28); an absent recovery directory lists `[]` and must not crash (EC-02).
 *
 * @param rowSource the live row list, normally `{ holder.rows }`. It is read on demand so a
 *   refresh that lands mid-session is reflected without rebuilding the model, and reading it
 *   inside a composable records the Compose snapshot dependency.
 * @param onRefresh the FR-26 refresh action, normally `holder::refresh`.
 */
class RecoverySheetModel(
    private val rowSource: () -> List<RecoveryRow>,
    private val onRefresh: () -> Unit,
) {

    /** The rows to render, in the holder's newest-first order; verbatim (FR-27). */
    val rows: List<RecoveryRow> get() = rowSource()

    /** `true` when there is nothing to restore — the sheet renders the localized empty state (FR-28). */
    val isEmpty: Boolean get() = rows.isEmpty()

    /** The sheet became visible: re-fetch unconditionally (FR-26). Never at launch, never cached. */
    fun open() {
        onRefresh()
    }

    /** The sheet was dismissed without a restore: deliberately inert. */
    fun close() = Unit

    companion object {
        /**
         * A model over an already-known row snapshot, for tests and previews. The default
         * no-op refresh keeps "refreshes nothing" explicit rather than implying a fetch.
         */
        fun of(rows: List<RecoveryRow>, refresh: () -> Unit = {}): RecoverySheetModel =
            RecoverySheetModel(rowSource = { rows }, onRefresh = refresh)
    }
}

/**
 * Builds a recovery row's accessibility label by filling the `restore_note_label` template's two
 * positional placeholders — `%1$s` = the shared preview, `%2$s` = the shared saved-at subtitle
 * (FR-27, NFR-04). The template is a string resource; this function never holds display copy, so
 * both locales resolve through `Resources` and only the fill is unit-pinned here.
 *
 * [Locale.ROOT] is deliberate: `%s` is locale-independent, and the subtitle is already formatted
 * by the shared `RecoveryInstant.formatSubtitle()` — no date arithmetic happens here.
 */
fun rowLabel(labelTemplate: String, preview: String, subtitle: String): String =
    String.format(Locale.ROOT, labelTemplate, preview, subtitle)
