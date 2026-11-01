#!/usr/bin/env bash
# tool/m5_gate.sh — M5 Share & Lifecycle structural verification gate
#
# Asserts the full M5 target tree in one runnable command.  Run from the repo
# root: bash tool/m5_gate.sh
#
# Exit 0 only when ALL checks (1)–(8) pass.
# Each check prints a labelled PASS/FAIL line; the script exits 1 on any FAIL.
#
# Design rules:
#   - Every check is load-bearing: when the M5 surfaces land, each asserted
#     invariant must be verifiable / already-satisfied.
#   - xcodebuild / plutil / assetutil are macOS-only and are NOT used here.
#     Those validations remain in ios.yml CI steps.
#   - No network access; Gradle is invoked only transitively via m4_gate (check 8).
#   - Comment-line exclusion: grep output lines are filtered to remove lines
#     whose first non-space chars are '//' (mirrors m4_gate idiom exactly).
#   - Mirrors the structure and idioms of tool/m4_gate.sh exactly:
#     result()/finish() harness, git ls-files + grep presence/absence idioms,
#     pass/fail counting, final exit-nonzero-on-any-fail.
#
# Spec refs: FR-14, FR-17, FR-20; R-01, AR-01, R-11; §5.3.

set -uo pipefail

if command -v rg >/dev/null 2>&1; then
    xgrep_q() { rg --no-ignore -q "$@"; }
else
    xgrep_q() { grep -qE "$@"; }
fi

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
    echo "=== M5 gate: $PASS passed, $FAIL failed ==="
    if [[ "$FAIL" -gt 0 ]]; then
        exit 1
    fi
    exit 0
}

echo "=== M5 gate: starting structural verification ==="
echo ""

# ---------------------------------------------------------------------------
# 1. coordinator-exists
#
# LifecycleSaveCoordinator.swift must be git-tracked.
# An untracked or absent file means the M5 synchronous save core is missing.
# ---------------------------------------------------------------------------
echo "[1] coordinator-exists: iosApp/iosApp/LifecycleSaveCoordinator.swift is git-tracked ..."

coordinator_file="iosApp/iosApp/LifecycleSaveCoordinator.swift"
if git ls-files --error-unmatch -- "$coordinator_file" > /dev/null 2>&1; then
    result "coordinator-exists" ok
else
    result "coordinator-exists" fail "$coordinator_file is not tracked by git (M5 save core missing)"
fi

# ---------------------------------------------------------------------------
# 2. no-async-in-save-path
#
# R-01/AR-01: The background save path must be SYNCHRONOUS.  Zero occurrences
# of async-introducing constructs are permitted in LifecycleSaveCoordinator.swift
# EXCLUDING comment lines (lines whose first non-space chars are '//').
#
# Patterns: Task {   Task.detached   DispatchQueue   \bawait\b   \basync\b
#
# Comment-line exclusion (same grep idiom as m4_gate check 11):
#   grep output lines are of the form "path:linenum:content".
#   Filter OUT lines where the content part (after "path:linenum:") starts with
#   optional spaces followed by '//'.  Uses the same regex:
#     ^\S+:[0-9]+:[[:space:]]*//)
# ---------------------------------------------------------------------------
echo "[2] no-async-in-save-path: zero async/Task/DispatchQueue/await/async (non-comment) in LifecycleSaveCoordinator.swift ..."

if [[ -f "$coordinator_file" ]]; then
    # -H forces "filename:linenum:content" format even for a single file so the
    # comment-exclusion regex ^\S+:[0-9]+:[[:space:]]*//) matches consistently
    # (without -H single-file grep emits "linenum:content" — no filename prefix).
    async_hits=$(grep -InH "Task {[[:space:]]*\|Task\.detached\|DispatchQueue\|\bawait\b\|\basync\b" "$coordinator_file" 2>/dev/null \
        | grep -Ev '^\S+:[0-9]+:[[:space:]]*//' || true)
    if [[ -z "$async_hits" ]]; then
        result "no-async-in-save-path" ok
    else
        result "no-async-in-save-path" fail "async construct(s) found in non-comment code in $coordinator_file (R-01/AR-01):
$async_hits"
    fi
else
    result "no-async-in-save-path" fail "$coordinator_file not found on disk"
fi

# ---------------------------------------------------------------------------
# 3. sync-save-uses-background-task
#
# FR-14/R-01: The synchronous save at .background must be wrapped in a
# UIBackgroundTask so the OS grants enough wall-time for the okio write to
# complete.  Asserts 'beginBackgroundTask' is present in the coordinator file.
# ---------------------------------------------------------------------------
echo "[3] sync-save-uses-background-task: 'beginBackgroundTask' present in LifecycleSaveCoordinator.swift ..."

if [[ -f "$coordinator_file" ]]; then
    if grep -q "beginBackgroundTask" "$coordinator_file" 2>/dev/null; then
        result "sync-save-uses-background-task" ok
    else
        result "sync-save-uses-background-task" fail "'beginBackgroundTask' not found in $coordinator_file (FR-14/R-01 — background-task wrapper missing)"
    fi
else
    result "sync-save-uses-background-task" fail "$coordinator_file not found on disk"
fi

# ---------------------------------------------------------------------------
# 4. scenePhase-in-ContentView
#
# FR-14: The ScenePhase lifecycle dispatch must be wired in ContentView.swift
# via .onChange(of: scenePhase).  The pattern tolerates optional surrounding
# space before/after 'scenePhase'.
# ---------------------------------------------------------------------------
echo "[4] scenePhase-in-ContentView: '.onChange(of: scenePhase)' present in ContentView.swift ..."

content_view_file="iosApp/iosApp/ContentView.swift"
if [[ -f "$content_view_file" ]]; then
    if grep -q "\.onChange(of: scenePhase)" "$content_view_file" 2>/dev/null; then
        result "scenePhase-in-ContentView" ok
    else
        result "scenePhase-in-ContentView" fail "'.onChange(of: scenePhase)' not found in $content_view_file (FR-14 — lifecycle dispatch wiring missing)"
    fi
else
    result "scenePhase-in-ContentView" fail "$content_view_file not found on disk"
fi

# ---------------------------------------------------------------------------
# 5. scenePhase-not-in-VM
#
# FR-12/m4_gate check 5 (reinforced from the M5 side): NoteViewModel.swift
# must not contain .onChange(of: scenePhase), RecoveryRepository, or
# SaveNoteToRecovery.  The save path lives OUTSIDE the VM.
# ---------------------------------------------------------------------------
echo "[5] scenePhase-not-in-VM: no .onChange(of: scenePhase)/RecoveryRepository/SaveNoteToRecovery in NoteViewModel.swift ..."

bvm_file="iosApp/iosApp/Editor/NoteViewModel.swift"
if [[ -f "$bvm_file" ]]; then
    vm_m5_hits=$(rg -n "\.onChange\(of: scenePhase|RecoveryRepository|SaveNoteToRecovery" "$bvm_file" 2>/dev/null || true)
    if [[ -z "$vm_m5_hits" ]]; then
        result "scenePhase-not-in-VM" ok
    else
        result "scenePhase-not-in-VM" fail "M5 save-path symbol(s) found in $bvm_file (FR-12 — save path must live outside the VM):
$vm_m5_hits"
    fi
else
    result "scenePhase-not-in-VM" ok "(vacuous: $bvm_file not yet tracked)"
fi

# ---------------------------------------------------------------------------
# 6. plist-filesharing
#
# FR-17: Info.plist must declare both Files-app keys immediately followed by
# <true/> on the next line (adjacent-line pairing — same idiom as m1_gate
# check (h) CFBundleIconName adjacency via grep -A1).
#
# Checks:
#   a) <key>UIFileSharingEnabled</key>     immediately followed by <true/>
#   b) <key>LSSupportsOpeningDocumentsInPlace</key>  immediately followed by <true/>
# ---------------------------------------------------------------------------
echo "[6] plist-filesharing: UIFileSharingEnabled + LSSupportsOpeningDocumentsInPlace both <true/> in Info.plist ..."

info_plist="iosApp/iosApp/Info.plist"
if [[ -f "$info_plist" ]]; then
    # Check UIFileSharingEnabled
    file_sharing_pair=$(grep -A1 "<key>UIFileSharingEnabled</key>" "$info_plist" 2>/dev/null)
    if [[ -z "$file_sharing_pair" ]]; then
        result "plist-filesharing" fail "<key>UIFileSharingEnabled</key> not found in $info_plist (FR-17)"
    elif ! echo "$file_sharing_pair" | grep -q "<true/>"; then
        result "plist-filesharing" fail "<key>UIFileSharingEnabled</key> in $info_plist is not immediately followed by <true/> (FR-17)"
    else
        # Check LSSupportsOpeningDocumentsInPlace
        docs_in_place_pair=$(grep -A1 "<key>LSSupportsOpeningDocumentsInPlace</key>" "$info_plist" 2>/dev/null)
        if [[ -z "$docs_in_place_pair" ]]; then
            result "plist-filesharing" fail "<key>LSSupportsOpeningDocumentsInPlace</key> not found in $info_plist (FR-17)"
        elif ! echo "$docs_in_place_pair" | grep -q "<true/>"; then
            result "plist-filesharing" fail "<key>LSSupportsOpeningDocumentsInPlace</key> in $info_plist is not immediately followed by <true/> (FR-17)"
        else
            result "plist-filesharing" ok
        fi
    fi
else
    result "plist-filesharing" fail "$info_plist not found on disk (FR-17)"
fi

# ---------------------------------------------------------------------------
# 7. share-empty-gate
#
echo "[7] share-empty-gate: native Share item disabled on empty text; system presenter; no panel Share row ..."

chrome_overlay_file="iosApp/iosApp/Chrome/ChromeOverlay.swift"
menu_bubble_file="iosApp/iosApp/Chrome/MenuBubble.swift"

if [[ ! -f "$chrome_overlay_file" ]]; then
    result "share-empty-gate" fail "$chrome_overlay_file not found on disk (FR-20)"
elif [[ ! -f "$menu_bubble_file" ]]; then
    result "share-empty-gate" fail "$menu_bubble_file not found on disk (FR-20)"
elif ! xgrep_q 'SystemSharePresenter\.present\(' "$chrome_overlay_file"; then
    result "share-empty-gate" fail "'SystemSharePresenter.present(' not found in $chrome_overlay_file (FR-20 — pill anchored-popover share missing)"
elif ! xgrep_q 'shareItem\.isEnabled = !text\.trimmingCharacters\(in: \.whitespacesAndNewlines\)\.isEmpty' "$chrome_overlay_file"; then
    result "share-empty-gate" fail "Native Share item empty-buffer gate missing"
elif xgrep_q "ShareLink" "$menu_bubble_file"; then
    result "share-empty-gate" fail "'ShareLink' found in $menu_bubble_file (FR-20 — the panel Share row was removed per user direction; Share lives only on the pill)"
else
    result "share-empty-gate" ok
fi

# ---------------------------------------------------------------------------
# 8. m1+m4 still pass
#
# M5 cannot go green while an earlier invariant regresses.  Run m1_gate and
# m4_gate capturing their output; surface only pass/fail + their own summary
# lines.  M5 fails if either exits non-zero.
# ---------------------------------------------------------------------------
echo "[8] m1+m4 still pass: running tool/m1_gate.sh + tool/m4_gate.sh ..."

m1_ok=true
m4_ok=true

if [[ -x "tool/m1_gate.sh" ]] || [[ -f "tool/m1_gate.sh" ]]; then
    m1_output=$(bash tool/m1_gate.sh 2>&1)
    m1_exit=$?
    m1_summary=$(echo "$m1_output" | grep -E "^(ALL M1 GATES PASSED|FAIL:)" | head -5)
    if [[ "$m1_exit" -ne 0 ]]; then
        m1_ok=false
    fi
else
    m1_ok=false
    m1_summary="tool/m1_gate.sh not found"
fi

if [[ -f "tool/m4_gate.sh" ]]; then
    m4_output=$(bash tool/m4_gate.sh 2>&1)
    m4_exit=$?
    m4_summary=$(echo "$m4_output" | grep "=== M4 gate:" | tail -1)
    if [[ "$m4_exit" -ne 0 ]]; then
        m4_ok=false
    fi
else
    m4_ok=false
    m4_summary="tool/m4_gate.sh not found"
fi

if $m1_ok && $m4_ok; then
    echo "  m1_gate: ${m1_summary}"
    echo "  m4_gate: ${m4_summary}"
    result "m1+m4-still-pass" ok
else
    if ! $m1_ok; then
        result "m1+m4-still-pass" fail "m1_gate FAILED — ${m1_summary}"
    else
        result "m1+m4-still-pass" fail "m4_gate FAILED — ${m4_summary}"
    fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
finish
