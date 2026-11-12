import UIKit

@MainActor
enum NativeEditorMenu {
    /// Flat top level: the recent notes are the menu's own rows — no "Recent notes"
    /// submenu — and the About submenu closes the list underneath them.
    ///
    /// The recents stay a deferred element so the rows are still resolved on every
    /// open (fresh `RecoveryListViewModel` per presentation, FR-12); the deferred
    /// element's children are inserted in place, which is what keeps them above About.
    static func make(menuVM: MenuViewModel) -> UIMenu {
        let recents = UIDeferredMenuElement.uncached { completion in
            completion(recoveryActions(menuVM: menuVM))
        }
        let about = UIMenu(
            title: String(localized: "About"), image: UIImage(systemName: "info.circle"),
            children: aboutActions()
        )
        return UIMenu(children: [recents, about])
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
