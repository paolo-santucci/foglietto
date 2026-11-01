#!/usr/bin/env bash
# tool/m3_gate.sh — M3 SwiftUI Editor Core structural verification gate
#
# Asserts the full M3 target tree in one runnable command.  Run from the repo
# root: bash tool/m3_gate.sh
#
# Exit 0 only when ALL checks (a)–(p) pass.
# Exit 1 on the FIRST failing check with a descriptive message that names
# exactly what is missing or wrong.
#
# Design rules:
#   - Every check is load-bearing: temporarily removing any asserted artifact
#     MUST flip that specific check to fail.
#   - xcodebuild / plutil / assetutil are macOS-only and are NOT used here.
#     Those validations remain in ios.yml CI steps.
#   - No network access; no Gradle tasks; no xcodebuild calls.  This script is
#     a pure structural audit runnable on any bash host (Linux or macOS).
#   - Mirrors the structure and idioms of tool/m1_gate.sh exactly.
#     Presence checks use git ls-files + grep; absence checks use
#     grep -RIn directly on the target path (safe: empty directory = no match).
#
# Spec refs: §7.1 gate-sh; FR-23, FR-24, FR-12, FR-19, FR-20, FR-01, FR-02,
#            FR-10, NFR-02, NFR-03, NFR-04; §6.1 EC-13..EC-16.

set -uo pipefail

# ---------------------------------------------------------------------------
# fail <message>
#   Print a FAIL line and exit 1 immediately.  Every check calls this on the
#   first violation so the caller sees exactly which gate tripped.
# ---------------------------------------------------------------------------
fail() {
    echo "FAIL: $*" >&2
    exit 1
}

echo "=== M3 gate: starting structural verification ==="

# ---------------------------------------------------------------------------
# PRESENCE CHECKS
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# (a) UIViewRepresentable present in iosApp/
#
# FR-01 / FR-23: the editing surface must be a UIViewRepresentable-wrapped
# UITextView, never SwiftUI TextEditor.  At least one tracked file in iosApp/
# must contain the UIViewRepresentable conformance declaration.
# ---------------------------------------------------------------------------
echo "[a] UIViewRepresentable present in iosApp/ ..."

uivr_hits=$(git ls-files -- "iosApp/" | xargs grep -l "UIViewRepresentable" 2>/dev/null)
[[ -n "$uivr_hits" ]] \
    || fail "(a) UIViewRepresentable not found in any tracked file under iosApp/ — NoteEditor.swift is not yet authored (FR-01/FR-23)"

echo "  [a] OK"

# ---------------------------------------------------------------------------
# (b) UITextView present in iosApp/iosApp/Editor/
#
# FR-01: the UIViewRepresentable wraps a UITextView.  The Editor/ subdirectory
# must contain at least one tracked file with UITextView.
# ---------------------------------------------------------------------------
echo "[b] UITextView present in iosApp/iosApp/Editor/ ..."

editor_files=$(git ls-files -- "iosApp/iosApp/Editor/")
if [[ -n "$editor_files" ]]; then
    uitv_hits=$(echo "$editor_files" | xargs grep -l "UITextView" 2>/dev/null || true)
else
    uitv_hits=""
fi
[[ -n "$uitv_hits" ]] \
    || fail "(b) UITextView not found in any tracked file under iosApp/iosApp/Editor/ — NoteEditor.swift is not yet authored (FR-01)"

echo "  [b] OK"

# ---------------------------------------------------------------------------
# (c) Exactly one offset-conversion helper: TextOffsetBridge.swift
#
# NFR-04: the NSRange↔shared-Int conversion is centralized in exactly one
# helper.  The file must be tracked by git.
# ---------------------------------------------------------------------------
echo "[c] Exactly one offset-conversion helper (TextOffsetBridge.swift) ..."

bridge_tracked=$(git ls-files -- "iosApp/iosApp/Editor/TextOffsetBridge.swift")
[[ -n "$bridge_tracked" ]] \
    || fail "(c) iosApp/iosApp/Editor/TextOffsetBridge.swift not tracked — the single NSRange↔shared offset conversion helper is not yet authored (FR-06/NFR-04)"

echo "  [c] OK"

# ---------------------------------------------------------------------------
# (d) func populate present exactly once in NoteViewModel.swift
#
# FR-10 / §5.3 seam: the programmatic-restore entry point must be defined once
# and only once.  The gate checks the definition count, not the call count.
# ---------------------------------------------------------------------------
echo "[d] func populate present exactly once in NoteViewModel.swift ..."

bvm_file="iosApp/iosApp/Editor/NoteViewModel.swift"
bvm_tracked=$(git ls-files -- "$bvm_file")
[[ -n "$bvm_tracked" ]] \
    || fail "(d) iosApp/iosApp/Editor/NoteViewModel.swift not tracked — NoteViewModel is not yet authored (FR-09/FR-10)"

populate_count=$(grep -c "func populate" "$bvm_file" 2>/dev/null || echo "0")
[[ "$populate_count" -eq 1 ]] \
    || fail "(d) 'func populate' appears $populate_count time(s) in $bvm_file — must be exactly 1 (FR-10/§5.3 seam)"

echo "  [d] OK"

# ---------------------------------------------------------------------------
# ABSENCE CHECKS
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# (e) TextEditor == 0 in iosApp/iosApp/  (AR-02 / FR-01)
#
# SwiftUI TextEditor is forbidden — the surface must use UIViewRepresentable
# wrapping UITextView.  Any match is a spec violation.
# ---------------------------------------------------------------------------
echo "[e] TextEditor absent in iosApp/iosApp/ (AR-02/FR-01) ..."

te_hits=$(grep -RIn "TextEditor" "iosApp/iosApp/" 2>/dev/null || true)
[[ -z "$te_hits" ]] \
    || fail "(e) TextEditor found in iosApp/iosApp/ — SwiftUI TextEditor is forbidden (AR-02/FR-01):
$te_hits"

echo "  [e] OK"

# ---------------------------------------------------------------------------
# (f) SharedPlaceholder == 0 in iosApp/  (FR-19)
#
# M2/TASK-07 deleted SharedPlaceholder.  M3 must not reintroduce a call.
# Any match means the latent ContentView build-break was not fully repaired.
# ---------------------------------------------------------------------------
echo "[f] SharedPlaceholder absent in iosApp/ (FR-19) ..."

sp_hits=$(grep -RIn "SharedPlaceholder" "iosApp/" 2>/dev/null || true)
[[ -z "$sp_hits" ]] \
    || fail "(f) SharedPlaceholder found in iosApp/ — deleted symbol must not be called; ContentView must be replaced wholesale (FR-19/EC-14):
$sp_hits"

echo "  [f] OK"

# ---------------------------------------------------------------------------
# (g) String.count and String.Index offset arithmetic == 0 in iosApp/iosApp/Editor/
#
# FR-06 / NFR-04: all offset conversion must go through TextOffsetBridge using
# NSString-backed UTF-16.  Swift.String.count and String.Index arithmetic at
# the shared boundary silently corrupts offsets past non-BMP characters.
# grep -RIn returns empty output if the directory contains no tracked files —
# so this is safe to run even before Editor/ exists.
# ---------------------------------------------------------------------------
echo "[g] String.count / String.Index offset arithmetic absent in iosApp/iosApp/Editor/ (NFR-04/FR-06) ..."

sc_hits=$(grep -RIn "\.count\|String\.Index" "iosApp/iosApp/Editor/" 2>/dev/null || true)
[[ -z "$sc_hits" ]] \
    || fail "(g) Swift.String.count or String.Index found in iosApp/iosApp/Editor/ — offset arithmetic must use NSString UTF-16 via TextOffsetBridge exclusively (FR-06/NFR-04/EC-02):
$sc_hits"

echo "  [g] OK"

# ---------------------------------------------------------------------------
# (h) .glassEffect / GlassEffectContainer == 0 in iosApp/iosApp/Editor/
#
# FR-20 / NFR-03: glass is chrome-only; the editor content surface (UITextView
# / UIViewRepresentable) must carry no glass material.  Any match is a
# compliance violation.
# ---------------------------------------------------------------------------
echo "[h] .glassEffect / GlassEffectContainer absent in iosApp/iosApp/Editor/ (FR-20/NFR-03) ..."

glass_hits=$(grep -RIn "glassEffect\|GlassEffectContainer" "iosApp/iosApp/Editor/" 2>/dev/null || true)
[[ -z "$glass_hits" ]] \
    || fail "(h) .glassEffect or GlassEffectContainer found in iosApp/iosApp/Editor/ — glass is forbidden on the editor content layer (FR-20/NFR-03/EC-13):
$glass_hits"

echo "  [h] OK"

# ---------------------------------------------------------------------------
# (i) func save / func persist / func recoveryWrite == 0 in NoteViewModel.swift
#
# FR-12 / §5.3 seam: the VM exposes no save/persist/recovery-write API.
# Only run if the file is tracked (presence asserted in check (d)).
# ---------------------------------------------------------------------------
echo "[i] No save/persist/recoveryWrite API in NoteViewModel.swift (FR-12) ..."

if [[ -f "$bvm_file" ]]; then
    save_hits=$(grep -In "func save\|func persist\|func recoveryWrite" "$bvm_file" 2>/dev/null || true)
    [[ -z "$save_hits" ]] \
        || fail "(i) save/persist/recoveryWrite method found in $bvm_file — no save API is permitted on the VM in M3 (FR-12/§5.3 seam):
$save_hits"
fi

echo "  [i] OK"

# ---------------------------------------------------------------------------
# (j) .populate( call == 0 in iosApp/iosApp/ (excluding the definition)
#
# FR-10 / §5.3 seam: populate is defined-not-wired in M3.  No M3 caller
# should invoke it.  The gate scans for the call pattern (.populate( with a
# dot prefix) which the definition line (func populate) does not match.
# ---------------------------------------------------------------------------
echo "[j] .populate( call absent in iosApp/iosApp/ — seam defined-not-wired (FR-10) ..."

populate_call_hits=$(grep -RIn "\.populate(" "iosApp/iosApp/" 2>/dev/null || true)
[[ -z "$populate_call_hits" ]] \
    || fail "(j) .populate( call found in iosApp/iosApp/ — the populate seam must be defined but have no M3 caller (FR-10/§5.3):
$populate_call_hits"

echo "  [j] OK"

# ---------------------------------------------------------------------------
# (k) _continuing / isApplying / reentryGuard == 0 in iosApp/iosApp/
#
# FR-02 / EC-03: the shouldChangeTextIn hook needs no re-entrancy guard
# because a direct UITextView.text = assignment bypasses the delegate.
# Any such guard variable is a spec violation.
# ---------------------------------------------------------------------------
echo "[k] No _continuing / isApplying / reentryGuard in iosApp/iosApp/ (FR-02/EC-03) ..."

reentry_hits=$(grep -RIn "_continuing\|isApplying\|reentryGuard" "iosApp/iosApp/" 2>/dev/null || true)
[[ -z "$reentry_hits" ]] \
    || fail "(k) Re-entrancy guard variable (_continuing/isApplying/reentryGuard) found in iosApp/iosApp/ — no guard is needed or permitted (FR-02/EC-03):
$reentry_hits"

echo "  [k] OK"

# ---------------------------------------------------------------------------
# (l) UIFileSharingEnabled / LSSupportsOpeningDocumentsInPlace == 0 in Info.plist
#     AND CFBundleIconName count unchanged from M1 baseline (FR-24)
#
# FR-24: M3 must not touch Info.plist.  The Files-app keys belong to M5.
# CFBundleIconName must still be present at least once (M1 established it).
# ---------------------------------------------------------------------------
echo "[l] Info.plist untouched — no Files-app keys; CFBundleIconName present (FR-24) ..."

info_plist="iosApp/iosApp/Info.plist"
[[ -f "$info_plist" ]] || fail "(l) $info_plist not found on disk"

filesapp_hits=$(grep -n "UIFileSharingEnabled\|LSSupportsOpeningDocumentsInPlace" "$info_plist" 2>/dev/null || true)
[[ -z "$filesapp_hits" ]] \
    || fail "(l) UIFileSharingEnabled or LSSupportsOpeningDocumentsInPlace found in $info_plist — M3 must not add Files-app keys (FR-24/M5):
$filesapp_hits"

# CFBundleIconName must remain present (M1 baseline).
icon_pair=$(grep -A1 "<key>CFBundleIconName</key>" "$info_plist" 2>/dev/null || true)
[[ -n "$icon_pair" ]] \
    || fail "(l) CFBundleIconName missing from $info_plist — M1 established this key and M3 must not remove it (FR-24)"

echo "$icon_pair" | grep -q "<string>AppIcon</string>" \
    || fail "(l) CFBundleIconName in $info_plist is not immediately followed by <string>AppIcon</string> — value changed or key structure broken (FR-24)"

echo "  [l] OK"

# ---------------------------------------------------------------------------
# (m) if #available == 0 in M3-touched Swift files (NFR-02)
#
# NFR-02: the deployment target is iOS 26.0; no glass-fallback or availability
# guard should be introduced in any M3-touched file.  Scan the Editor/
# directory plus the two composition-root files M3 modifies wholesale.
# ---------------------------------------------------------------------------
echo "[m] No 'if #available' in M3-touched Swift files (NFR-02) ..."

available_hits=""

# Editor/ (new files from TASK-03/04/05/06)
editor_avail=$(grep -RIn "if #available" "iosApp/iosApp/Editor/" 2>/dev/null || true)
available_hits="${available_hits}${editor_avail}"

# ContentView.swift (replaced wholesale in TASK-07)
if [[ -f "iosApp/iosApp/ContentView.swift" ]]; then
    cv_avail=$(grep -In "if #available" "iosApp/iosApp/ContentView.swift" 2>/dev/null || true)
    available_hits="${available_hits}${cv_avail}"
fi

# iosAppApp.swift (minimal touch in TASK-07)
if [[ -f "iosApp/iosApp/iosAppApp.swift" ]]; then
    app_avail=$(grep -In "if #available" "iosApp/iosApp/iosAppApp.swift" 2>/dev/null || true)
    available_hits="${available_hits}${app_avail}"
fi

[[ -z "$available_hits" ]] \
    || fail "(m) 'if #available' found in M3-touched Swift files — iOS 26.0 is the deployment floor; no availability guard is permitted (NFR-02):
$available_hits"

echo "  [m] OK"

# ---------------------------------------------------------------------------
# PBXPROJ / CI STRUCTURAL CHECKS
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# (n) iosAppTests present in scheme <TestAction><Testables>  (FR-22)
#
# The xcscheme must reference the iosAppTests target inside the TestAction
# Testables block so xcodebuild test picks it up automatically.
# ---------------------------------------------------------------------------
echo "[n] iosAppTests in scheme <TestAction><Testables> (FR-22) ..."

xcscheme_file="iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme"
[[ -f "$xcscheme_file" ]] || fail "(n) $xcscheme_file not found on disk"

grep -q "iosAppTests" "$xcscheme_file" \
    || fail "(n) 'iosAppTests' not found in $xcscheme_file — the test target must be registered in the scheme's <TestAction><Testables> (FR-22)"

# Confirm the reference is inside the TestAction block, not just elsewhere
# (e.g. only in BuildActionEntries).  Extract lines from <TestAction to
# </TestAction> and confirm 'iosAppTests' is in that window.
testaction_block=$(awk '/<TestAction/,/<\/TestAction>/' "$xcscheme_file" 2>/dev/null || true)
echo "$testaction_block" | grep -q "iosAppTests" \
    || fail "(n) 'iosAppTests' exists in $xcscheme_file but NOT inside the <TestAction>...</TestAction> block — it must be inside <TestAction><Testables> (FR-22)"

echo "  [n] OK"

# ---------------------------------------------------------------------------
# (o) IPHONEOS_DEPLOYMENT_TARGET = 26.0 in all four config blocks;
#     SWIFT_VERSION = 6.0  (NFR-02)
#
# The pbxproj has four XCBuildConfiguration blocks (2 target × 2 project).
# Every one must declare 26.0.  After M3 adds the iosAppTests target, there
# will be at least 4 deployment-target entries.  Currently (M1 baseline) there
# are exactly 4 (2 project + 2 target).  The check uses >= 4 to tolerate the
# M3 test-target configs being added.
# ---------------------------------------------------------------------------
echo "[o] IPHONEOS_DEPLOYMENT_TARGET = 26.0 in all config blocks; SWIFT_VERSION = 6.0 (NFR-02) ..."

pbxproj="iosApp/iosApp.xcodeproj/project.pbxproj"
[[ -f "$pbxproj" ]] || fail "(o) $pbxproj not found on disk"

dt_count=$(grep -c "IPHONEOS_DEPLOYMENT_TARGET = 26.0" "$pbxproj" 2>/dev/null || echo "0")
[[ "$dt_count" -ge 4 ]] \
    || fail "(o) IPHONEOS_DEPLOYMENT_TARGET = 26.0 appears $dt_count time(s) in $pbxproj — expected at least 4 (one per XCBuildConfiguration block; after M3 the iosAppTests target adds 2 more) (NFR-02)"

sv_count=$(grep -c "SWIFT_VERSION = 6.0" "$pbxproj" 2>/dev/null || echo "0")
[[ "$sv_count" -ge 2 ]] \
    || fail "(o) SWIFT_VERSION = 6.0 appears $sv_count time(s) in $pbxproj — expected at least 2 (one per target XCBuildConfiguration block) (NFR-02)"

echo "  [o] OK"

# ---------------------------------------------------------------------------
# (p) Per expected new Swift file: basename appears >= 2 times in pbxproj
#
# FR-21 / EC-15: every new .swift file must be registered in three places
# in project.pbxproj (PBXFileReference + PBXGroup A1000031 child +
# PBXBuildFile in Sources phase A1000005).  A basename appearing fewer than
# 2 times indicates incomplete registration (only appears in one section).
#
# Checked files: the four Editor/ files introduced by M3.
# On the M1-baseline tree all will fail (expected — files arrive in later
# waves; TASK-09 is the wave at which all are present and registered).
# ---------------------------------------------------------------------------
echo "[p] pbxproj 3-place registration — basename >= 2 occurrences per M3 Swift file (FR-21) ..."

m3_swift_files=(
    "TextOffsetBridge.swift"
    "NoteViewModel.swift"
    "NoteEditor.swift"
    "EditorPinchModifier.swift"
)

pbxproj_failures=""
for swift_file in "${m3_swift_files[@]}"; do
    ref_count=$(grep -c "$swift_file" "$pbxproj" 2>/dev/null || echo "0")
    # grep -c returns a plain integer; compare numerically
    if ! [[ "$ref_count" =~ ^[0-9]+$ ]]; then
        ref_count=0
    fi
    if [[ "$ref_count" -lt 2 ]]; then
        pbxproj_failures="${pbxproj_failures}  ${swift_file}: ${ref_count} occurrence(s) in project.pbxproj (need >= 2 for PBXFileReference + Sources-phase entry)\n"
    fi
done

[[ -z "$pbxproj_failures" ]] \
    || fail "$(printf '(p) pbxproj registration incomplete for one or more M3 Swift files (FR-21/EC-15):\n%b' "$pbxproj_failures")"

echo "  [p] OK"

# ---------------------------------------------------------------------------
# All checks passed.
# ---------------------------------------------------------------------------
echo ""
echo "ALL M3 GATES PASSED"
exit 0
