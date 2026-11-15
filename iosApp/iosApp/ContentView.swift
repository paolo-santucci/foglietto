import SwiftUI
import UIKit
import shared

struct ContentView: View {
    let viewModel: NoteViewModel
    let recovery: RecoveryRepository
    let lifecycle: LifecycleSaveCoordinator

    /// The ONE inbound drainer built & already cold-drained at the composition root
    /// (FR-16, FR-17; §5.1.5). `.active` reuses it for the warm drain — same instance,
    /// so the cold and warm effect order can never diverge.
    let drainer: ShareInboxDrainer

    @Environment(\.scenePhase) private var scenePhase
    @State private var coordinatorBox = CoordinatorBox()
    @State private var menuVM: MenuViewModel
    @State private var isKeyboardPresented = false

    init(
        viewModel: NoteViewModel,
        recovery: RecoveryRepository,
        lifecycle: LifecycleSaveCoordinator,
        drainer: ShareInboxDrainer
    ) {
        self.viewModel = viewModel
        self.recovery = recovery
        self.lifecycle = lifecycle
        self.drainer = drainer
        _menuVM = State(wrappedValue: MenuViewModel(recovery: recovery, viewModel: viewModel))
    }

    var body: some View {
        GeometryReader { geometry in
            ChromeOverlay(
                text: viewModel.text,
                menuVM: menuVM,
                canUndo: coordinatorBox.canUndo,
                onUndo: { [coordinatorBox] in coordinatorBox.coordinator?.undo() },
                isKeyboardPresented: isKeyboardPresented,
                onCloseKeyboard: { [coordinatorBox] in coordinatorBox.coordinator?.closeKeyboard() }
            ) {
                NoteEditor(viewModel: viewModel, coordinatorBox: coordinatorBox)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .ignoresSafeArea()
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        bottomToolbar(bottomSafeAreaInset: geometry.safeAreaInsets.bottom)
                    }
            }
            .ignoresSafeArea(.container)
        }
        .preferredColorScheme(nil)
        .tint(Color("AccentColor"))
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            isKeyboardPresented = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            isKeyboardPresented = false
        }
        .onChange(of: scenePhase) { _, newPhase in
            ContentView.dispatchScenePhase(newPhase, to: lifecycle, drainer: drainer)
        }
    }

    private func bottomToolbar(bottomSafeAreaInset: CGFloat) -> some View {
        HStack {
            BottomToolbar(
                isKeyboardPresented: isKeyboardPresented,
                isEditorEmpty: viewModel.text.isEmpty,
                onClear: { [coordinatorBox, recovery] in
                    coordinatorBox.coordinator?.clearEditor(savingTo: recovery)
                },
                onCopy: { [coordinatorBox] in coordinatorBox.coordinator?.copyToPasteboard() },
                onPaste: { [coordinatorBox] in coordinatorBox.coordinator?.pasteAtCaret() },
                onIndent: { [coordinatorBox, viewModel] in
                    guard let textView = coordinatorBox.coordinator?.textView else { return }
                    let result = viewModel.indent(in: textView.selectedRange, of: textView.text ?? "")
                    coordinatorBox.coordinator?.applyIndentResult(text: result.text, range: result.selection)
                },
                onOutdent: { [coordinatorBox, viewModel] in
                    guard let textView = coordinatorBox.coordinator?.textView else { return }
                    let result = viewModel.outdent(in: textView.selectedRange, of: textView.text ?? "")
                    coordinatorBox.coordinator?.applyIndentResult(text: result.text, range: result.selection)
                }
            )
            if !isKeyboardPresented { Spacer() }
        }
        .padding(.leading, isKeyboardPresented ? ChromeMetrics.keyboardToolbarSideInset : ChromeMetrics.toolbarFloatingPadding)
        .padding(.trailing, isKeyboardPresented ? ChromeMetrics.keyboardToolbarSideInset : 0)
        .padding(.bottom, isKeyboardPresented ? ChromeMetrics.keyboardToolbarGap : -min(bottomSafeAreaInset, ChromeMetrics.toolbarBottomOverhang))
    }

    /// Routes a ScenePhase transition to the lifecycle coordinator, plus the warm
    /// inbound drain on `.active` (FR-14, FR-17, FR-18, EC-17/EC-18; §5.1.5).
    ///
    /// `.active` order is load-bearing: `onActive()` resets the ephemeral buffer FIRST,
    /// then `drainer?.drain()` seeds it. Never the reverse — a seed applied before the
    /// reset is wiped (assessment C-2). Both mutations land in this one synchronous
    /// main-actor turn, so the very next `updateUIView` already shows the seed (EC-19).
    ///
    /// `drainer` is defaulted so the pre-existing two-argument call sites still compile;
    /// production always passes the single instance stored at the composition root.
    ///
    /// - Parameters:
    ///   - phase: the new `ScenePhase`.
    ///   - coordinator: the session lifecycle/save coordinator.
    ///   - drainer: the warm inbound drainer; `nil` means "reset only".
    @MainActor
    static func dispatchScenePhase(
        _ phase: ScenePhase,
        to coordinator: LifecycleSaveCoordinator,
        drainer: ShareInboxDrainer? = nil
    ) {
        switch phase {
        case .background:
            coordinator.onBackground()
        case .active:
            coordinator.onActive()
            drainer?.drain()
        case .inactive:
            coordinator.onInactive()
        @unknown default:
            break
        }
    }
}
