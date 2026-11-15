// ShareViewController.swift
// iosApp/ShareExtension
//
// The Share Extension's entire lifecycle: read the shared item, write it to the
// App Group inbox, ask the system to bring Foglietto forward, complete. It
// presents no Foglietto surface of any kind — the share sheet's own chrome is
// the only UI (FR-12, FR-13, FR-14, FR-15, NFR-06).
//
// Contract (§5.1.6):
//   input      extensionContext.inputItems in order; per attachment prefer
//              "public.plain-text", then "public.text", then the item's own
//              attributedContentText when no attachment yields a payload
//   convert    String as-is; NSAttributedString.string; Data as strict UTF-8
//   deliver    ShareInbox().write(payload) — the RAW payload, no normalization
//   hand-off   on a successful write, one best-effort `openURL:` dispatch that
//              brings the host app forward (FR-29); a failed dispatch changes
//              nothing — the payload is already in the inbox
//   otherwise  nil, an error, or an unexpected class completes WITHOUT writing
//   completion completeRequest(returningItems:) is guarded by a one-shot Bool,
//              so it runs exactly once on every path and is the only exit
//
// LAUNCH (FR-29). iOS publishes NO supported API for a Share Extension to open
// its containing app: `NSExtensionContext.open(_:)` is documented for the Today
// and iMessage extension points only, and the legacy `openURL:` responder trick
// is a silent no-op since iOS 18 ("BUG IN CLIENT OF UIKIT … Force returning
// false"). What still works — and what Chromium ships in
// `ios/chrome/common/extension_open_url.mm` — is dispatching the CURRENT
// selector, `openURL:options:completionHandler:`, to the first responder in the
// chain that implements it. That is undocumented behaviour, so it is strictly
// best-effort and never load-bearing: the inbox write happens first and the host
// drains it on its next activation if the dispatch does nothing.
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

import ObjectiveC
import UIKit

/// The no-UI share target. `NSExtensionPrincipalClass` in `Info.plist` names it
/// as `$(PRODUCT_MODULE_NAME).ShareViewController`, so it is deliberately not
/// `final`: the runtime looks the class up by name.
@MainActor
class ShareViewController: UIViewController {

    /// The only two UTIs this extension accepts, in preference order (§5.1.6).
    private static let acceptedIdentifiers = ["public.plain-text", "public.text"]

    /// The custom scheme the host declares in `iosApp/Info.plist`. Opening it is
    /// only a launch signal: the payload always travels through the App Group
    /// inbox, never through the URL.
    private static let hostAppURLString = "foglietto://share"

    /// The non-deprecated UIKit selector for opening a URL. Written as a string
    /// on purpose — the symbol is never referenced directly, and the legacy
    /// `openURL:` selector is a no-op since iOS 18.
    private static let openURLSelectorName = "openURL:options:completionHandler:"

    /// The providers still to try, in the order the system handed them to us.
    private var remaining: [(provider: NSItemProvider, identifier: String)] = []

    /// Text an input item carries outside its attachments
    /// (`NSExtensionItem.attributedContentText`). Some host apps publish the
    /// shared text there instead of registering an item provider, so it is the
    /// last resort after every attachment has failed to produce a payload.
    private var attributedContentText: String?

    /// One-shot completion guard: `completeRequest` runs exactly once, so a
    /// late second callback cannot double-complete the share request.
    private var hasCompleted = false

    override func viewDidLoad() {
        super.viewDidLoad()
        let inputItems = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        attributedContentText = Self.contentText(in: inputItems)
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
    /// out of providers falls back to the item's own content text, then
    /// completes without writing when that is absent too (FR-15, OQ-05).
    private func loadNextCandidate() {
        guard !remaining.isEmpty else {
            if let attributedContentText, !attributedContentText.isEmpty {
                deliver(attributedContentText)
            } else {
                complete()
            }
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

    /// The first input item's own text, when the host app published the share as
    /// `attributedContentText` instead of an item provider. Empty strings are
    /// not a payload; the raw value is never trimmed or normalized here.
    private static func contentText(in inputItems: [NSExtensionItem]) -> String? {
        for item in inputItems {
            if let text = item.attributedContentText?.string, !text.isEmpty {
                return text
            }
        }
        return nil
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

    /// Writes the raw payload to the one-slot inbox, then asks the system to
    /// bring Foglietto forward, then completes. A failed write (unavailable
    /// container included) is silent: same exit as success (FR-15, FR-20,
    /// EC-14) and no launch is attempted, because there would be nothing to
    /// show.
    private func deliver(_ payload: String) {
        if ShareInbox().write(payload) {
            openHostApp()
        }
        complete()
    }

    // -----------------------------------------------------------------------
    // MARK: - Launch (FR-29, best-effort)
    // -----------------------------------------------------------------------

    /// Asks the system to foreground the containing app.
    ///
    /// Walks this controller's responder chain for the first responder that
    /// implements the modern `openURL:options:completionHandler:` selector and
    /// dispatches the host's scheme to it. In an app extension that responder is
    /// the UIKit object the system uses instead of the (unavailable)
    /// `UIApplication.shared`, which is why the call has to go through its
    /// implementation rather than through a typed Swift call.
    ///
    /// Returns nothing on purpose: there is no supported way to learn whether the
    /// launch happened, and the payload is in the inbox either way — the host
    /// drains it on its next activation when this does nothing (FR-15/F-20).
    /// The scheme is the one `iosApp/Info.plist` declares (`foglietto`); the text
    /// itself never travels in the URL.
    private func openHostApp() {
        guard let url = URL(string: Self.hostAppURLString) else { return }
        let selector = NSSelectorFromString(Self.openURLSelectorName)

        var responder: UIResponder? = self
        while let current = responder {
            if current.responds(to: selector) {
                Self.sendOpenURL(to: current, selector: selector, url: url)
                return
            }
            responder = current.next
        }
    }

    /// Invokes `openURL:options:completionHandler:` through its implementation.
    ///
    /// Swift cannot call a three-argument selector and `NSInvocation` is not
    /// available in Swift (Chromium uses NSInvocation from Objective-C for the
    /// same call), so the IMP is cast to a C function with the selector's exact
    /// signature. The trailing arguments are the empty options dictionary and a
    /// nil completion handler — the values UIKit expects from a caller that does
    /// not track the result.
    private static func sendOpenURL(to target: UIResponder, selector: Selector, url: URL) {
        typealias OpenURL = @convention(c) (
            AnyObject,
            Selector,
            NSURL,
            NSDictionary?,
            AnyObject?
        ) -> Void

        guard let method = class_getInstanceMethod(type(of: target), selector) else { return }
        let implementation = method_getImplementation(method)
        let openURL = unsafeBitCast(implementation, to: OpenURL.self)
        openURL(target, selector, url as NSURL, nil, nil)
    }

    // -----------------------------------------------------------------------
    // MARK: - Completion
    // -----------------------------------------------------------------------

    /// The single exit of this controller.
    private func complete() {
        guard !hasCompleted else { return }
        hasCompleted = true
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}
