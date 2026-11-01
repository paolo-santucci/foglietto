#!/usr/bin/env bash
# tool/m6_gate.sh — M6 Localization & Theming structural verification gate
#
# Asserts the full M6 target tree in one runnable command.  Run from the repo
# root: bash tool/m6_gate.sh
#
# Exit 0 only when ALL checks (1)–(12) pass.
# Each check prints a labelled PASS/FAIL line; the script exits 1 on any FAIL.
#
# Design rules:
#   - Every check is load-bearing: when the M6 surfaces land, each asserted
#     invariant must be verifiable / already-satisfied.
#   - xcodebuild / plutil / assetutil are macOS-only and are NOT used here.
#     Those validations remain in ios.yml CI steps.
#   - No network access; Gradle is invoked only transitively via m4_gate (check 12).
#   - Comment-line exclusion: grep output lines are filtered to remove lines
#     whose first non-space chars are '//' (mirrors m4_gate/m5_gate idiom exactly).
#   - Mirrors the structure and idioms of tool/m5_gate.sh exactly:
#     result()/finish() harness, git ls-files + grep presence/absence idioms,
#     pass/fail counting, final exit-nonzero-on-any-fail.
#
# Spec refs: FR-23, FR-24; NFR-04, NFR-05.

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
    echo "=== M6 gate: $PASS passed, $FAIL failed ==="
    if [[ "$FAIL" -gt 0 ]]; then
        exit 1
    fi
    exit 0
}

echo "=== M6 gate: starting structural verification ==="
echo ""

# ---------------------------------------------------------------------------
# 1. xcstrings-exists
#
# FR-24: Localizable.xcstrings must be git-tracked.
# An untracked or absent file means EN/IT localization is missing entirely.
# ---------------------------------------------------------------------------
echo "[1] xcstrings-exists: iosApp/iosApp/Localizable.xcstrings is git-tracked ..."

xcstrings_file="iosApp/iosApp/Localizable.xcstrings"
if git ls-files --error-unmatch -- "$xcstrings_file" > /dev/null 2>&1; then
    result "xcstrings-exists" ok
else
    result "xcstrings-exists" fail "$xcstrings_file is not tracked by git (FR-24 EN+IT localization missing)"
fi

# ---------------------------------------------------------------------------
# 2. xcstrings-has-it+en
#
# FR-24: The String Catalog must contain both "en" and "it" locale keys.
# A catalog with only "en" means the IT translation was never authored.
# ---------------------------------------------------------------------------
echo "[2] xcstrings-has-it+en: Localizable.xcstrings contains both \"en\" and \"it\" ..."

if [[ -f "$xcstrings_file" ]]; then
    if ! grep -q '"en"' "$xcstrings_file" 2>/dev/null; then
        result "xcstrings-has-it+en" fail "\"en\" not found in $xcstrings_file (FR-24 — English locale missing)"
    elif ! grep -q '"it"' "$xcstrings_file" 2>/dev/null; then
        result "xcstrings-has-it+en" fail "\"it\" not found in $xcstrings_file (FR-24 — Italian locale missing)"
    else
        result "xcstrings-has-it+en" ok
    fi
else
    result "xcstrings-has-it+en" fail "$xcstrings_file not found on disk"
fi

# ---------------------------------------------------------------------------
# 3. xcstrings-registered
#
# FR-24: project.pbxproj must contain a PBXFileReference with
# lastKnownFileType = text.json.xcstrings (the catalog's Xcode type tag) AND
# "it" must be present in the knownRegions array.
#
# Without both, the catalog compiles but Italian never resolves at runtime.
# ---------------------------------------------------------------------------
echo "[3] xcstrings-registered: pbxproj has text.json.xcstrings PBXFileReference AND 'it' in knownRegions ..."

pbxproj="iosApp/iosApp.xcodeproj/project.pbxproj"
if [[ -f "$pbxproj" ]]; then
    # Check for the xcstrings file reference type tag
    if ! grep -q "text.json.xcstrings" "$pbxproj" 2>/dev/null; then
        result "xcstrings-registered" fail "text.json.xcstrings PBXFileReference not found in $pbxproj (catalog not registered as Xcode resource)"
    else
        # Check that 'it' appears inside the knownRegions block
        # grep -A4 captures the knownRegions key and the next 4 lines (the region array)
        regions_block=$(grep -A4 "knownRegions" "$pbxproj" 2>/dev/null)
        if echo "$regions_block" | grep -qw "it"; then
            result "xcstrings-registered" ok
        else
            result "xcstrings-registered" fail "'it' not found in knownRegions block of $pbxproj (FR-24 — IT region not declared)"
        fi
    fi
else
    result "xcstrings-registered" fail "$pbxproj not found on disk"
fi

# ---------------------------------------------------------------------------
# 4. shared-zero-ui-strings
#
# FR-24: shared/src/commonMain must hold ZERO user-facing UI string literals.
# All display copy lives in Swift; the shared Kotlin module is presentation-free.
#
# Scans for quoted display words as quoted string literals (double-quoted).
# Comment-line exclusion (same idiom as m4_gate check 11 / m5_gate check 2):
#   grep output format is "path:linenum:content"; filter OUT lines where the
#   content part starts with optional spaces followed by '//'.
# ---------------------------------------------------------------------------
echo "[4] shared-zero-ui-strings: shared/src/commonMain holds zero UI string literals ..."

commonMain_dir="shared/src/commonMain"
if [[ -d "$commonMain_dir" ]]; then
    ui_string_hits=$(grep -RInH '"System\|"Light\|"Dark\|"About\|"Copy\|"Paste\|"Indent\|"Outdent\|"Share\|"Recent\|"Settings\|"Menu\|"Close' \
        "$commonMain_dir/" 2>/dev/null \
        | grep -Ev '^\S+:[0-9]+:[[:space:]]*//' || true)
    if [[ -z "$ui_string_hits" ]]; then
        result "shared-zero-ui-strings" ok
    else
        result "shared-zero-ui-strings" fail "UI string literal(s) found in $commonMain_dir (FR-24 — shared module must hold zero UI strings):
$ui_string_hits"
    fi
else
    result "shared-zero-ui-strings" fail "$commonMain_dir does not exist"
fi

# ---------------------------------------------------------------------------
# 5. preferredColorScheme-at-root
#
# FR-23: .preferredColorScheme must be wired in ContentView.swift so the
# selected theme drives the entire native iOS view hierarchy.
# Comment-line exclusion applied via -H + Ev filter.
# ---------------------------------------------------------------------------
echo "[5] preferredColorScheme-at-root: '.preferredColorScheme' present (non-comment) in ContentView.swift ..."

content_view_file="iosApp/iosApp/ContentView.swift"
if [[ -f "$content_view_file" ]]; then
    scheme_hits=$(grep -InH "\.preferredColorScheme" "$content_view_file" 2>/dev/null \
        | grep -Ev '^\S+:[0-9]+:[[:space:]]*//' || true)
    if [[ -n "$scheme_hits" ]]; then
        result "preferredColorScheme-at-root" ok
    else
        result "preferredColorScheme-at-root" fail "'.preferredColorScheme' not found in non-comment code in $content_view_file (FR-23 — theme wiring missing)"
    fi
else
    result "preferredColorScheme-at-root" fail "$content_view_file not found on disk"
fi

# ---------------------------------------------------------------------------
# 6. no-hardcoded-scheme
#
# FR-23/C-05: The theme must be derived from the shared AppColorScheme setting,
# never hardcoded.  Zero occurrences of .preferredColorScheme(.dark) or
# .preferredColorScheme(.light) as a literal hardcoded value are permitted.
# Comment-line exclusion applied.
# ---------------------------------------------------------------------------
echo "[6] no-hardcoded-scheme: no '.preferredColorScheme(.dark)'/'.(.light)' literal at root (non-comment) ..."

all_iosapp_swift=$(git ls-files -- "iosApp/" | grep '\.swift$' || true)
if [[ -n "$all_iosapp_swift" ]]; then
    hardcoded_hits=$(echo "$all_iosapp_swift" | xargs grep -InH \
        '\.preferredColorScheme(\.dark)\|\.preferredColorScheme(\.light)' \
        2>/dev/null \
        | grep -Ev '^\S+:[0-9]+:[[:space:]]*//' || true)
    if [[ -z "$hardcoded_hits" ]]; then
        result "no-hardcoded-scheme" ok
    else
        result "no-hardcoded-scheme" fail "hardcoded .preferredColorScheme(.dark/.light) literal found (C-05 — must derive from AppColorScheme):
$hardcoded_hits"
    fi
else
    result "no-hardcoded-scheme" ok "(vacuous: no tracked .swift files under iosApp/)"
fi

# ---------------------------------------------------------------------------
# 7. launch-colorset-has-dark
#
# FR-23: LaunchBackground.colorset/Contents.json must declare a dark-mode
# appearance variant using the "luminosity" appearance key so the per-theme
# launch surface (light vs dark) is handled by the OS asset catalog.
# ---------------------------------------------------------------------------
echo "[7] launch-colorset-has-dark: LaunchBackground.colorset has a luminosity dark appearance variant ..."

colorset_json="iosApp/iosApp/Assets.xcassets/LaunchBackground.colorset/Contents.json"
if [[ -f "$colorset_json" ]]; then
    if grep -q '"appearance" : "luminosity"' "$colorset_json" 2>/dev/null; then
        result "launch-colorset-has-dark" ok
    else
        result "launch-colorset-has-dark" fail "\"appearance\" : \"luminosity\" not found in $colorset_json (FR-23 — dark launch color variant missing)"
    fi
else
    result "launch-colorset-has-dark" fail "$colorset_json not found on disk"
fi

# ---------------------------------------------------------------------------
# 8. no-Text-verbatim-userfacing
#
# FR-24: Text(verbatim:) bypasses the localization system and must not appear
# in user-facing copy inside Chrome/ or Editor/.
# Expect empty result (absence assertion).
# ---------------------------------------------------------------------------
echo "[8] no-Text-verbatim-userfacing: no 'Text(verbatim:' in Chrome/ or Editor/ ..."

chrome_dir="iosApp/iosApp/Chrome"
editor_dir="iosApp/iosApp/Editor"

verbatim_hits=""
for dir in "$chrome_dir" "$editor_dir"; do
    if [[ -d "$dir" ]]; then
        hits=$(grep -RIn "Text(verbatim:" "$dir/" 2>/dev/null || true)
        verbatim_hits="${verbatim_hits}${hits}"
    fi
done

if [[ -z "$verbatim_hits" ]]; then
    result "no-Text-verbatim-userfacing" ok
else
    result "no-Text-verbatim-userfacing" fail "Text(verbatim:) found in Chrome/ or Editor/ (FR-24 — bypasses localization):
$verbatim_hits"
fi

# ---------------------------------------------------------------------------
# 9. tooltips-localized
#
# FR-24: Two checks combined:
#   a) Zero "localized in M6" marker comments remain in iosApp/iosApp/ —
#      all tooltip and label strings that were tagged for future localization
#      must now use String(localized:, comment:).
#   b) ChromeOverlay.swift AND BottomToolbar.swift both contain String(localized:
#      confirming that tooltip localization was applied (not just the markers
#      stripped without replacement).
# ---------------------------------------------------------------------------
echo "[9] tooltips-localized: zero 'localized in M6' markers + String(localized: present in ChromeOverlay + BottomToolbar ..."

iosapp_dir="iosApp/iosApp"
m6_marker_hits=$(grep -RIn "localized in M6" "$iosapp_dir/" 2>/dev/null || true)

chrome_overlay_file="iosApp/iosApp/Chrome/ChromeOverlay.swift"
bottomtoolbar_file="iosApp/iosApp/Chrome/BottomToolbar.swift"

chrome_overlay_localized=""
bottomtoolbar_localized=""
if [[ -f "$chrome_overlay_file" ]]; then
    chrome_overlay_localized=$(grep -In "String(localized:" "$chrome_overlay_file" 2>/dev/null || true)
fi
if [[ -f "$bottomtoolbar_file" ]]; then
    bottomtoolbar_localized=$(grep -In "String(localized:" "$bottomtoolbar_file" 2>/dev/null || true)
fi

if [[ -n "$m6_marker_hits" ]]; then
    result "tooltips-localized" fail "'localized in M6' marker(s) still present — tooltip localization incomplete (FR-24):
$m6_marker_hits"
elif [[ -z "$chrome_overlay_localized" ]]; then
    result "tooltips-localized" fail "String(localized: not found in $chrome_overlay_file (FR-24 — tooltip localization missing)"
elif [[ -z "$bottomtoolbar_localized" ]]; then
    result "tooltips-localized" fail "String(localized: not found in $bottomtoolbar_file (FR-24 — tooltip localization missing)"
else
    result "tooltips-localized" ok
fi

# ---------------------------------------------------------------------------
# 10. theme-mapping-tests-retired
#
# User direction (always-system theme): the theme picker, the
# AppColorScheme→ColorScheme? mapping (ThemeColorScheme.swift) and
# ThemeResolutionTests.swift are REMOVED — the mapping no longer exists to
# test. The check now guards the retirement: the test must NOT be registered
# (a registered test referencing deleted symbols would fail the CI compile).
# ---------------------------------------------------------------------------
echo "[10] theme-mapping-tests-retired: ThemeResolutionTests.swift absent from pbxproj ..."

if [[ -f "$pbxproj" ]]; then
    if grep -q "ThemeResolutionTests" "$pbxproj" 2>/dev/null; then
        result "theme-mapping-tests-retired" fail "ThemeResolutionTests.swift still registered in $pbxproj — the mapping was removed; delete the registration (always-system theme)"
    else
        result "theme-mapping-tests-retired" ok
    fi
else
    result "theme-mapping-tests-retired" fail "$pbxproj not found on disk"
fi

# ---------------------------------------------------------------------------
# 11. tap-target-44
#
# NFR-05: Chrome controls must enforce ≥44×44 pt tap targets.
# Asserts that the Chrome/ directory contains a frame(minWidth or minHeight
# of 44) modifier, confirming the minimum touch-target constraint is in place.
# ---------------------------------------------------------------------------
echo "[11] tap-target-44: frame(minWidth/minHeight: 44) present in Chrome/ ..."

if [[ -d "$chrome_dir" ]]; then
    tap44_hits=$(grep -RIn "minWidth: 44\|minHeight: 44\|frame.*44.*44\|frame(width: 44" "$chrome_dir/" 2>/dev/null || true)
    if [[ -n "$tap44_hits" ]]; then
        result "tap-target-44" ok
    else
        result "tap-target-44" fail "No ≥44pt tap-target frame modifier found in $chrome_dir (NFR-05)"
    fi
else
    result "tap-target-44" ok "(vacuous: $chrome_dir does not exist yet)"
fi

# ---------------------------------------------------------------------------
# 12. m1+m4+m5 still pass
#
# M6 cannot go green while an earlier invariant regresses.  Run m1_gate,
# m4_gate, and m5_gate capturing their output; surface only pass/fail + their
# own summary lines.  M6 fails if any exits non-zero.
# ---------------------------------------------------------------------------
echo "[12] m1+m4+m5 still pass: running tool/m1_gate.sh + tool/m4_gate.sh + tool/m5_gate.sh ..."

m1_ok=true
m4_ok=true
m5_ok=true

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

if [[ -f "tool/m5_gate.sh" ]]; then
    m5_output=$(bash tool/m5_gate.sh 2>&1)
    m5_exit=$?
    m5_summary=$(echo "$m5_output" | grep "=== M5 gate:" | tail -1)
    if [[ "$m5_exit" -ne 0 ]]; then
        m5_ok=false
    fi
else
    m5_ok=false
    m5_summary="tool/m5_gate.sh not found"
fi

if $m1_ok && $m4_ok && $m5_ok; then
    echo "  m1_gate: ${m1_summary}"
    echo "  m4_gate: ${m4_summary}"
    echo "  m5_gate: ${m5_summary}"
    result "m1+m4+m5-still-pass" ok
else
    if ! $m1_ok; then
        result "m1+m4+m5-still-pass" fail "m1_gate FAILED — ${m1_summary}"
    elif ! $m4_ok; then
        result "m1+m4+m5-still-pass" fail "m4_gate FAILED — ${m4_summary}"
    else
        result "m1+m4+m5-still-pass" fail "m5_gate FAILED — ${m5_summary}"
    fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
finish
