import SwiftUI

struct BottomToolbar: View {
    var isKeyboardPresented = false
    var isEditorEmpty = true
    var onClear: () -> Void = {}
    let onCopy: () -> Void
    let onPaste: () -> Void
    let onIndent: () -> Void
    let onOutdent: () -> Void

    var body: some View {
        HStack(spacing: ChromeMetrics.toolbarItemSpacing) {
            toolbarButton("doc.on.doc", label: String(localized: "Copy"),
                          hint: String(localized: "Copy text"), action: onCopy)
            toolbarButton("doc.on.clipboard", label: String(localized: "Paste"),
                          hint: String(localized: "Paste text"), action: onPaste)
            toolbarButton("decrease.indent", label: String(localized: "Outdent"),
                          hint: String(localized: "Outdent"), action: onOutdent)
            toolbarButton("increase.indent", label: String(localized: "Indent"),
                          hint: String(localized: "Indent"), action: onIndent)
            toolbarButton("eraser", label: String(localized: "Clear editor"),
                          hint: String(localized: "Clear editor"), action: onClear)
                .disabled(isEditorEmpty)
        }
        .padding(.horizontal, ChromeMetrics.capsuleHorizontalPadding)
        .glassEffect(in: .capsule)
    }

    private func toolbarButton(_ symbol: String, label: String, hint: String,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .imageScale(.large)
                .fontWeight(ChromeMetrics.iconScaleWeight)
                .frame(minWidth: 44,
                       maxWidth: isKeyboardPresented ? .infinity : nil,
                       minHeight: isKeyboardPresented ? ChromeMetrics.keyboardToolbarHeight : ChromeMetrics.pillHeight)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .help(hint)
    }
}
