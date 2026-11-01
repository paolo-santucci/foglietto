#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path
import re
import sys

def source(path):
    return "\n".join(line for line in Path(path).read_text().splitlines()
                     if not line.lstrip().startswith("//"))

chrome = source("iosApp/iosApp/Chrome/ChromeOverlay.swift")
share = source("iosApp/iosApp/Chrome/SystemSharePresenter.swift")
menu = source("iosApp/iosApp/Chrome/MenuBubble.swift")
editor = source("iosApp/iosApp/Editor/NoteEditor.swift")
toolbar = source("iosApp/iosApp/Chrome/BottomToolbar.swift")
content = source("iosApp/iosApp/ContentView.swift")

checks = {
    "bottom toolbar action order": 0 <= toolbar.find('toolbarButton("doc.on.doc"') < toolbar.find('toolbarButton("doc.on.clipboard"') < toolbar.find('toolbarButton("decrease.indent"') < toolbar.find('toolbarButton("increase.indent"'),
    "keyboard dismissal in top chrome": "onCloseKeyboard" not in toolbar and all(token in chrome for token in (
        'UIImage(systemName: "checkmark")', 'closeKeyboardItem.style = .prominent', 'groups.append(closeKeyboardGroup)')),
    "editor inset preserved": "textContainerInset" in editor and not re.search(r"textContainerInset\s*=\s*\.zero", editor),
    "editor fragment padding": "lineFragmentPadding = 0" in editor,
    "real grouped navigation items": all(token in chrome for token in (
        "UINavigationController(rootViewController: editor)", "trailingItemGroups", "[shareItem, menuItem]")),
    "native menu attached to bar item": "menuItem.menu = NativeEditorMenu.make" in chrome
        and "MenuPopoverController" not in chrome and "presentMenu()" not in chrome,
    "share popover anchored to bar item": all(token in share for token in (
        "from source: UIBarButtonItem", "popover.sourceItem = source", "modalPresentationStyle = .popover")),
    "system owns presentation glass": not re.search(
        r"glassEffect|GlassEffectContainer|dropCarrier|journeyTask|shareBlob|ShareSourceAnchor|Task\.sleep",
        chrome + menu + share),
    "system owns menu motion": "UIMenu(" in menu and "withAnimation" not in menu
        and "UIAccessibility.isReduceMotionEnabled" in share,
    "native popup submenus refreshed on open": "UIDeferredMenuElement.uncached" in menu
        and "isRecoveryExpanded" not in menu and "isAboutExpanded" not in menu,
    "panels follow pill frame": "frame(in: container)" in chrome
        and "popover.popoverLayoutMargins" in chrome and "delegate: self" in chrome,
    "native compact-width presentation": "adaptivePresentationStyle" in chrome and ".none" in chrome
        and "adaptivePresentationStyle" in share and ".none" in share,
    "keyboard-safe bottom toolbar": ".safeAreaInset(edge: .bottom" in content,
    "system owns menu sizing and scrolling": "UIDeferredMenuElement.uncached" in menu
        and "UIHostingController" not in menu and "popover" not in menu,
}
for label, passed in checks.items():
    print(f"{'PASS' if passed else 'FAIL'}: {label}")
print(f"Native chrome gate: {sum(checks.values())}/{len(checks)} passed")
sys.exit(0 if all(checks.values()) else 1)
PY

bash tool/m4_gate.sh
