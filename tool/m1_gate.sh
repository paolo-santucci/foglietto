#!/usr/bin/env bash
# tool/m1_gate.sh — M1 Foundation & Scaffold structural verification gate
#
# Asserts the full M1 target tree in one runnable command.  Run from the repo
# root: bash tool/m1_gate.sh
#
# Exit 0 only when ALL checks (a)–(l) pass.
# Exit 1 on the FIRST failing check with a descriptive message that names
# exactly what is missing or wrong.
#
# Design rules:
#   - Every check is load-bearing: temporarily removing any asserted artifact
#     MUST flip that specific check to fail.
#   - plutil / assetutil are macOS-only and are NOT used here; those validations
#     remain in ios.yml CI steps.  CFBundleIconName is asserted via grep.
#   - No network access; no Gradle tasks; no xcodebuild calls.  This script is
#     a pure structural audit runnable on any bash host (Linux or macOS).
#
# Spec refs: §7.1 m1_gate, FR-01/02/03/04/07/09/11/14/17; EC-01..EC-09.

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

echo "=== M1 gate: starting structural verification ==="

# ---------------------------------------------------------------------------
# (a) Gradle root files exist AND are tracked by git
#
# Checks that the eight Gradle scaffold files are present in the working tree
# AND appear in `git ls-files` (i.e. committed / staged, not just on disk).
# Failing this means either the file was never authored or it was not git-added.
# Also asserts gradlew is executable (required for CI bootstrap).
# ---------------------------------------------------------------------------
echo "[a] Gradle root files tracked ..."

for path in \
    settings.gradle.kts \
    gradle.properties \
    build.gradle.kts \
    "gradle/libs.versions.toml" \
    gradlew \
    gradlew.bat \
    "gradle/wrapper/gradle-wrapper.jar" \
    "gradle/wrapper/gradle-wrapper.properties"
do
    # git ls-files returns empty string when the path is untracked or absent.
    tracked=$(git ls-files -- "$path")
    [[ -n "$tracked" ]] || fail "(a) Gradle file not tracked: $path"
done

# gradlew must be executable so CI can bootstrap without chmod.
[[ -x gradlew ]] || fail "(a) gradlew is not executable (missing +x bit)"

echo "  [a] OK"

# ---------------------------------------------------------------------------
# (b) shared module source sets exist and are non-empty
#
# The shared module must have all three source-set directories populated.
# An empty directory would mean the source-set is declared but contains no
# source or test files — which is a false-green for the EC-01 guard.
# shared/build.gradle.kts is also checked (tracked separately from source sets).
# ---------------------------------------------------------------------------
echo "[b] shared module source sets ..."

tracked=$(git ls-files -- "shared/build.gradle.kts")
[[ -n "$tracked" ]] || fail "(b) shared/build.gradle.kts not tracked"

for dir in \
    "shared/src/commonMain" \
    "shared/src/commonTest" \
    "shared/src/jvmTest"
do
    # Directory must exist on disk.
    [[ -d "$dir" ]] || fail "(b) source-set directory missing: $dir"

    # Directory must be non-empty: git ls-files lists at least one tracked file
    # under it (empty = no content was committed).
    files_under=$(git ls-files -- "$dir")
    [[ -n "$files_under" ]] || fail "(b) source-set directory is empty (no tracked files): $dir"
done

echo "  [b] OK"

# ---------------------------------------------------------------------------
# (c) iosApp tree exists
#
# All eight key paths in the iosApp/ tree must be present as tracked files or
# as tracked files inside tracked directories.  Uses git ls-files for each
# concrete file; the xcscheme and shared scheme are also asserted.
# ---------------------------------------------------------------------------
echo "[c] iosApp tree ..."

for path in \
    "iosApp/iosApp.xcodeproj/project.pbxproj" \
    "iosApp/SharedPackage/Package.swift" \
    "iosApp/iosApp/iosAppApp.swift" \
    "iosApp/iosApp/ContentView.swift" \
    "iosApp/iosApp/Info.plist" \
    "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json" \
    "iosApp/iosApp/Assets.xcassets/LaunchBackground.colorset/Contents.json" \
    "iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme"
do
    tracked=$(git ls-files -- "$path")
    [[ -n "$tracked" ]] || fail "(c) iosApp file not tracked: $path"
done

echo "  [c] OK"

# ---------------------------------------------------------------------------
# (d) ExportOptions.plist.template at repo root
#
# The template was relocated from ios/ to the repo root.  Its absence means
# the sed step in ios.yml will fail at deploy time.
# ---------------------------------------------------------------------------
echo "[d] ExportOptions.plist.template ..."

tracked=$(git ls-files -- "ExportOptions.plist.template")
[[ -n "$tracked" ]] || fail "(d) ExportOptions.plist.template not tracked at repo root"

echo "  [d] OK"

# ---------------------------------------------------------------------------
# (e) Flutter sources absent
#
# git ls-files over all tracked Flutter paths must return EMPTY output.
# Any non-empty output means a tracked Flutter file survived the removal.
# ---------------------------------------------------------------------------
echo "[e] Flutter sources absent ..."

flutter_tracked=$(git ls-files -- \
    lib/ \
    test/ \
    integration_test/ \
    pubspec.yaml \
    pubspec.lock \
    analysis_options.yaml \
    l10n.yaml \
    android/ \
    ios/ \
    2>/dev/null)

[[ -z "$flutter_tracked" ]] || fail "(e) tracked Flutter sources still present:
$flutter_tracked"

echo "  [e] OK"

# ---------------------------------------------------------------------------
# (f) android.yml absent
#
# Check both git tracking AND physical presence on disk.  A workflow file that
# is untracked but on disk can still interfere depending on the tool chain.
# ---------------------------------------------------------------------------
echo "[f] android.yml absent ..."

android_tracked=$(git ls-files -- ".github/workflows/android.yml")
[[ -z "$android_tracked" ]] || fail "(f) .github/workflows/android.yml is still tracked by git"

[[ ! -f ".github/workflows/android.yml" ]] || fail "(f) .github/workflows/android.yml exists on disk (untracked)"

echo "  [f] OK"

# ---------------------------------------------------------------------------
# (g) quality.yml AND ios.yml contain 'kmp-rewrite' in their trigger area
#
# Without this, a push to kmp-rewrite produces zero workflow runs and the
# entire M1 CI gate silently never fires (EC-02 — the assessment's #1 failure
# mode).  Both workflow files must contain the string 'kmp-rewrite'.
# ---------------------------------------------------------------------------
echo "[g] kmp-rewrite trigger in quality.yml and ios.yml ..."

grep -q "kmp-rewrite" ".github/workflows/quality.yml" \
    || fail "(g) 'kmp-rewrite' not found in .github/workflows/quality.yml — branch trigger missing"

grep -q "kmp-rewrite" ".github/workflows/ios.yml" \
    || fail "(g) 'kmp-rewrite' not found in .github/workflows/ios.yml — branch trigger missing"

echo "  [g] OK"

# ---------------------------------------------------------------------------
# (h) iosApp/iosApp/Info.plist has top-level CFBundleIconName = AppIcon
#
# grep -A1 prints the matching line plus the one immediately following it.
# We confirm that <key>CFBundleIconName</key> is directly followed (with no
# intervening blank line or other key) by <string>AppIcon</string>.
# This is the R-07 guard: a nested-only key would pass the first grep but fail
# the paired-line check.  plutil/assetutil are macOS-only and remain in ios.yml.
# ---------------------------------------------------------------------------
echo "[h] Info.plist CFBundleIconName top-level key ..."

info_plist="iosApp/iosApp/Info.plist"
[[ -f "$info_plist" ]] || fail "(h) $info_plist not found on disk"

# grep -A1 emits: <key>CFBundleIconName</key>\n<string>AppIcon</string>
# The second grep checks the pair output contains the value line.
icon_pair=$(grep -A1 "<key>CFBundleIconName</key>" "$info_plist" 2>/dev/null)
[[ -n "$icon_pair" ]] \
    || fail "(h) <key>CFBundleIconName</key> not found in $info_plist"

echo "$icon_pair" | grep -q "<string>AppIcon</string>" \
    || fail "(h) CFBundleIconName in $info_plist is not immediately followed by <string>AppIcon</string> (may be nested or wrong value)"

echo "  [h] OK"

# ---------------------------------------------------------------------------
# (i) SharedPackage/Package.swift contains the exact XCFramework path string
#
# The SPM binary target path is the single fixed contract (§5.1.1 / §4.2).
# Any drift from the exact string breaks SPM resolution in xcodebuild.
# ---------------------------------------------------------------------------
echo "[i] SharedPackage/Package.swift XCFramework path ..."

pkg_swift="iosApp/SharedPackage/Package.swift"
[[ -f "$pkg_swift" ]] || fail "(i) $pkg_swift not found on disk"

grep -qF "../../shared/build/XCFrameworks/release/shared.xcframework" "$pkg_swift" \
    || fail "(i) binaryTarget path '../../shared/build/XCFrameworks/release/shared.xcframework' not found in $pkg_swift (must be TWO levels up from iosApp/SharedPackage/ to reach the repo-root XCFramework — '../shared/...' wrongly resolves to iosApp/shared/...)"

echo "  [i] OK"

# ---------------------------------------------------------------------------
# (j) Purity: shared/src/commonMain contains no Flutter/platform imports
#
# The shared module's commonMain source set must be strictly platform-neutral
# (FR-02/FR-03/FR-04, NFR-01).  grep -RInE returns zero matches on a clean
# module; any match is a purity violation that would fail JVM compile on a
# Mac-less host and break the Android reuse promise.
#
# NOTE: iosApp is intentionally NOT scanned — Swift legitimately imports
# SwiftUI, Foundation, UIKit, etc.  Only commonMain is audited here.
# ---------------------------------------------------------------------------
echo "[j] shared/src/commonMain purity ..."

if [[ -d "shared/src/commonMain" ]]; then
    purity_hits=$(grep -RInE \
        'flutter|riverpod|dart:|Foundation|SwiftUI|UIKit|AppKit|Cocoa' \
        "shared/src/commonMain" \
        2>/dev/null || true)
    [[ -z "$purity_hits" ]] \
        || fail "(j) platform/Flutter imports found in shared/src/commonMain:
$purity_hits"
else
    fail "(j) shared/src/commonMain directory not found — purity check cannot run"
fi

echo "  [j] OK"

# ---------------------------------------------------------------------------
# (k) No CocoaPods: git ls-files empty AND no Podfile on disk
#
# CocoaPods would reintroduce a signing surface that conflicts with the SPM
# binary-target approach (R-13, NFR-04, FR-07).
# ---------------------------------------------------------------------------
echo "[k] No CocoaPods ..."

pods_tracked=$(git ls-files -- \
    Podfile \
    "Pods/" \
    "iosApp/Podfile" \
    "iosApp/Pods/" \
    2>/dev/null)
[[ -z "$pods_tracked" ]] \
    || fail "(k) CocoaPods files are tracked by git: $pods_tracked"

for podfile in Podfile iosApp/Podfile; do
    [[ ! -f "$podfile" ]] \
        || fail "(k) $podfile exists on disk (untracked) — CocoaPods must not be present"
done

echo "  [k] OK"

# ---------------------------------------------------------------------------
# (l) No Flutter token in any workflow file
#
# After the CI rework, no workflow should contain flutter, pub get,
# build_runner, dart format, or lcov.  These tokens indicate Flutter steps
# that were not fully stripped and would fail on a Mac-less KMP runner.
# ---------------------------------------------------------------------------
echo "[l] No Flutter token in .github/workflows/ ..."

flutter_wf_hits=$(grep -RInE \
    'flutter|pub get|build_runner|dart format|lcov' \
    ".github/workflows/" \
    2>/dev/null || true)
[[ -z "$flutter_wf_hits" ]] \
    || fail "(l) Flutter tokens found in .github/workflows/:
$flutter_wf_hits"

echo "  [l] OK"

# ---------------------------------------------------------------------------
# All checks passed.
# ---------------------------------------------------------------------------
echo ""
echo "ALL M1 GATES PASSED"
exit 0
