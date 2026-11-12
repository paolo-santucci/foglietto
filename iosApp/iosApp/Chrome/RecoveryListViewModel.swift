// Chrome/RecoveryListViewModel.swift
// Foglietto — KMP Milestone 4: Liquid Glass Chrome
//
// @Observable view model backing the recent-notes rows of the menu (top level).
// Re-fetches RecoveryRepository.list() on every menu open (not launch-cached).
// Maps each RecoveryNote to a RecoveryRow for display.
// Restore-on-tap only — no delete, delete-all, or confirm dialog (FR-14).
//
// RecoveryInstant → subtitle comes from the shared Kotlin `formatSubtitle()`.
// No local formatter: the rule is delegated to `shared` (FR-09 / TASK-15).
// NO Date is used; NO epoch arithmetic is used (NFR-06).
// RecoveryRepository is called DIRECTLY (no read-side use case; no okio import).
//
// Spec refs: FR-12, FR-13, FR-14, FR-23, NFR-06;
//            EC-02, EC-04, EC-05, EC-06, EC-16.
// Contract: §5.1.d, §5.1.c (RecoveryRepository direct call, OQ-07 decision).

import Observation
import shared

// MARK: - RecoveryRow

/// Display-ready view struct for a single recovery note row.
///
/// `path` is the stable identity key (used as the argument to `select(path:)`).
/// `previewTitle` is the first ≤80 UTF-16 characters of the note (one-line, no newline —
///   guaranteed by the shared `RecoveryPreview.truncate` contract).
/// `dateSubtitle` is the shared `RecoveryInstant.formatSubtitle()` output —
///   "YYYY-MM-DD HH:MM", NO `Date`, NO epoch arithmetic (NFR-06, FR-09).
struct RecoveryRow: Identifiable {
    /// Stable identity for `ForEach` keying and for the `select(path:)` call.
    var id: String { path }

    /// Absolute file path — passed to `RecoveryRepository.read(path:)` on tap (FR-13).
    let path: String

    /// Single-line preview title (≤80 UTF-16, no newline — FR-12 / §5.1.c).
    let previewTitle: String

    /// Date subtitle produced by the shared `RecoveryInstant.formatSubtitle()`.
    /// Format: "YYYY-MM-DD HH:MM" (one shared implementation — FR-09).
    /// No `Date`, no epoch math (NFR-06).
    let dateSubtitle: String
}

// MARK: - RecoveryListViewModel

/// `@Observable` view model backing the recent-notes rows of the menu.
///
/// **Ctor-injected (DIP):**
///   - `recovery: RecoveryRepository` — for `list()` and `read(path:)` calls.
///   - `viewModel: NoteViewModel` — for `populate(_:)` on restore.
///
/// **Re-fetch on every open (FR-12):**
/// `refresh()` calls `recovery.list()` on every invocation. There is no launch-time
/// cache — the rows reflect on-disk state at the moment the menu is opened. This satisfies
/// EC-05 (mutated file) and EC-06 (absent dir on first open, which causes `list()`
/// to return `[]` without crashing).
///
/// **Restore-on-tap, nil-tolerant (FR-13 / EC-04):**
/// `select(path:)` calls `recovery.read(path:)`. If the result is `nil` (file
/// vanished since `list()` — EC-04), nothing is restored and no crash occurs.
/// If non-nil, `viewModel.populate(_:)` is called exactly once (FR-13).
///
/// **List only — no delete (FR-14):**
/// No `delete`, `deleteAll`, or confirmation affordance is present on this class.
///
/// **No filesystem types across the boundary:**
/// `RecoveryRepository` is called directly; the underlying okio filesystem/path types are
/// internal to the shared Kotlin module and are never referenced in Swift (OQ-07 decision).
@Observable
final class RecoveryListViewModel {

    // MARK: - Injected dependencies (DIP)

    private let recovery: RecoveryRepository
    private let noteViewModel: NoteViewModel

    // MARK: - Observable state

    /// The list of rows currently shown in the menu.
    /// Empty until `refresh()` is called; also empty when there are no recovery notes
    /// on disk (EC-02 empty-recovery state).
    private(set) var rows: [RecoveryRow] = []

    // MARK: - Init

    /// Designated initialiser.
    ///
    /// - Parameters:
    ///   - recovery: The `RecoveryRepository` to call for `list()` / `read(path:)`.
    ///   - viewModel: The `NoteViewModel` to call `populate(_:)` on for restore.
    init(recovery: RecoveryRepository, viewModel: NoteViewModel) {
        self.recovery = recovery
        self.noteViewModel = viewModel
    }

    // MARK: - Refresh (re-fetch on every open — FR-12)

    /// Re-fetch the recovery note list from disk.
    ///
    /// Called by `MenuBubble` on every menu open.
    /// Not cached from launch — each call reflects the current on-disk state.
    ///
    /// The `list()` result is already ordered newest-first by filename
    /// (`FileRecoveryRepository` guarantees this — §5.1.c); no client-side
    /// sort is applied. There is no read-side display cap (OQ-04/05 decision).
    ///
    /// Each `RecoveryNote` is mapped to a `RecoveryRow` with:
    ///   - `previewTitle` set to `note.preview` (≤80 UTF-16, single line — §5.1.c).
    ///   - `dateSubtitle` taken from the shared `note.savedAt.formatSubtitle()`
    ///     (delegated to Kotlin — no local formatting, FR-09).
    func refresh() {
        let notes = recovery.list()
        rows = notes.map { note in
            RecoveryRow(
                path: note.path,
                previewTitle: note.preview,
                dateSubtitle: note.savedAt.formatSubtitle()
            )
        }
    }

    // MARK: - Select / restore (FR-13 / EC-04 / EC-05 / EC-16)

    /// Restore a recovery note into the buffer.
    ///
    /// Calls `recovery.read(path:)` synchronously (the shared API is non-suspend —
    /// FR-24; EC-16: a large synchronous read may briefly block but does not crash).
    ///
    /// **Nil-tolerance (EC-04 / FR-13):** If `read` returns `nil` (file vanished since
    /// the last `list()` call — e.g. deleted via Files app), no restore occurs and
    /// no crash is raised. The row simply does nothing.
    ///
    /// **On-disk text (EC-05):** If the file was mutated externally between `list()`
    /// and this tap, `read` returns the current on-disk text. There is no stale
    /// snapshot assumption — whatever `read` returns is what is restored.
    ///
    /// No delete or side-effect occurs on the recovery file itself (FR-14).
    ///
    /// - Parameter path: The absolute file path from the `RecoveryRow`.
    func select(path: String) {
        guard let text = recovery.read(path: path) else {
            // EC-04: file vanished since list() — no restore, no crash.
            return
        }
        // FR-13: non-keystroke-origin restore via populate(_:).
        noteViewModel.populate(text)
    }
}
