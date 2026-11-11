import SwiftUI
import UIKit
import Observation
import shared

@Observable
final class CoordinatorBox {
    weak var coordinator: NoteEditor.Coordinator?
    var canUndo = false
}

struct ChromeOverlay<Content: View>: UIViewControllerRepresentable {
    let text: String
    let menuVM: MenuViewModel
    let canUndo: Bool
    let onUndo: () -> Void
    var isKeyboardPresented = false
    var onCloseKeyboard: () -> Void = {}
    @ViewBuilder let content: () -> Content

    func makeCoordinator() -> ChromeBarCoordinator {
        ChromeBarCoordinator()
    }

    func makeUIViewController(context: Context) -> UINavigationController {
        let editor = UIHostingController(rootView: content())
        let navigation = UINavigationController(rootViewController: editor)
        navigation.view.tintColor = UIColor(named: "AccentColor")
        navigation.navigationBar.prefersLargeTitles = false
        context.coordinator.attach(to: navigation)
        context.coordinator.update(text: text, menuVM: menuVM, canUndo: canUndo, onUndo: onUndo)
        context.coordinator.updateKeyboard(isPresented: isKeyboardPresented, onClose: onCloseKeyboard)
        return navigation
    }

    func updateUIViewController(_ navigation: UINavigationController, context: Context) {
        (navigation.viewControllers.first as? UIHostingController<Content>)?.rootView = content()
        context.coordinator.update(text: text, menuVM: menuVM, canUndo: canUndo, onUndo: onUndo)
        context.coordinator.updateKeyboard(isPresented: isKeyboardPresented, onClose: onCloseKeyboard)
    }

    static func dismantleUIViewController(_ navigation: UINavigationController, coordinator: ChromeBarCoordinator) {
        navigation.dismiss(animated: false)
    }
}

@MainActor
final class ChromeBarCoordinator: NSObject, UIPopoverPresentationControllerDelegate {
    private weak var navigation: UINavigationController?
    private var text = ""
    private var menuVM: MenuViewModel?
    private var onUndo: () -> Void = {}
    private var showsUndo = false
    private var showsKeyboardDismissal = false
    private var onCloseKeyboard: () -> Void = {}

    private lazy var closeKeyboardItem = UIBarButtonItem(
        title: String(localized: "Hide keyboard"),
        image: UIImage(systemName: "checkmark"),
        primaryAction: UIAction { [weak self] _ in self?.onCloseKeyboard() }
    )
    private lazy var closeKeyboardGroup = UIBarButtonItemGroup(
        barButtonItems: [closeKeyboardItem], representativeItem: nil
    )

    private lazy var shareItem = UIBarButtonItem(
        title: String(localized: "Share"),
        image: UIImage(systemName: "square.and.arrow.up"),
        primaryAction: UIAction { [weak self] _ in self?.presentShare() }
    )

    private lazy var menuItem = UIBarButtonItem(
        title: String(localized: "Menu"),
        image: UIImage(systemName: "ellipsis"),
        menu: UIMenu(children: [])
    )

    private lazy var undoItem = UIBarButtonItem(
        title: String(localized: "Undo"),
        image: UIImage(systemName: "arrow.uturn.backward"),
        primaryAction: UIAction { [weak self] _ in self?.onUndo() }
    )

    private lazy var shareMenuGroup = UIBarButtonItemGroup(
        barButtonItems: [shareItem, menuItem], representativeItem: nil
    )
    private lazy var undoGroup = UIBarButtonItemGroup(barButtonItems: [undoItem], representativeItem: nil)

    func attach(to navigation: UINavigationController) {
        self.navigation = navigation
        shareItem.accessibilityLabel = String(localized: "Share")
        menuItem.accessibilityLabel = String(localized: "Menu")
        undoItem.accessibilityLabel = String(localized: "Undo")
        shareItem.accessibilityHint = String(localized: "Share text")
        menuItem.accessibilityHint = String(localized: "Open menu")
        closeKeyboardItem.accessibilityLabel = String(localized: "Hide keyboard")
        closeKeyboardItem.style = .prominent
        closeKeyboardItem.tintColor = UIColor(named: "AccentColor")
        updateGroups()
    }

    func update(text: String, menuVM: MenuViewModel, canUndo: Bool, onUndo: @escaping () -> Void) {
        self.text = text
        if self.menuVM !== menuVM {
            self.menuVM = menuVM
            menuItem.menu = NativeEditorMenu.make(menuVM: menuVM)
        }
        self.onUndo = onUndo
        // Share enablement is the shared predicate — no local trim (FR-08 / FR-09).
        shareItem.isEnabled = ShareGateKt.canShare(text: text)
        if showsUndo != canUndo {
            showsUndo = canUndo
            updateGroups()
        }
    }

    func updateKeyboard(isPresented: Bool, onClose: @escaping () -> Void) {
        onCloseKeyboard = onClose
        guard showsKeyboardDismissal != isPresented else { return }
        showsKeyboardDismissal = isPresented
        updateGroups()
    }

    private func updateGroups() {
        var groups = showsUndo ? [undoGroup, shareMenuGroup] : [shareMenuGroup]
        if showsKeyboardDismissal { groups.append(closeKeyboardGroup) }
        navigation?.viewControllers.first?.navigationItem.trailingItemGroups = groups
    }

    private func presentShare() {
        guard let navigation, shareItem.isEnabled else { return }
        SystemSharePresenter.present(
            text: text,
            from: shareItem,
            in: navigation,
            delegate: self,
            configurePopover: alignPopover
        )
    }

    func popoverPresentationController(
        _ popoverPresentationController: UIPopoverPresentationController,
        willRepositionPopoverTo rect: UnsafeMutablePointer<CGRect>,
        in view: AutoreleasingUnsafeMutablePointer<UIView>
    ) {
        let presented = popoverPresentationController.presentedViewController
        guard !presented.isBeingPresented, !presented.isBeingDismissed,
              presented.transitionCoordinator == nil else { return }
        alignPopover(popoverPresentationController)
    }

    private func alignPopover(_ popover: UIPopoverPresentationController) {
        guard let navigation, let referenceView = navigation.view else { return }
        let container = popover.containerView ?? referenceView
        let frames = [shareItem, menuItem].compactMap { $0.frame(in: container) }
        guard frames.count == 2 else { return }
        let pillFrame = frames.reduce(CGRect.null) { $0.union($1) }
        let inset = ChromeMetrics.chromeHorizontalInset
        popover.popoverLayoutMargins = PillPopoverAlignment.margins(
            pillFrame: pillFrame, bounds: container.bounds,
            minimum: UIEdgeInsets(top: 0, left: inset, bottom: inset, right: 0)
        )
    }

    func adaptivePresentationStyle(
        for controller: UIPresentationController,
        traitCollection: UITraitCollection
    ) -> UIModalPresentationStyle {
        .none
    }
}
