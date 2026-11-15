// ShareViewController.swift
// iosApp/ShareExtension
//
// The Share Extension's entire lifecycle: read the shared item, write it to the
// App Group inbox, complete. It presents no Foglietto surface of any kind — the
// share sheet's own chrome is the only UI (FR-12, FR-13, FR-14, FR-15, NFR-06).
//
// Contract (§5.1.6):
//   input      extensionContext.inputItems in order; per attachment prefer
//              "public.plain-text", then "public.text"
//   convert    String as-is; NSAttributedString.string; Data as strict UTF-8
//   deliver    ShareInbox().write(payload) — the RAW payload, no normalization
//   otherwise  nil, an error, or an unexpected class completes WITHOUT writing
//   completion completeRequest(returningItems:) is guarded by a one-shot Bool,
//              so it runs exactly once on every path and is the only exit
//
// HARD ABSENCES (FR-14, D1, FR-27): the extension imports no KMP module, links
// no Kotlin framework, normalizes nothing, writes no recovery file, enumerates
// no App Group directory, presents no UI and logs nothing — no timer, no retry.
// Normalization belongs to the host (§5.1.1); writing raw bytes is what keeps
// both platforms byte-identical.
//
// Isolation: NSItemProvider loads asynchronously and its completion handler is
// not guaranteed to run on the main actor, so the completion body touches no
// main-actor state. It decodes to a `String?` (Sendable) first and hops back
// with one `Task { @MainActor in }` — a scheduling hop, never a delay.
//
// Spec refs: FR-12, FR-13, FR-14, FR-15, FR-26, FR-27, NFR-06; §5.1.6, §5.2, D1.
// CI-verified on macos-26: `build_ios` is the first thing that compiles it.

import UIKit

/// The no-UI share target. `NSExtensionPrincipalClass` in `Info.plist` names it
/// as `$(PRODUCT_MODULE_NAME).ShareViewController`, so it is deliberately not
/// `final`: the runtime looks the class up by name.
@MainActor
class ShareViewController: UIViewController {

    /// The only two UTIs this extension accepts, in preference order (§5.1.6).
    private static let acceptedIdentifiers = ["public.plain-text", "public.text"]

    /// The providers still to try, in the order the system handed them to us.
    private var remaining: [(provider: NSItemProvider, identifier: String)] = []

    /// One-shot completion guard: `completeRequest` runs exactly once, so a
    /// late second callback cannot double-complete the share request.
    private var hasCompleted = false

    override func viewDidLoad() {
        super.viewDidLoad()
        let inputItems = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        remaining = Self.textCandidates(in: inputItems)
        loadNextCandidate()
    }

    // -----------------------------------------------------------------------
    // MARK: - Capture
    // -----------------------------------------------------------------------

    /// Every text attachment of every input item, in order, paired with the
    /// identifier to load it as. Attachments that offer neither UTI are skipped
    /// here, so the loader only ever sees loadable candidates.
    private static func textCandidates(
        in inputItems: [NSExtensionItem]
    ) -> [(provider: NSItemProvider, identifier: String)] {
        var candidates: [(provider: NSItemProvider, identifier: String)] = []
        for item in inputItems {
            for provider in item.attachments ?? [] {
                guard let identifier = acceptedIdentifiers.first(
                    where: provider.hasItemConformingToTypeIdentifier
                ) else {
                    continue
                }
                candidates.append((provider, identifier))
            }
        }
        return candidates
    }

    /// Loads the first still-untried provider; an unusable answer (nil, an
    /// error, an unexpected class) falls through to the next one, and running
    /// out of providers completes without writing (FR-15, OQ-05).
    private func loadNextCandidate() {
        guard !remaining.isEmpty else {
            complete()
            return
        }
        let candidate = remaining.removeFirst()
        candidate.provider.loadItem(
            forTypeIdentifier: candidate.identifier,
            options: nil
        ) { item, error in
            let payload = Self.payload(from: item, error: error)
            Task { @MainActor in
                if let payload {
                    self.deliver(payload)
                } else {
                    self.loadNextCandidate()
                }
            }
        }
    }

    /// Converts one loaded item to the payload string, or nil when the item is
    /// not text at all. Strict UTF-8: undecodable bytes are not a payload.
    ///
    /// `nonisolated` on purpose: `NSItemProvider`'s completion handler is not
    /// guaranteed to run on the main actor, and this conversion touches no actor
    /// state, so it must be callable from either side of the hop.
    private nonisolated static func payload(from item: Any?, error: Error?) -> String? {
        guard error == nil, let item else { return nil }
        if let text = item as? String {
            return text
        }
        if let attributed = item as? NSAttributedString {
            return attributed.string
        }
        if let data = item as? Data {
            return String(data: data, encoding: .utf8)
        }
        return nil
    }

    // -----------------------------------------------------------------------
    // MARK: - Delivery
    // -----------------------------------------------------------------------

    /// Writes the raw payload to the one-slot inbox, then completes. A failed
    /// write (unavailable container included) is silent: same exit as success
    /// (FR-15, FR-20, EC-14).
    private func deliver(_ payload: String) {
        _ = ShareInbox().write(payload)
        complete()
    }

    /// The single exit of this controller.
    private func complete() {
        guard !hasCompleted else { return }
        hasCompleted = true
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}
