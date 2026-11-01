#!/usr/bin/env bash
# tool/m7_gate.sh — M7 CI to TestFlight & Hardening structural verification gate
#
# Asserts the full M7 target tree in one runnable command.  Run from the repo
# root: bash tool/m7_gate.sh
#
# Exit 0 only when ALL checks (1)–(13) pass.
# Each check prints a labelled PASS/FAIL line; the script exits 1 on any FAIL.
#
# Design rules:
#   - Every check is load-bearing: when the M7 surfaces land, each asserted
#     invariant must be verifiable / already-satisfied.
#   - xcodebuild / plutil / assetutil / actool are macOS-only and are NOT used
#     here.  Those validations remain in ios.yml CI steps (C-06).
#   - No network access; prior gates invoked only transitively via check 13.
#   - Comment-line exclusion: grep output lines are filtered to remove lines
#     whose first non-space chars are '//' or '#' as appropriate per file type.
#   - Mirrors the structure and idioms of tool/m6_gate.sh exactly:
#     result()/finish() harness, git ls-files + grep presence/absence idioms,
#     pass/fail counting, final exit-nonzero-on-any-fail.
#
# Spec refs: FR-26, FR-27; §3.1.d invariant table.

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
    echo "=== M7 gate: $PASS passed, $FAIL failed ==="
    if [[ "$FAIL" -gt 0 ]]; then
        exit 1
    fi
    exit 0
}

echo "=== M7 gate: starting structural verification ==="
echo ""

# Shared path constants
info_plist="iosApp/iosApp/Info.plist"
export_options="ExportOptions.plist.template"
appicon_json="iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json"
ios_yml=".github/workflows/ios.yml"

# ---------------------------------------------------------------------------
# 1. plist-marketing-version-macro
#
# FR-27: CFBundleShortVersionString value must be the Xcode build-setting macro
# $(MARKETING_VERSION) — never a hardcoded version string.  Uses grep -A1
# adjacency (key line immediately followed by value line) so a stale string
# inserted between them is still caught.
# ---------------------------------------------------------------------------
echo "[1] plist-marketing-version-macro: CFBundleShortVersionString value is \$(MARKETING_VERSION) in Info.plist ..."

if [[ -f "$info_plist" ]]; then
    mv_pair=$(grep -A1 "<key>CFBundleShortVersionString</key>" "$info_plist" 2>/dev/null)
    if echo "$mv_pair" | grep -q "\$(MARKETING_VERSION)"; then
        result "plist-marketing-version-macro" ok
    else
        result "plist-marketing-version-macro" fail "CFBundleShortVersionString value is not \$(MARKETING_VERSION) in $info_plist (FR-27 — version macro missing)"
    fi
else
    result "plist-marketing-version-macro" fail "$info_plist not found on disk"
fi

# ---------------------------------------------------------------------------
# 2. plist-build-version-macro
#
# FR-27: CFBundleVersion value must be the Xcode build-setting macro
# $(CURRENT_PROJECT_VERSION).  Uses grep -A1 adjacency idiom.
# ---------------------------------------------------------------------------
echo "[2] plist-build-version-macro: CFBundleVersion value is \$(CURRENT_PROJECT_VERSION) in Info.plist ..."

if [[ -f "$info_plist" ]]; then
    bv_pair=$(grep -A1 "<key>CFBundleVersion</key>" "$info_plist" 2>/dev/null)
    if echo "$bv_pair" | grep -q "\$(CURRENT_PROJECT_VERSION)"; then
        result "plist-build-version-macro" ok
    else
        result "plist-build-version-macro" fail "CFBundleVersion value is not \$(CURRENT_PROJECT_VERSION) in $info_plist (FR-27 — build version macro missing)"
    fi
else
    result "plist-build-version-macro" fail "$info_plist not found on disk"
fi

# ---------------------------------------------------------------------------
# 3. plist-icon-name
#
# FR-27: CFBundleIconName value must be 'AppIcon'.  Uses grep -A1 adjacency.
# A missing or wrong value causes altool to reject the IPA with
# "Missing CFBundleIconName" (R-07 landmine).
# ---------------------------------------------------------------------------
echo "[3] plist-icon-name: CFBundleIconName value is 'AppIcon' in Info.plist ..."

if [[ -f "$info_plist" ]]; then
    icon_pair=$(grep -A1 "<key>CFBundleIconName</key>" "$info_plist" 2>/dev/null)
    if echo "$icon_pair" | grep -q "<string>AppIcon</string>"; then
        result "plist-icon-name" ok
    else
        result "plist-icon-name" fail "CFBundleIconName value is not 'AppIcon' in $info_plist (FR-27 — altool will reject the IPA)"
    fi
else
    result "plist-icon-name" fail "$info_plist not found on disk"
fi

# ---------------------------------------------------------------------------
# 4. plist-branding
#
# FR-27: Both CFBundleName AND CFBundleDisplayName must be 'Foglietto'.
# A mismatch here means the bundle or the home-screen label shows the wrong
# name after signing; App Store Connect also validates the display name.
# ---------------------------------------------------------------------------
echo "[4] plist-branding: CFBundleName AND CFBundleDisplayName are both 'Foglietto' in Info.plist ..."

if [[ -f "$info_plist" ]]; then
    bundle_name_pair=$(grep -A1 "<key>CFBundleName</key>" "$info_plist" 2>/dev/null)
    display_name_pair=$(grep -A1 "<key>CFBundleDisplayName</key>" "$info_plist" 2>/dev/null)

    if ! echo "$bundle_name_pair" | grep -q "<string>Foglietto</string>"; then
        result "plist-branding" fail "CFBundleName is not 'Foglietto' in $info_plist (FR-27 — branding mismatch)"
    elif ! echo "$display_name_pair" | grep -q "<string>Foglietto</string>"; then
        result "plist-branding" fail "CFBundleDisplayName is not 'Foglietto' in $info_plist (FR-27 — branding mismatch)"
    else
        result "plist-branding" ok
    fi
else
    result "plist-branding" fail "$info_plist not found on disk"
fi

# ---------------------------------------------------------------------------
# 5. appicon-luminosity-variants
#
# FR-27: AppIcon.appiconset/Contents.json must declare BOTH dark and tinted
# luminosity appearance variants so the home-screen icon adapts to the user's
# chosen appearance (iOS 18+ / iOS 26 adaptive icon feature).
#
# IMPORTANT: the actual Contents.json uses "value": "dark" (no spaces around
# the colon), NOT "value" : "dark" — grep must match the file's real format.
# Checked by grepping for "value": "dark" and "value": "tinted".
# ---------------------------------------------------------------------------
echo "[5] appicon-luminosity-variants: AppIcon Contents.json declares dark + tinted luminosity appearances ..."

if [[ -f "$appicon_json" ]]; then
    if ! grep -q '"value": "dark"' "$appicon_json" 2>/dev/null; then
        result "appicon-luminosity-variants" fail "\"value\": \"dark\" not found in $appicon_json (FR-27 — dark icon variant missing)"
    elif ! grep -q '"value": "tinted"' "$appicon_json" 2>/dev/null; then
        result "appicon-luminosity-variants" fail "\"value\": \"tinted\" not found in $appicon_json (FR-27 — tinted icon variant missing)"
    else
        result "appicon-luminosity-variants" ok
    fi
else
    result "appicon-luminosity-variants" fail "$appicon_json not found on disk"
fi

# ---------------------------------------------------------------------------
# 6. exportoptions-method-appstore
#
# C-01/FR-26: ExportOptions.plist.template method must be 'app-store'.
# An ad-hoc or development method here would silently produce an un-uploadable
# IPA that fails at altool with a signing-method rejection.
# ---------------------------------------------------------------------------
echo "[6] exportoptions-method-appstore: method value is 'app-store' in ExportOptions.plist.template ..."

if [[ -f "$export_options" ]]; then
    method_pair=$(grep -A1 "<key>method</key>" "$export_options" 2>/dev/null)
    if echo "$method_pair" | grep -q "<string>app-store</string>"; then
        result "exportoptions-method-appstore" ok
    else
        result "exportoptions-method-appstore" fail "method value is not 'app-store' in $export_options (C-01 — wrong export method)"
    fi
else
    result "exportoptions-method-appstore" fail "$export_options not found on disk"
fi

# ---------------------------------------------------------------------------
# 7. exportoptions-manual-signing
#
# C-01: signingStyle must be 'manual' in ExportOptions.plist.template.
# 'automatic' here would bypass the explicit profile/cert overrides passed
# via xcodebuild CLI arguments and break signing on the CI runner.
# ---------------------------------------------------------------------------
echo "[7] exportoptions-manual-signing: signingStyle value is 'manual' in ExportOptions.plist.template ..."

if [[ -f "$export_options" ]]; then
    style_pair=$(grep -A1 "<key>signingStyle</key>" "$export_options" 2>/dev/null)
    if echo "$style_pair" | grep -q "<string>manual</string>"; then
        result "exportoptions-manual-signing" ok
    else
        result "exportoptions-manual-signing" fail "signingStyle value is not 'manual' in $export_options (C-01 — automatic signing would bypass profile overrides)"
    fi
else
    result "exportoptions-manual-signing" fail "$export_options not found on disk"
fi

# ---------------------------------------------------------------------------
# 8. exportoptions-bundle-id-key
#
# FR-26: The provisioningProfiles dict in ExportOptions.plist.template must
# contain the canonical bundle identifier as a key.  Without it, xcodebuild
# -exportArchive cannot find the provisioning profile for the app target.
# ---------------------------------------------------------------------------
echo "[8] exportoptions-bundle-id-key: 'com.paolosantucci.foglietto' key present under provisioningProfiles in ExportOptions.plist.template ..."

if [[ -f "$export_options" ]]; then
    if grep -q "<key>com.paolosantucci.foglietto</key>" "$export_options" 2>/dev/null; then
        result "exportoptions-bundle-id-key" ok
    else
        result "exportoptions-bundle-id-key" fail "<key>com.paolosantucci.foglietto</key> not found in $export_options (FR-26 — bundle ID missing from provisioningProfiles)"
    fi
else
    result "exportoptions-bundle-id-key" fail "$export_options not found on disk"
fi

# ---------------------------------------------------------------------------
# 9. exportoptions-manage-version-false
#
# FR-26 / §3.1.b (T-01 addition — LOAD-BEARING):
# ExportOptions.plist.template must contain BOTH the
# <key>manageAppVersionAndBuildNumber</key> key AND <false/> as its value.
# Without this key, Xcode silently rewrites the CLI-set build number during
# -exportArchive, causing build-409 collisions on the shared ASC app.
#
# This check is non-vacuous: removing the key from the template would cause
# this check to fail (the grep for manageAppVersionAndBuildNumber returns empty).
# The grep targets the key itself, not the surrounding context.
# ---------------------------------------------------------------------------
echo "[9] exportoptions-manage-version-false: manageAppVersionAndBuildNumber key AND <false/> present in ExportOptions.plist.template ..."

if [[ -f "$export_options" ]]; then
    if ! grep -q "<key>manageAppVersionAndBuildNumber</key>" "$export_options" 2>/dev/null; then
        result "exportoptions-manage-version-false" fail "<key>manageAppVersionAndBuildNumber</key> not found in $export_options (§3.1.b — T-01 addition missing; Xcode will silently rewrite build number)"
    else
        # Also assert the adjacent value is <false/>
        manage_pair=$(grep -A1 "<key>manageAppVersionAndBuildNumber</key>" "$export_options" 2>/dev/null)
        if echo "$manage_pair" | grep -q "<false/>"; then
            result "exportoptions-manage-version-false" ok
        else
            result "exportoptions-manage-version-false" fail "manageAppVersionAndBuildNumber key found but value is not <false/> in $export_options (§3.1.b — must be false to prevent build-number override)"
        fi
    fi
else
    result "exportoptions-manage-version-false" fail "$export_options not found on disk"
fi

# ---------------------------------------------------------------------------
# 10. deploy-tag-gated
#
# C-05: The deploy_testflight job must have the tag gate condition so that
# signed builds and TestFlight uploads only trigger on production v* tags —
# never on branch pushes or PRs.
# ---------------------------------------------------------------------------
echo "[10] deploy-tag-gated: deploy_testflight job has 'if: startsWith(github.ref, 'refs/tags/v')' in ios.yml ..."

if [[ -f "$ios_yml" ]]; then
    if grep -q "if: startsWith(github.ref, 'refs/tags/v')" "$ios_yml" 2>/dev/null; then
        result "deploy-tag-gated" ok
    else
        result "deploy-tag-gated" fail "'if: startsWith(github.ref, '\"'refs/tags/v'\"')' not found in $ios_yml (C-05 — deploy_testflight is not tag-gated)"
    fi
else
    result "deploy-tag-gated" fail "$ios_yml not found on disk"
fi

# ---------------------------------------------------------------------------
# 11. deploy-has-upload
#
# FR-26: The deploy_testflight job must include an altool --upload-app
# invocation.  Without this the pipeline arms signing but never actually
# uploads to TestFlight.
# ---------------------------------------------------------------------------
echo "[11] deploy-has-upload: 'altool' + '--upload-app' present in deploy_testflight job in ios.yml ..."

if [[ -f "$ios_yml" ]]; then
    if ! grep -q "altool" "$ios_yml" 2>/dev/null; then
        result "deploy-has-upload" fail "'altool' not found in $ios_yml (FR-26 — TestFlight upload step missing)"
    elif ! grep -q "\-\-upload-app" "$ios_yml" 2>/dev/null; then
        result "deploy-has-upload" fail "'--upload-app' not found in $ios_yml (FR-26 — altool upload flag missing)"
    else
        result "deploy-has-upload" ok
    fi
else
    result "deploy-has-upload" fail "$ios_yml not found on disk"
fi

# ---------------------------------------------------------------------------
# 12. no-flutter-runner-pods-tokens
#
# C-08: Zero ACTIVE (non-comment) Flutter coupling tokens in ios.yml.
# Tokens checked: 'rm Runner.app', 'pod install', 'Podfile'.
# (The '.xcodeproj' extension legitimately appears in xcodebuild -project
#  flags; the Ruby xcodeproj gem would appear as 'require .xcodeproj.' or
#  'Xcodeproj::' — neither is present.  Grep for the three concrete active
#  forms to avoid false-positives on explanatory comments that mention the
#  removed Flutter coupling.)
#
# Comment-line exclusion: ios.yml is YAML so comment lines start with '#'.
# Filter OUT lines where the content part (after "path:linenum:") starts with
# optional spaces followed by '#' — same idiom as m4_gate/m5_gate/m6_gate
# for '//' Swift/Kotlin comments, adapted for YAML '#' comments.
# ---------------------------------------------------------------------------
echo "[12] no-flutter-runner-pods-tokens: zero active 'rm Runner.app'/'pod install'/'Podfile' tokens in ios.yml (non-comment) ..."

if [[ -f "$ios_yml" ]]; then
    flutter_hits=$(grep -InH "rm Runner\.app\|pod install\|Podfile" "$ios_yml" 2>/dev/null \
        | grep -Ev '^\S+:[0-9]+:[[:space:]]*#' || true)
    if [[ -z "$flutter_hits" ]]; then
        result "no-flutter-runner-pods-tokens" ok
    else
        result "no-flutter-runner-pods-tokens" fail "Flutter coupling token(s) found in active (non-comment) YAML in $ios_yml (C-08):
$flutter_hits"
    fi
else
    result "no-flutter-runner-pods-tokens" fail "$ios_yml not found on disk"
fi

# ---------------------------------------------------------------------------
# 13. m1+m4+m5+m6 still pass
#
# M7 cannot go green while an earlier invariant regresses.  Run m1_gate,
# m4_gate, m5_gate, and m6_gate capturing their output; surface only
# pass/fail + their own summary lines.  M7 fails if any exits non-zero.
# Mirrors exactly how m6_gate.sh chains its priors (m1+m4+m5).
# ---------------------------------------------------------------------------
echo "[13] m1+m4+m5+m6 still pass: running tool/m1_gate.sh + tool/m4_gate.sh + tool/m5_gate.sh + tool/m6_gate.sh ..."

m1_ok=true
m4_ok=true
m5_ok=true
m6_ok=true

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

if [[ -f "tool/m6_gate.sh" ]]; then
    m6_output=$(bash tool/m6_gate.sh 2>&1)
    m6_exit=$?
    m6_summary=$(echo "$m6_output" | grep "=== M6 gate:" | tail -1)
    if [[ "$m6_exit" -ne 0 ]]; then
        m6_ok=false
    fi
else
    m6_ok=false
    m6_summary="tool/m6_gate.sh not found"
fi

if $m1_ok && $m4_ok && $m5_ok && $m6_ok; then
    echo "  m1_gate: ${m1_summary}"
    echo "  m4_gate: ${m4_summary}"
    echo "  m5_gate: ${m5_summary}"
    echo "  m6_gate: ${m6_summary}"
    result "m1+m4+m5+m6-still-pass" ok
else
    if ! $m1_ok; then
        result "m1+m4+m5+m6-still-pass" fail "m1_gate FAILED — ${m1_summary}"
    elif ! $m4_ok; then
        result "m1+m4+m5+m6-still-pass" fail "m4_gate FAILED — ${m4_summary}"
    elif ! $m5_ok; then
        result "m1+m4+m5+m6-still-pass" fail "m5_gate FAILED — ${m5_summary}"
    else
        result "m1+m4+m5+m6-still-pass" fail "m6_gate FAILED — ${m6_summary}"
    fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
finish
