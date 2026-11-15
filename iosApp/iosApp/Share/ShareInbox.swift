// ShareInbox.swift
// iosApp/Share
//
// The App Group inbox: exactly one pending inbound share payload, written by the
// Share Extension and drained by the host app (FR-13, FR-15, FR-20, NFR-08).
//
// This file is the SINGLE definition of the inbox path grammar and of the atomic
// entry write: TASK-09 compiles it into the extension target as well, so the two
// processes can never disagree about where the payload lives.
//
// Contract (§5.1.2):
//   container  App Group — resolved lazily on every call, never cached
//   directory  <container>/Library/Application Support/FogliettoInbox  (first write)
//   entry      pending.txt — raw UTF-8 payload, no BOM, no framing, no metadata
//
// Errors are values, never thrown: `read()` is nil, `write(_:)` is false, `clear()`
// is a no-op. The inbox is Foundation-only (no SwiftUI, no KMP, no host-app types)
// and performs no logging and no directory enumeration — the App Group holds the
// payload and nothing else (FR-27).

import Foundation

struct ShareInbox {

    /// The App Group declared by both the host app and the Share Extension (FR-26).
    static let appGroupIdentifier = "group.paolosantucci.foglietto"

    /// Where the container comes from, resolved on every call.
    private enum ContainerSource {
        /// Production: the App Group container, resolved lazily per call so no
        /// cached URL can outlive a container that is not there yet.
        case appGroup(identifier: String)
        /// Tests (OQ-23): an injected directory instead of the real App Group.
        case directory(url: URL)
        /// No container available: every operation fails closed.
        case unavailable
    }

    /// One component per directory level, so a space in a name can never be
    /// absorbed into a neighbouring component.
    private static let directoryComponents = ["Library", "Application Support", "FogliettoInbox"]
    private static let entryName = "pending.txt"

    private let source: ContainerSource

    /// Production initialiser: the App Group is the only container used.
    init(appGroupIdentifier: String = ShareInbox.appGroupIdentifier) {
        self.source = .appGroup(identifier: appGroupIdentifier)
    }

    /// Test-only seam (OQ-23): inject a directory, or nil for "container missing".
    /// The consumer API (`read`/`clear`/`write`) is identical either way.
    init(containerURL: URL?) {
        if let containerURL {
            self.source = .directory(url: containerURL)
        } else {
            self.source = .unavailable
        }
    }

    // -----------------------------------------------------------------------
    // MARK: - Contract
    // -----------------------------------------------------------------------

    /// The pending payload, or nil when there is nothing decodable to drain.
    ///
    /// nil when: the container is nil, the directory or entry is absent, the read
    /// fails, or the bytes are not valid UTF-8 — in that last case the undecodable
    /// entry is deleted first so it can never be drained twice.
    func read() -> String? {
        guard let containerURL = self.containerURL else { return nil }
        guard let data = try? Data(contentsOf: entryFileURL(in: containerURL)) else { return nil }
        guard let text = String(data: data, encoding: .utf8) else {
            clear()
            return nil
        }
        return text
    }

    /// Removes the pending entry if it exists. Missing entry (and any other
    /// failure) is swallowed: the call is idempotent and never throws.
    func clear() {
        guard let containerURL = self.containerURL else { return }
        try? FileManager.default.removeItem(at: entryFileURL(in: containerURL))
    }

    /// Writes the payload as the one pending entry, creating the inbox directory
    /// on first use. Returns false on any failure; never throws.
    func write(_ text: String) -> Bool {
        guard let containerURL = self.containerURL else { return false }
        do {
            try FileManager.default.createDirectory(
                at: inboxDirectoryURL(in: containerURL),
                withIntermediateDirectories: true
            )
            try Data(text.utf8).write(to: entryFileURL(in: containerURL), options: .atomic)
            return true
        } catch {
            return false
        }
    }

    // -----------------------------------------------------------------------
    // MARK: - Path grammar
    // -----------------------------------------------------------------------

    /// Resolved on every call — production reads the App Group container here.
    private var containerURL: URL? {
        switch source {
        case let .appGroup(identifier):
            return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
        case let .directory(url):
            return url
        case .unavailable:
            return nil
        }
    }

    private func inboxDirectoryURL(in containerURL: URL) -> URL {
        Self.directoryComponents.reduce(containerURL) { url, component in
            url.appending(path: component, directoryHint: .isDirectory)
        }
    }

    private func entryFileURL(in containerURL: URL) -> URL {
        inboxDirectoryURL(in: containerURL).appending(path: Self.entryName, directoryHint: .notDirectory)
    }
}
