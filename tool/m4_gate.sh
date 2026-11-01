#!/usr/bin/env bash
# tool/m4_gate.sh — M4 Liquid Glass Chrome structural verification gate
#
# Asserts the full M4 target tree in one runnable command.  Run from the repo
# root: bash tool/m4_gate.sh
#
# Exit 0 only when ALL checks (1)–(13) pass.
# Each check prints a labelled PASS/FAIL line; the script exits 1 on any FAIL.
#
# Design rules:
#   - Every check is load-bearing: when the Chrome/ surfaces and CM fixes land,
#     each asserted invariant must be verifiable / already-satisfied.
#   - xcodebuild / plutil / assetutil are macOS-only and are NOT used here.
#     Those validations remain in ios.yml CI steps.
#   - No network access; minimal Gradle invocation (check 9 only).
#   - Chrome/ not-yet-existing is handled per-check: checks scoped to Chrome/
#     are vacuously PASS when the directory does not exist (the files haven't
#     landed yet — that is the correct pre-M4 state).  Checks 1/4/6/10 target
#     files that already exist and run unconditionally now.
#   - Mirrors the structure and idioms of tool/m3_gate.sh exactly.
#     Presence checks use git ls-files + grep; absence checks use
#     grep -RIn directly on the target path.
#
# Spec refs: NFR-01/02/05/06, FR-02/07/09/15/22/24/25; spec §7.1 gate items,
#            OQ-16.

set -uo pipefail

PASS=0
FAIL=0

# ---------------------------------------------------------------------------
# result <label> <ok|fail> [detail]
#   Print a labelled PASS/FAIL line.  Accumulate counts; call finish() at end.
# ---------------------------------------------------------------------------
result() {
    local label="$1"
    local status="$2"
    local detail="${3:-}"
    if [[ "$status" == "ok" ]]; then
        echo "  PASS: $label"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $label${detail:+ — $detail}" >&2
        FAIL=$((FAIL + 1))
    fi
}

finish() {
    echo ""
    echo "=== M4 gate: $PASS passed, $FAIL failed ==="
    if [[ "$FAIL" -gt 0 ]]; then
        exit 1
    fi
    exit 0
}

echo "=== M4 gate: starting structural verification ==="
echo ""

# ---------------------------------------------------------------------------
# 1. glass-off-content
#
# NFR-01/FR-02: .glassEffect, GlassEffectContainer, .glassButton, .glassToolbar*
# must be absent from the editor content layer (NoteEditor.swift).
# Glass belongs exclusively on the Chrome/ layer.
#
# Only runs when NoteEditor.swift exists (tracked file).  If the file is
# absent, the check still passes vacuously (nothing to violate).
# ---------------------------------------------------------------------------
echo "[1] glass-off-content: no glass in iosApp/iosApp/Editor/NoteEditor.swift ..."

be_file="iosApp/iosApp/Editor/NoteEditor.swift"
if git ls-files --error-unmatch -- "$be_file" > /dev/null 2>&1; then
    glass_content_hits=$(grep -In "glassEffect\|GlassEffectContainer\|glassButton\|glassToolbar" "$be_file" 2>/dev/null || true)
    if [[ -z "$glass_content_hits" ]]; then
        result "glass-off-content" ok
    else
        result "glass-off-content" fail "glass symbol(s) found in $be_file (NFR-01/FR-02):
$glass_content_hits"
    fi
else
    result "glass-off-content" ok "(vacuous: $be_file not yet tracked)"
fi

# ---------------------------------------------------------------------------
# 2. glass-scoped-to-Chrome
#
# NFR-01/FR-02: .glassEffect, GlassEffectContainer, or any .glass* must not
# appear in any .swift file OUTSIDE iosApp/iosApp/Chrome/.
#
# Strategy:
#   a) Grep all tracked .swift files under iosApp/ for glass symbols.
#   b) Remove any hits that come from under Chrome/.
#   c) Any remaining hits are violations.
# ---------------------------------------------------------------------------
echo "[2] glass-scoped-to-Chrome: no glass in any .swift outside iosApp/iosApp/Chrome/ ..."

# Collect all tracked .swift files under iosApp/
all_swift_files=$(git ls-files -- "iosApp/" | grep '\.swift$' || true)

if [[ -n "$all_swift_files" ]]; then
    # grep those files for glass symbols; filter OUT Chrome/ hits
    glass_outside_hits=$(echo "$all_swift_files" | xargs grep -In "glassEffect\|GlassEffectContainer\|\.glass" 2>/dev/null | grep -v "^iosApp/iosApp/Chrome/" || true)
else
    glass_outside_hits=""
fi

if [[ -z "$glass_outside_hits" ]]; then
    result "glass-scoped-to-Chrome" ok
else
    result "glass-scoped-to-Chrome" fail "glass symbol(s) found outside Chrome/ (NFR-01/FR-02):
$glass_outside_hits"
fi

# ---------------------------------------------------------------------------
# 3. no-glass-fallback-branch
#
# NFR-02: min iOS 26.0 — no 'if #available(iOS' guards inside Chrome/.
# If Chrome/ does not yet exist, vacuously PASS.
# ---------------------------------------------------------------------------
echo "[3] no-glass-fallback-branch: no 'if #available(iOS' inside Chrome/ ..."

chrome_dir="iosApp/iosApp/Chrome"
if [[ -d "$chrome_dir" ]]; then
    fallback_hits=$(grep -RIn "if #available(iOS" "$chrome_dir/" 2>/dev/null || true)
    if [[ -z "$fallback_hits" ]]; then
        result "no-glass-fallback-branch" ok
    else
        result "no-glass-fallback-branch" fail "'if #available(iOS' in Chrome/ (NFR-02):
$fallback_hits"
    fi
else
    result "no-glass-fallback-branch" ok "(vacuous: Chrome/ does not exist yet)"
fi

# ---------------------------------------------------------------------------
# 4. single-settings-instance
#
# NFR-05/FR-03: exactly 1 createIosSettingsRepository() call in iosAppApp.swift;
# 0 calls in any other .swift file.
#
# This runs unconditionally — iosAppApp.swift is an existing tracked file.
# The pre-M4 tree has 2 calls (CM-1 bug), so this check FAILS now.
# After TASK-04 collapses it to 1, it will PASS.
# ---------------------------------------------------------------------------
echo "[4] single-settings-instance: exactly 1 createIosSettingsRepository() in iosAppApp.swift; 0 elsewhere ..."

app_file="iosApp/iosApp/iosAppApp.swift"
app_count=0
if [[ -f "$app_file" ]]; then
    # Count only non-comment lines (exclude // comment lines)
    app_count=$(grep -c "createIosSettingsRepository()" "$app_file" 2>/dev/null || echo "0")
    # Subtract comment-line hits to get code-level call count
    app_comment_count=$(grep -c "createIosSettingsRepository()" "$app_file" 2>/dev/null | xargs -I{} sh -c 'true' || true)
    # Recount excluding comment lines
    app_count=$(grep "createIosSettingsRepository()" "$app_file" 2>/dev/null | grep -Ev '^[[:space:]]*//' | wc -l | tr -d ' ' || echo "0")
fi

# Check all OTHER swift files for the symbol (non-comment lines)
other_swift_hits=$(git ls-files -- "iosApp/" | grep '\.swift$' | grep -v "iosApp/iosApp/iosAppApp.swift" | xargs grep -In "createIosSettingsRepository()" 2>/dev/null | grep -Ev '^[^:]+:[0-9]+:[[:space:]]*//' || true)

if [[ "$app_count" -eq 1 ]] && [[ -z "$other_swift_hits" ]]; then
    result "single-settings-instance" ok
elif [[ "$app_count" -ne 1 ]]; then
    result "single-settings-instance" fail "createIosSettingsRepository() appears $app_count time(s) in $app_file (code lines, excl. comments) — need exactly 1 (NFR-05/FR-03)"
else
    result "single-settings-instance" fail "createIosSettingsRepository() found in non-iosAppApp.swift files (NFR-05/FR-03):
$other_swift_hits"
fi

# ---------------------------------------------------------------------------
# 5. no-VM-surface-widen
#
# FR-25: NoteViewModel.swift must not gain save/persist/write methods,
# .onChange(of: scenePhase), or a RecoveryRepository reference.
# Runs against the existing file unconditionally.
# ---------------------------------------------------------------------------
echo "[5] no-VM-surface-widen: no save/persist/write/.onChange(scenePhase)/RecoveryRepository in NoteViewModel.swift ..."

bvm_file="iosApp/iosApp/Editor/NoteViewModel.swift"
if [[ -f "$bvm_file" ]]; then
    vm_widen_hits=$(grep -In "func save\|func persist\|func write\|\.onChange(of: scenePhase)\|RecoveryRepository" "$bvm_file" 2>/dev/null || true)
    if [[ -z "$vm_widen_hits" ]]; then
        result "no-VM-surface-widen" ok
    else
        result "no-VM-surface-widen" fail "VM surface-widening symbol(s) found in $bvm_file (FR-25):
$vm_widen_hits"
    fi
else
    result "no-VM-surface-widen" ok "(vacuous: $bvm_file not yet tracked)"
fi

# ---------------------------------------------------------------------------
# 6. text-private-set
#
# FR-25: exactly 1 'private(set) var text' in NoteViewModel.swift;
# 0 occurrences of 'var text:' without 'private(set)'.
# Runs against the existing file unconditionally.
# ---------------------------------------------------------------------------
echo "[6] text-private-set: exactly 1 'private(set) var text' in NoteViewModel.swift; 0 bare 'var text:' ..."

if [[ -f "$bvm_file" ]]; then
    private_set_count=$(grep -c "private(set) var text" "$bvm_file" 2>/dev/null || echo "0")
    # Bare 'var text:' without 'private(set)' prefix on the same line
    bare_text_hits=$(grep -In "var text:" "$bvm_file" 2>/dev/null | grep -v "private(set)" || true)

    if [[ "$private_set_count" -eq 1 ]] && [[ -z "$bare_text_hits" ]]; then
        result "text-private-set" ok
    elif [[ "$private_set_count" -ne 1 ]]; then
        result "text-private-set" fail "'private(set) var text' appears $private_set_count time(s) in $bvm_file — need exactly 1 (FR-25)"
    else
        result "text-private-set" fail "bare 'var text:' (without private(set)) found in $bvm_file (FR-25):
$bare_text_hits"
    fi
else
    result "text-private-set" ok "(vacuous: $bvm_file not yet tracked)"
fi

# ---------------------------------------------------------------------------
# 7. no-Find-entry
#
# FR-09: No "Find", findButton, FindAffordance, or SearchBar inside Chrome/.
# If Chrome/ does not yet exist, vacuously PASS.
# ---------------------------------------------------------------------------
echo "[7] no-Find-entry: no Find/findButton/FindAffordance/SearchBar in Chrome/ ..."

if [[ -d "$chrome_dir" ]]; then
    find_hits=$(grep -RIn '"Find"\|findButton\|FindAffordance\|SearchBar' "$chrome_dir/" 2>/dev/null || true)
    if [[ -z "$find_hits" ]]; then
        result "no-Find-entry" ok
    else
        result "no-Find-entry" fail "Find-related symbol(s) in Chrome/ (FR-09):
$find_hits"
    fi
else
    result "no-Find-entry" ok "(vacuous: Chrome/ does not exist yet)"
fi

# ---------------------------------------------------------------------------
# 8. no-separate-KeyboardAccessoryBar
#
# FR-15: No KeyboardAccessoryBar or inputAccessoryView inside Chrome/.
# The bottom toolbar is hosted via .safeAreaInset, not as an accessory bar.
# If Chrome/ does not yet exist, vacuously PASS.
# ---------------------------------------------------------------------------
echo "[8] no-separate-KeyboardAccessoryBar: no KeyboardAccessoryBar/inputAccessoryView in Chrome/ ..."

if [[ -d "$chrome_dir" ]]; then
    kab_hits=$(grep -RIn "KeyboardAccessoryBar\|inputAccessoryView" "$chrome_dir/" 2>/dev/null || true)
    if [[ -z "$kab_hits" ]]; then
        result "no-separate-KeyboardAccessoryBar" ok
    else
        result "no-separate-KeyboardAccessoryBar" fail "KeyboardAccessoryBar/inputAccessoryView found in Chrome/ (FR-15):
$kab_hits"
    fi
else
    result "no-separate-KeyboardAccessoryBar" ok "(vacuous: Chrome/ does not exist yet)"
fi

# ---------------------------------------------------------------------------
# 9. SourceScanGateTest passthrough
#
# Invokes the shared Kotlin JVM test suite to verify commonMain purity
# (no Foundation/UIKit/SwiftUI in the common source set).
# Requires the Gradle wrapper to be present and runnable.
# ---------------------------------------------------------------------------
echo "[9] SourceScanGateTest passthrough: ./gradlew :shared:jvmTest --tests '*.SourceScanGateTest' ..."

if [[ -x "./gradlew" ]]; then
    if ./gradlew :shared:jvmTest --tests "*.SourceScanGateTest" --quiet 2>&1; then
        result "SourceScanGateTest" ok
    else
        result "SourceScanGateTest" fail "SourceScanGateTest failed — commonMain purity violation (NFR-06)"
    fi
else
    result "SourceScanGateTest" fail "gradlew not found or not executable at ./gradlew"
fi

# ---------------------------------------------------------------------------
# 10. populate-gate-removed
#
# OQ-16/FR-07/CM-5: The M3 structural assertion that populate() is uncalled
# must be DELETED from NoteViewModelTests.swift (not merely commented out).
# Greps for the old assertion text patterns; asserts 0 matches.
#
# Target path: iosApp/iosAppTests/NoteViewModelTests.swift
# (This is the real path on disk; the plan references iosApp/iosApp/iosAppTests/
# which is the intended M4 target location for any new test files.)
#
# NOTE: The M3 implementation did NOT use these exact assertion strings
# (the M3 populate-uncalled constraint was enforced via the m3_gate.sh (j)
# check, not a Swift-level populateCalled/populateCallCount counter).
# As a result this check passes vacuously on the current pre-M4 tree.
# It becomes load-bearing after TASK-06 if a commented-out version of the
# old assertion text accidentally survives; the gate would catch it.
# ---------------------------------------------------------------------------
echo "[10] populate-gate-removed: 0 populate-uncalled assertion text in NoteViewModelTests.swift ..."

bvm_tests_file="iosApp/iosAppTests/NoteViewModelTests.swift"
# Also check the plan-specified path in case M4 moves/recreates the file
bvm_tests_file_alt="iosApp/iosApp/iosAppTests/NoteViewModelTests.swift"

found_bvm_tests=""
if [[ -f "$bvm_tests_file" ]]; then
    found_bvm_tests="$bvm_tests_file"
elif [[ -f "$bvm_tests_file_alt" ]]; then
    found_bvm_tests="$bvm_tests_file_alt"
fi

if [[ -n "$found_bvm_tests" ]]; then
    populate_gate_hits=$(grep -In "populate should not be called\|populateCalled.*false\|populateCallCount.*0" "$found_bvm_tests" 2>/dev/null || true)
    if [[ -z "$populate_gate_hits" ]]; then
        result "populate-gate-removed" ok
    else
        result "populate-gate-removed" fail "old populate-uncalled assertion text found in $found_bvm_tests (OQ-16/FR-07) — must be DELETED not commented:
$populate_gate_hits"
    fi
else
    result "populate-gate-removed" ok "(vacuous: NoteViewModelTests.swift not found at expected path)"
fi

# ---------------------------------------------------------------------------
# 11. no-epoch-arithmetic-in-factory
#
# NFR-06/FR-05: No epoch arithmetic in shared/src/iosMain — the IosRecoveryFactory
# now() lambda must use UTC NSCalendar decomposition, not raw epoch values.
# ---------------------------------------------------------------------------
echo "[11] no-epoch-arithmetic-in-factory: no epoch arithmetic in shared/src/iosMain ..."

iosMain_dir="shared/src/iosMain"
if [[ -d "$iosMain_dir" ]]; then
    # Exclude comment lines (KDoc `*` lines and `//` lines) so that documentation
    # describing the absence of epoch arithmetic does not itself trigger the check.
    epoch_hits=$(grep -RIn "epochSeconds\|epochMilliseconds\|1000L\|timeIntervalSince1970\|timeIntervalSinceReferenceDate" "$iosMain_dir/" 2>/dev/null \
        | grep -Ev '^\S+:[0-9]+:[[:space:]]*(\*|//)' || true)
    if [[ -z "$epoch_hits" ]]; then
        result "no-epoch-arithmetic-in-factory" ok
    else
        result "no-epoch-arithmetic-in-factory" fail "epoch arithmetic found in non-comment code in $iosMain_dir (NFR-06/FR-05):
$epoch_hits"
    fi
else
    result "no-epoch-arithmetic-in-factory" ok "(vacuous: $iosMain_dir does not exist)"
fi

# ---------------------------------------------------------------------------
# 12. About-URLs-not-localized
#
# FR-22: URL literals in the About entry must NOT be wrapped in
# NSLocalizedString/String(localized:) — URLs are invariant across locales.
# If Chrome/ does not yet exist, vacuously PASS.
# ---------------------------------------------------------------------------
echo "[12] About-URLs-not-localized: no NSLocalizedString(\"https:// or String(localized: \"https:// in Chrome/ ..."

if [[ -d "$chrome_dir" ]]; then
    localized_url_hits=$(grep -RIn 'NSLocalizedString("https://\|String(localized: "https://' "$chrome_dir/" 2>/dev/null || true)
    if [[ -z "$localized_url_hits" ]]; then
        result "About-URLs-not-localized" ok
    else
        result "About-URLs-not-localized" fail "localized URL literal(s) found in Chrome/ (FR-22):
$localized_url_hits"
    fi
else
    result "About-URLs-not-localized" ok "(vacuous: Chrome/ does not exist yet)"
fi

# ---------------------------------------------------------------------------
# 13. no-okio-across-boundary
#
# FR-05/CM-3/CM-4: okio must not be imported or referenced in any iosApp Swift
# file.  okio types are internal to the shared Kotlin module and must not cross
# the XCFramework boundary.
# ---------------------------------------------------------------------------
echo "[13] no-okio-across-boundary: no 'import okio' or 'okio.' in any iosApp/**/*.swift ..."

all_iosapp_swift=$(git ls-files -- "iosApp/" | grep '\.swift$' || true)
if [[ -n "$all_iosapp_swift" ]]; then
    okio_hits=$(echo "$all_iosapp_swift" | xargs grep -In "import okio\|okio\." 2>/dev/null || true)
    if [[ -z "$okio_hits" ]]; then
        result "no-okio-across-boundary" ok
    else
        result "no-okio-across-boundary" fail "okio reference(s) found in iosApp Swift file(s) (FR-05/CM-3/CM-4):
$okio_hits"
    fi
else
    result "no-okio-across-boundary" ok "(vacuous: no tracked .swift files under iosApp/)"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
finish
