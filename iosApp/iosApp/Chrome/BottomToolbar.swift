import SwiftUI

/// The floating bottom action bar.
///
/// Idle (keyboard down) the capsule carries only two actions — **Paste** and **Clear**. Copy,
/// Outdent and Indent join it while the keyboard is up and leave when it goes, so the resting bar
/// stays two controls wide instead of showing five buttons for a caret that is not there.
///
/// The left-to-right order never changes, whatever the keyboard does:
/// Copy · Paste · Outdent · Indent · Clear.
///
/// Liquid Glass: the bar is ONE capsule of glass (`glassEffect`) — never glass per button, which
/// would stack glass on glass — with the system's neutral label colour for the glyphs (no accent
/// tint), hierarchical symbol rendering for depth, a medium weight to match the glass controls
/// around it, and a quiet press response (see `ToolbarPressStyle`).
struct BottomToolbar: View {
    var isKeyboardPresented = false
    var isEditorEmpty = true
    var onClear: () -> Void = {}
    let onCopy: () -> Void
    let onPaste: () -> Void
    let onIndent: () -> Void
    let onOutdent: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: ChromeMetrics.toolbarItemSpacing) {
            if isKeyboardPresented {
                toolbarButton("doc.on.doc", label: String(localized: "Copy"),
                              hint: String(localized: "Copy text"), action: onCopy)
                    .transition(revealTransition)
            }
            toolbarButton("doc.on.clipboard", label: String(localized: "Paste"),
                          hint: String(localized: "Paste text"), action: onPaste)
            if isKeyboardPresented {
                toolbarButton("decrease.indent", label: String(localized: "Outdent"),
                              hint: String(localized: "Outdent"), action: onOutdent)
                    .transition(revealTransition)
                toolbarButton("increase.indent", label: String(localized: "Indent"),
                              hint: String(localized: "Indent"), action: onIndent)
                    .transition(revealTransition)
            }
            toolbarButton("trash", label: String(localized: "Clear editor"),
                          hint: String(localized: "Clear editor"),
                          isEnabled: !isEditorEmpty, action: onClear)
        }
        .padding(.horizontal, ChromeMetrics.capsuleHorizontalPadding)
        .animation(reduceMotion ? nil : ChromeMetrics.toolbarRevealAnimation, value: isKeyboardPresented)
        .glassEffect(in: .capsule)
    }

    /// The band members appear and disappear from the capsule: a fade with a small scale, so the
    /// capsule reads as growing rather than snapping wider. Reduce Motion keeps the crossfade and
    /// drops the scale.
    private var revealTransition: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.6))
    }

    private func toolbarButton(_ symbol: String, label: String, hint: String,
                               isEnabled: Bool = true,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .symbolRenderingMode(.hierarchical)
                .imageScale(.large)
                .fontWeight(ChromeMetrics.iconScaleWeight)
                .frame(minWidth: 44,
                       maxWidth: isKeyboardPresented ? .infinity : nil,
                       minHeight: isKeyboardPresented ? ChromeMetrics.keyboardToolbarHeight : ChromeMetrics.pillHeight)
                .contentShape(.rect)
        }
        .buttonStyle(ToolbarPressStyle(reduceMotion: reduceMotion, isEnabled: isEnabled))
        .disabled(!isEnabled)
        .accessibilityLabel(label)
        .help(hint)
    }
}

/// The press response inside the glass capsule: the glyph dips and dims under the finger, the way
/// a system glass control answers a touch, then springs back. A disabled control keeps the label
/// dimmed — a custom `ButtonStyle` owns that appearance, the button does not dim itself.
private struct ToolbarPressStyle: ButtonStyle {
    var reduceMotion: Bool
    var isEnabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(isEnabled ? (configuration.isPressed ? 0.55 : 1) : 0.3)
            .scaleEffect(configuration.isPressed ? 0.88 : 1)
            .animation(reduceMotion ? nil : .snappy(duration: 0.16), value: configuration.isPressed)
    }
}
