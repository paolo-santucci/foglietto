// Chrome/MenuViewModel.swift
// Foglietto — KMP Milestone 4: Liquid Glass Chrome
//
// View model backing the menu bubble. Ctor-injected (DIP, no provider
// indirection). With the theme picker and font-size rocker removed (user
// direction — the app always follows the system theme and the system default
// font), it owns exactly one responsibility: fresh RecoveryListViewModel
// creation on each menu open.
//
// Spec refs: FR-12; NFR-05, NFR-06; EC-10.
// Contract: §5.1.d.

import shared

// MARK: - MenuViewModel

/// View model backing `MenuBubble`.
///
/// Ctor-injected with the two dependencies it needs (DIP — concretes wired
/// at the composition root in `ContentView`; no provider or singleton
/// access inside this class):
///   - `recovery: RecoveryRepository` — for passing to fresh `RecoveryListViewModel` instances.
///   - `viewModel: NoteViewModel` — for passing to fresh `RecoveryListViewModel` instances.
///
/// **Fresh RecoveryListViewModel (FR-12):**
/// `makeRecoveryListViewModel()` returns a NEW `RecoveryListViewModel` on every call,
/// so each menu open re-fetches `recovery.list()` fresh (never launch-cached).
final class MenuViewModel {

    // MARK: - Injected dependencies (DIP)

    private let recovery: RecoveryRepository
    private let noteViewModel: NoteViewModel

    // MARK: - Init

    /// Designated initialiser — DIP: all concrete dependencies wired by the caller.
    ///
    /// - Parameters:
    ///   - recovery: The shared `RecoveryRepository` instance (CM-3/FR-05).
    ///   - viewModel: The shared `NoteViewModel` instance.
    init(recovery: RecoveryRepository, viewModel: NoteViewModel) {
        self.recovery = recovery
        self.noteViewModel = viewModel
    }

    // MARK: - Fresh RecoveryListViewModel factory (FR-12)

    /// Returns a **fresh** `RecoveryListViewModel` on every call.
    ///
    /// The menu builder calls this each time the menu is opened (the recents are
    /// top-level rows resolved from a deferred element, not a submenu).
    /// A fresh instance is returned (never cached from the previous open) so that
    /// `refresh()` re-fetches `recovery.list()` from the current on-disk state.
    /// Caching would violate FR-12 ("re-fetches on every open").
    ///
    /// - Returns: A new `RecoveryListViewModel` ready to have `refresh()` called on it.
    func makeRecoveryListViewModel() -> RecoveryListViewModel {
        RecoveryListViewModel(recovery: recovery, viewModel: noteViewModel)
    }
}
