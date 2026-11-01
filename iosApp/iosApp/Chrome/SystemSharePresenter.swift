import UIKit

@MainActor
enum SystemSharePresenter {
    static func present(text: String, from source: UIBarButtonItem, in presenting: UIViewController,
                        delegate: UIPopoverPresentationControllerDelegate? = nil,
                        configurePopover: (UIPopoverPresentationController) -> Void = { _ in }) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              presenting.view.window != nil,
              presenting.presentedViewController == nil else { return }
        let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        controller.modalPresentationStyle = .popover
        guard let popover = controller.popoverPresentationController else { return }
        popover.sourceItem = source
        popover.delegate = delegate ?? SharePopoverAdaptivity.shared
        configurePopover(popover)
        presenting.present(controller, animated: !UIAccessibility.isReduceMotionEnabled)
    }
}

enum PillPopoverAlignment {
    static func margins(pillFrame: CGRect, bounds: CGRect, minimum: UIEdgeInsets) -> UIEdgeInsets {
        guard !pillFrame.isEmpty, !pillFrame.isNull, !pillFrame.isInfinite else { return minimum }
        return UIEdgeInsets(
            top: max(minimum.top, pillFrame.minY - bounds.minY),
            left: minimum.left,
            bottom: minimum.bottom,
            right: max(minimum.right, bounds.maxX - pillFrame.maxX)
        )
    }
}

private final class SharePopoverAdaptivity: NSObject, UIPopoverPresentationControllerDelegate {
    static let shared = SharePopoverAdaptivity()

    func adaptivePresentationStyle(
        for controller: UIPresentationController,
        traitCollection: UITraitCollection
    ) -> UIModalPresentationStyle {
        .none
    }
}
