// ShareInboxDrainer.swift
// iosApp/Share
//
// The host-side ordering seam for one inbound share payload (FR-05, FR-06, FR-07,
// FR-09, FR-10, FR-18, FR-19, FR-20, NFR-01, NFR-05; spec §5.1.3).
//
// Contract — `drain()` runs EXACTLY these five steps, in this order:
//   1. text = normalize(readEntry())        — normalize sees the RAW payload
//   2. text == nil  -> clearEntry(); return — blank or absent: no save, no seed
//   3. saveCurrentBuffer()                  — the collision save (trim-guarded)
//   4. populate(text)                       — the single programmatic mutation
//   5. clearEntry()                         — ONLY after a successful seed, so a
//                                             process death between 3 and 4
//                                             replays the share on next launch
//
// Errors are never thrown and nothing is caught: a failing collision save raises
// out of step 3 and terminates the process before the seed and before the delete,
// which is exactly the FR-07 ordering (no seed, the entry survives).
//
// Synchrony (NFR-01): this file contains no Task, no DispatchQueue, no async and
// no await. The drain returns only after every effect has been applied, which is
// what lets the cold-start seed reach the first rendered frame.
//
// Privacy (NFR-03): the payload is never logged, never described and never stored
// here — the drainer holds only closures.
//
// Test seam (OQ-23 / OQ-27): the four effects are stored as closures. The
// `init(inbox:...)` initialiser is the production entry point (§5.1.3); the
// `init(read:clear:...)` initialiser exists so the ordering cases can observe and
// control read/clear without an App Group container. Both paths run the identical
// `drain()` body.

/// Turns one pending inbox payload into a seeded editor buffer.
///
/// Both call sites — `iosAppApp.init()` (cold start) and
/// `ContentView.dispatchScenePhase(.active)` (warm activation) — use the SAME
/// instance, so the order of effects can never diverge between them.
///
/// `@MainActor`: the drain is driven from the SwiftUI composition root and from
/// `.onChange(of: scenePhase)`; both are main-actor isolated, and the drain must
/// land in the same turn as the `.active` reset (FR-17).
@MainActor
struct ShareInboxDrainer {

    // MARK: - Injected effects

    /// The pending payload, or nil when the inbox holds nothing decodable.
    private let readEntry: () -> String?

    /// Consumes the pending entry. Idempotent; never throws.
    private let clearEntry: () -> Void

    /// The shared inbound rule (`IncomingTextKt.acceptIncomingText`). Returns nil
    /// for an absent or blank payload, so steps 1 and 2 share one decision.
    private let normalize: (String?) -> String?

    /// The collision save (`LifecycleSaveCoordinator.saveCurrentBuffer`): the
    /// buffer the user was looking at is saved before the seed replaces it (FR-06).
    private let saveCurrentBuffer: () -> Void

    /// The seed entry point (`NoteViewModel.populate`): the only programmatic
    /// mutation of the buffer on the inbound path (FR-05).
    private let populate: (String) -> Void

    // MARK: - Init

    /// Production initialiser (§5.1.3): read and clear come from the App Group inbox.
    ///
    /// - Parameters:
    ///   - inbox: the single pending-entry store shared with the Share Extension.
    ///   - normalize: the shared inbound rule.
    ///   - saveCurrentBuffer: the synchronous, trim-guarded collision save.
    ///   - populate: the seed entry point.
    init(
        inbox: ShareInbox,
        normalize: @escaping (String?) -> String?,
        saveCurrentBuffer: @escaping () -> Void,
        populate: @escaping (String) -> Void
    ) {
        self.init(
            read: { inbox.read() },
            clear: { inbox.clear() },
            normalize: normalize,
            saveCurrentBuffer: saveCurrentBuffer,
            populate: populate
        )
    }

    /// Test seam (OQ-23 / OQ-27): the same five effects with read and clear
    /// injected, so the ordering cases need no App Group container and can record
    /// the clear step itself. Never called from production code.
    init(
        read: @escaping () -> String?,
        clear: @escaping () -> Void,
        normalize: @escaping (String?) -> String?,
        saveCurrentBuffer: @escaping () -> Void,
        populate: @escaping (String) -> Void
    ) {
        self.readEntry = read
        self.clearEntry = clear
        self.normalize = normalize
        self.saveCurrentBuffer = saveCurrentBuffer
        self.populate = populate
    }

    // MARK: - Drain

    /// Applies the five ordered steps and returns after all of them have run.
    ///
    /// A rejected payload (absent, unreadable or blank) is still consumed: the
    /// entry is cleared and nothing else happens — no save, no seed, no toast.
    ///
    /// - Returns: `true` when a payload was seeded into the buffer, `false` when
    ///   the inbox held nothing usable. The cold-start caller uses it to arm the
    ///   launch-seed guard on the coordinator (FR-16); the warm `.active` caller
    ///   ignores it, because its reset has already run.
    @discardableResult
    func drain() -> Bool {
        guard let text = normalize(readEntry()) else {
            clearEntry()
            return false
        }

        saveCurrentBuffer()
        populate(text)
        clearEntry()
        return true
    }
}
