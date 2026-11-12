import UIKit

@MainActor
enum NativeEditorMenu {
    /// About first, recents underneath, all flat.
    ///
    /// **About at the top is the pin.** A `UIMenu` is one system scroll view, so nothing can be
    /// anchored to a screen edge; but UIKit opens a menu at its first row, so putting About first
    /// keeps it on screen however long the recent-notes list grows. It stays a real submenu.
    ///
    /// The "Recent notes" label keeps the look of the retired submenu row (clock glyph + title)
    /// but is inert: `.disabled` renders it as a header rather than an action, so the rows under
    /// it restore in one tap (FR-12 still holds — the deferred element resolves a fresh
    /// `RecoveryListViewModel` on every open).
    static func make(menuVM: MenuViewModel) -> UIMenu {
        let about = UIMenu(
            title: String(localized: "About"), image: UIImage(systemName: "info.circle"),
            children: aboutActions()
        )
        let header = UIAction(
            title: String(localized: "Recent notes"),
            image: UIImage(systemName: "clock.arrow.trianglehead.counterclockwise.rotate.90"),
            attributes: .disabled
        ) { _ in }
        let recents = UIDeferredMenuElement.uncached { completion in
            completion(recoveryActions(menuVM: menuVM))
        }
        return UIMenu(children: [about, header, recents])
    }

    static func recoveryActions(menuVM: MenuViewModel) -> [UIMenuElement] {
        let model = menuVM.makeRecoveryListViewModel()
        model.refresh()
        guard !model.rows.isEmpty else {
            return [UIAction(title: String(localized: "No recent notes."), attributes: .disabled) { _ in }]
        }
        return model.rows.map { row in
            let action = UIAction(title: row.previewTitle, image: UIImage(systemName: "doc.text")) { _ in
                model.select(path: row.path)
            }
            action.subtitle = row.dateSubtitle
            action.accessibilityLabel = String(
                format: String(localized: "Restore note: %@, saved %@"), row.previewTitle, row.dateSubtitle
            )
            action.accessibilityHint = String(localized: "Restore this note")
            return action
        }
    }

    private static func aboutActions() -> [UIMenuElement] {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let identity = UIAction(title: "Foglietto", image: UIImage(systemName: "app"), attributes: .disabled) { _ in }
        identity.subtitle = [version, "Paolo Santucci", "GPL-3.0"].filter { !$0.isEmpty }.joined(separator: " · ")
        return [
            identity,
            linkAction(title: String(localized: "Report an issue"), symbol: "exclamationmark.bubble",
                       address: "https://github.com/paolo-santucci/foglietto/issues"),
            linkAction(title: String(localized: "Website"), symbol: "globe",
                       address: "https://github.com/paolo-santucci/foglietto")
        ]
    }

    private static func linkAction(title: String, symbol: String, address: String) -> UIAction {
        UIAction(title: title, image: UIImage(systemName: symbol)) { _ in
            guard let url = URL(string: address) else { return }
            UIApplication.shared.open(url)
        }
    }
}
