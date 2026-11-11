#!/usr/bin/env bash
# tool/m8_gate.sh — M8 Android (Jetpack Compose) structural verification gate
#
# Asserts the Android front-end's structural invariants in one runnable command.
# Run from the repo root: bash tool/m8_gate.sh
#
# Plan: TASK-24 of sp-20261111-android-compose-app-plan (FR-39, FR-40).
# Spec refs: FR-02, FR-04, FR-06, FR-30, FR-35, FR-40, FR-41; NFR-04, NFR-06,
#            NFR-07, NFR-09; OQ-15, OQ-20; spec §7.2 source-scan inventory.
#
# Exit 0 only when ALL checks (1)–(25) pass.
# Each check prints a labelled PASS/FAIL line; the script exits 1 on any FAIL.
#
# Design rules:
#   - Checks (1)–(24) are pure source scans: no network, no device/emulator, no
#     Gradle invocation.  `./gradlew check` is CI's job.  Check (25) is the chain
#     into the prior gates; any Gradle cost a caller sees comes from m4_gate's own
#     SourceScanGateTest passthrough, which already runs in CI today.
#   - Mirrors the structure and idioms of tool/m4_gate.sh / tool/m5_gate.sh exactly:
#     set -uo pipefail, PASS/FAIL counters, result()/finish() helpers, presence via
#     `git ls-files`, absence via `grep -RIn`, prior gates chained by exit code.
#   - Comment-line exclusion is load-bearing, not cosmetic: several COMPLIANT files
#     document the forbidden token in KDoc — shared/src/androidMain/AndroidContext.kt
#     says "imports nothing from androidx.compose.*", AppContainer.kt and
#     MainActivity.kt both name ProcessLifecycleOwner, EditorActionRow.kt and
#     Motion.kt both name imePadding()/ANIMATOR_DURATION_SCALE.  Every scan drops
#     hits whose content starts with //, /*, * or # (drop_comment_lines).
#   - XML is scanned with the comments stripped FIRST (strip_xml_comments): the
#     source manifest header literally contains "<uses-permission>" while declaring
#     none, and a line-based filter cannot see inside a block comment.
#   - The MERGED manifest (androidApp/build/intermediates/.../AndroidManifest.xml)
#     is never scanned: androidx injects a signature-level permission at merge time,
#     so scanning merged output would be a guaranteed false positive.  Every broad
#     grep carries --exclude-dir=build for the same reason.
#   - Vacuous passes only for genuinely optional artifacts (spec: the launcher icon
#     is a "Could", FR-41).  Every other target tree exists by TASK-24, so a missing
#     directory/file is a FAIL naming the remedy — never a silent pass.
#
# Deliberate gaps — spec §7.2 bullets NOT encoded here, with the reason.  They are
# dropped rather than faked; do not add a token scan to "cover" them later:
#   * "0 forked domain rules" (list continuation, indent/outdent, preview truncation,
#     filename grammar, save/trim, subtitle formatting, share gating).  A regex
#     cannot distinguish "delegates to the shared rule" from "re-derives it" without
#     a call graph: every candidate token also matches the legitimate Android call
#     sites that WRAP the shared rule (rowLabel, actionRowEnabled, provideMotion,
#     actionRowChrome).  Such a scan would be vacuous or false-positive-only.  The
#     invariant is pinned behaviourally by the Tier A/B tests that assert each
#     Android call resolves to the shared implementation (spec §7.1/§7.2).
#   * "every IconButton/Icon carries a contentDescription resolving to a string
#     resource".  The action row draws its glyphs on a Canvas, so there are no
#     Icon( call sites to audit; a per-call-site rule would scan nothing.  Check (15)
#     pins the neighbouring property instead: no hard-coded literal may reach a
#     Text/contentDescription sink.
#
# Negative control — proves the gate bites.  Run each mutation on a scratch copy of
# the tree (or a throwaway commit on a personal branch); NEVER commit a mutation.
#   A) append `private const val Leak = "Share"` to
#      shared/src/androidMain/kotlin/com/paolosantucci/foglietto/shared/AndroidContext.kt
#        -> (2) androidMain-purity FAILs
#   B) delete the `<string name="redo">…</string>` line from
#      androidApp/src/main/res/values-it/strings.xml
#        -> (6) string-key-parity FAILs
#   C) add `import okio.FileSystem` to
#      androidApp/src/main/java/com/paolosantucci/foglietto/AppContainer.kt
#        -> (18) no-okio-in-app FAILs
#   D) add `id("org.jetbrains.kotlin.android")` to the plugins { } block of
#      androidApp/build.gradle.kts
#        -> (3) no-kotlin-android-plugin FAILs
#   E) replace a `stringResource(R.string.share)` argument with `Text("Share")`
#        -> (15) no-hardcoded-ui-copy FAILs
#   After reverting the mutation the unmutated tree must exit 0 again.
#
# Do NOT amend m1_gate.sh here, and do not create .github/workflows/android.yml:
# m1_gate check (f) bans that workflow and this gate chains m1.

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
    echo "=== M8 gate: $PASS passed, $FAIL failed ==="
    if [[ "$FAIL" -gt 0 ]]; then
        exit 1
    fi
    exit 0
}

# ---------------------------------------------------------------------------
# drop_comment_lines
#   stdin: grep -Hn / -RIn output, "path:linenum:content".
#   stdout: the same lines minus those whose CONTENT starts with a comment
#           marker (//, /*, the KDoc continuation '*', or a '#' line comment).
#   Every absence scan in this script is piped through it (m4/m5 idiom).
# ---------------------------------------------------------------------------
drop_comment_lines() {
    grep -Ev '^[^:]+:[0-9]+:[[:space:]]*(//|/\*|\*|#)' || true
}

# ---------------------------------------------------------------------------
# strip_xml_comments <file>
#   stdout: "<linenum>: <content>" for every line, with <!-- … --> blocks removed
#   (including multi-line blocks).  Needed because the source manifest documents
#   "<uses-permission>" inside its header comment while declaring none.
# ---------------------------------------------------------------------------
strip_xml_comments() {
    awk '
        BEGIN { incomment = 0 }
        {
            out = ""
            rest = $0
            while (length(rest) > 0) {
                if (incomment) {
                    i = index(rest, "-->")
                    if (i == 0) {
                        rest = ""
                    } else {
                        rest = substr(rest, i + 3)
                        incomment = 0
                    }
                } else {
                    i = index(rest, "<!--")
                    if (i == 0) {
                        out = out rest
                        rest = ""
                    } else {
                        out = out substr(rest, 1, i - 1)
                        rest = substr(rest, i + 4)
                        incomment = 1
                    }
                }
            }
            if (out ~ /[^[:space:]]/) print FNR ": " out
        }
    ' "$1"
}

# ---------------------------------------------------------------------------
# extract_string_keys <strings.xml>
#   stdout: the sorted, de-duplicated set of <string name="…"> keys.
# ---------------------------------------------------------------------------
extract_string_keys() {
    grep -oE '<string name="[^"]+"' "$1" 2>/dev/null \
        | sed -E 's/^.*name="([^"]+)"$/\1/' \
        | sort -u
}

# ---------------------------------------------------------------------------
# xml_strings_wellformed <strings.xml>
#   Structural well-formedness proxy (no xmllint dependency): a non-empty file
#   with balanced <resources>…</resources>, at least one key, and one </string>
#   per <string name="…"> opening element.  A truncated or half-edited file
#   (the realistic failure: a key deleted mid-element) cannot satisfy this.
# ---------------------------------------------------------------------------
xml_strings_wellformed() {
    local f="$1"
    local open_count close_count
    [[ -f "$f" ]] || return 1
    [[ -s "$f" ]] || return 1
    grep -q '<resources>' "$f" 2>/dev/null || return 1
    grep -q '</resources>' "$f" 2>/dev/null || return 1
    open_count=$(grep -c '<string name="' "$f" 2>/dev/null || true)
    close_count=$(grep -c '</string>' "$f" 2>/dev/null || true)
    [[ "$open_count" -gt 0 ]] || return 1
    [[ "$open_count" = "$close_count" ]] || return 1
    return 0
}

echo "=== M8 gate: starting Android structural verification ==="
echo ""

# Shared path constants.
app_main="androidApp/src/main"
app_kt="$app_main/java/com/paolosantucci/foglietto"
app_build="androidApp/build.gradle.kts"
app_container="$app_kt/AppContainer.kt"
main_activity="$app_kt/MainActivity.kt"
app_application="$app_kt/FogliettoApplication.kt"
manifest="$app_main/AndroidManifest.xml"
en_strings="$app_main/res/values/strings.xml"
it_strings="$app_main/res/values-it/strings.xml"
shared_build="shared/build.gradle.kts"
catalog="gradle/libs.versions.toml"

# Display-copy vocabulary: a quoted literal STARTING with one of these words is
# presentation copy, not a technical constant (the technical strings this tree
# legitimately holds are lowercase keys like "recovery", the "T…" filename
# prefix, "UTC", and developer-facing exception messages, none of which match).
ui_copy_re='"(Clear|Undo|Redo|Indent|Outdent|Share|Restore|Recent|About|Website|Report|Menu|Settings|Search|Find|Note|Notes|Editor|Copy|Paste|Cancel|Close|Open)'

# ---------------------------------------------------------------------------
# 1. commonMain-purity
#
# NFR-07 / FR-02: the shared module is domain + data only.  commonMain must hold
# zero display strings and zero android/androidx imports (the iOS side already
# pins the zero-UI-strings half in m6_gate check 4; this is the Android-facing
# half and is independent of m6 so the line is meaningful on its own).
# ---------------------------------------------------------------------------
echo "[1] commonMain-purity: shared/src/commonMain holds 0 display strings and 0 android/androidx imports ..."

common_main="shared/src/commonMain"
if [[ ! -d "$common_main" ]]; then
    result "commonMain-purity" fail "$common_main does not exist — the shared module tree is missing (restore it; m1_gate check (b) also fails)"
else
    cm_imports=$(grep -RInE --include='*.kt' '^[[:space:]]*import[[:space:]]+(android|androidx)\.' "$common_main" 2>/dev/null | drop_comment_lines)
    cm_strings=$(grep -RInE --include='*.kt' "$ui_copy_re" "$common_main" 2>/dev/null | drop_comment_lines)
    if [[ -z "$cm_imports" && -z "$cm_strings" ]]; then
        result "commonMain-purity" ok
    else
        cm_detail=""
        if [[ -n "$cm_imports" ]]; then
            cm_detail="${cm_detail}android/androidx import(s) in commonMain (NFR-07 — platform types must stay in the platform source sets):
$cm_imports
"
        fi
        if [[ -n "$cm_strings" ]]; then
            cm_detail="${cm_detail}display-string literal(s) in commonMain (NFR-07 — shared holds zero UI copy):
$cm_strings
"
        fi
        result "commonMain-purity" fail "$cm_detail"
    fi
fi

# ---------------------------------------------------------------------------
# 2. androidMain-purity
#
# NFR-07: shared/src/androidMain is the Android data/platform seam, not a UI
# layer.  Zero display strings and zero androidx.compose (or any other UI
# toolkit) imports.
#
# TRAP: AndroidContext.kt's own KDoc says "imports nothing from `androidx.compose.*`"
# — hence the non-comment filter on both scans.
# ---------------------------------------------------------------------------
echo "[2] androidMain-purity: shared/src/androidMain holds 0 display strings and 0 androidx.compose imports ..."

android_main="shared/src/androidMain"
if [[ ! -d "$android_main" ]]; then
    result "androidMain-purity" fail "$android_main does not exist — the Android target source set is missing"
else
    am_ui=$(grep -RInE --include='*.kt' 'androidx\.compose|androidx\.appcompat|com\.google\.android\.material' "$android_main" 2>/dev/null | drop_comment_lines)
    am_strings=$(grep -RInE --include='*.kt' "$ui_copy_re" "$android_main" 2>/dev/null | drop_comment_lines)
    if [[ -z "$am_ui" && -z "$am_strings" ]]; then
        result "androidMain-purity" ok
    else
        am_detail=""
        if [[ -n "$am_ui" ]]; then
            am_detail="${am_detail}UI-framework import(s) in androidMain (NFR-07 — the shared module has no presentation layer):
$am_ui
"
        fi
        if [[ -n "$am_strings" ]]; then
            am_detail="${am_detail}display-string literal(s) in androidMain (NFR-07 — copy lives in androidApp string resources):
$am_strings
"
        fi
        result "androidMain-purity" fail "$am_detail"
    fi
fi

# ---------------------------------------------------------------------------
# 3. no-kotlin-android-plugin
#
# FR-03: AGP 9 has built-in Kotlin.  `org.jetbrains.kotlin.android` must appear
# nowhere in any build script, version catalog or gradle.properties — applying it
# (or even declaring the alias) breaks the AGP-9 DSL.  Tracked + untracked-not-
# ignored build files are scanned so a half-staged edit cannot slip through.
# ---------------------------------------------------------------------------
echo "[3] no-kotlin-android-plugin: 'org.jetbrains.kotlin.android' appears in no *.kts / *.toml / *.properties ..."

build_files=$(git ls-files -co --exclude-standard -- '*.kts' '*.toml' '*.properties' 2>/dev/null || true)
if [[ -n "$build_files" ]]; then
    kotlin_android_hits=$(printf '%s\n' "$build_files" | xargs grep -HnE 'org\.jetbrains\.kotlin\.android' 2>/dev/null | drop_comment_lines)
else
    # Not a git checkout, or no build files tracked: fall back to a bounded disk scan.
    kotlin_android_hits=$(grep -RInE --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.kotlin \
        --include='*.kts' --include='*.toml' --include='*.properties' \
        'org\.jetbrains\.kotlin\.android' . 2>/dev/null | drop_comment_lines)
fi

if [[ -z "$kotlin_android_hits" ]]; then
    result "no-kotlin-android-plugin" ok
else
    result "no-kotlin-android-plugin" fail "org.jetbrains.kotlin.android is declared/applied (FR-03 — AGP 9 has built-in Kotlin; drop the alias/plugin):
$kotlin_android_hits"
fi

# ---------------------------------------------------------------------------
# 4. no-android-workflow
#
# FR-39 / OQ-20: Android verification lives inside quality.yml.  A separate
# android.yml is banned (m1_gate check (f) bans it too; chained here so this gate
# is self-contained).  Both tracked-ness and on-disk presence are asserted — an
# untracked-but-present workflow still runs on push.
# ---------------------------------------------------------------------------
echo "[4] no-android-workflow: .github/workflows/android.yml is neither tracked nor on disk ..."

android_yml_tracked=$(git ls-files -- ".github/workflows/android.yml" 2>/dev/null || true)
if [[ -n "$android_yml_tracked" ]]; then
    result "no-android-workflow" fail ".github/workflows/android.yml is tracked by git (FR-39/OQ-20 — Android checks belong in quality.yml)"
elif [[ -f ".github/workflows/android.yml" ]]; then
    result "no-android-workflow" fail ".github/workflows/android.yml exists on disk (untracked) — delete it; it would run alongside quality.yml"
else
    result "no-android-workflow" ok
fi

# ---------------------------------------------------------------------------
# 5. android-sdk-levels
#
# FR-03: androidApp pins compileSdk/targetSdk 36 and minSdk 26.  A drift here
# silently changes the platform surface the whole app is verified against.
# ---------------------------------------------------------------------------
echo "[5] android-sdk-levels: androidApp/build.gradle.kts declares compileSdk 36, targetSdk 36, minSdk 26 ..."

if [[ ! -f "$app_build" ]]; then
    result "android-sdk-levels" fail "$app_build not found on disk (FR-03 — the app module cannot be verified)"
else
    sdk_lines=$(grep -HnE '^[[:space:]]*(compileSdk|targetSdk|minSdk)[[:space:]]*=' "$app_build" 2>/dev/null | drop_comment_lines)
    sdk_missing=""
    grep -qE 'compileSdk[[:space:]]*=[[:space:]]*36([^0-9]|$)' <<< "$sdk_lines" || sdk_missing="${sdk_missing} compileSdk=36"
    grep -qE 'targetSdk[[:space:]]*=[[:space:]]*36([^0-9]|$)' <<< "$sdk_lines" || sdk_missing="${sdk_missing} targetSdk=36"
    grep -qE 'minSdk[[:space:]]*=[[:space:]]*26([^0-9]|$)' <<< "$sdk_lines" || sdk_missing="${sdk_missing} minSdk=26"
    if [[ -z "$sdk_missing" ]]; then
        result "android-sdk-levels" ok
    else
        result "android-sdk-levels" fail "missing/unpinned SDK level(s):${sdk_missing} in $app_build (FR-03)"
    fi
fi

# ---------------------------------------------------------------------------
# 6. string-key-parity
#
# NFR-06: values/strings.xml (EN) and values-it/strings.xml (IT) must expose the
# same key set, and both files must parse as well-formed string resources.
#
# NOTE (deliberate divergence from the plan's wording): the plan asks for "the
# literal 17-key inventory" to be pinned here.  That count is already pinned in
# the Tier A test `res/StringInventoryTest`, which fails with a precise diff when
# copy changes intentionally.  Hard-coding 17 in a structural gate would make any
# legitimate key addition require a gate edit (and push authors toward weakening
# the gate).  So this check pins the property that actually matters structurally:
# both files parse, both are non-empty, and the key SETS are identical.
# ---------------------------------------------------------------------------
echo "[6] string-key-parity: EN and IT strings.xml parse and expose identical key sets ..."

if ! xml_strings_wellformed "$en_strings"; then
    result "string-key-parity" fail "$en_strings is missing, empty, or structurally malformed (NFR-06 — unbalanced <string> elements)"
elif ! xml_strings_wellformed "$it_strings"; then
    result "string-key-parity" fail "$it_strings is missing, empty, or structurally malformed (NFR-06 — every EN key needs an IT counterpart)"
else
    en_keys=$(extract_string_keys "$en_strings")
    it_keys=$(extract_string_keys "$it_strings")
    key_diff=$(diff <(printf '%s\n' "$en_keys") <(printf '%s\n' "$it_keys") 2>/dev/null || true)
    if [[ -z "$en_keys" ]]; then
        result "string-key-parity" fail "$en_strings exposes zero <string name=…> keys (NFR-06)"
    elif [[ -z "$it_keys" ]]; then
        result "string-key-parity" fail "$it_strings exposes zero <string name=…> keys (NFR-06)"
    elif [[ -n "$key_diff" ]]; then
        result "string-key-parity" fail "EN/IT key sets differ ('<' = only EN, '>' = only IT) — add the missing translation (NFR-06):
$key_diff"
    else
        result "string-key-parity" ok
    fi
fi

# ---------------------------------------------------------------------------
# 7. manifest-zero-permissions
#
# FR-40 / NFR-04 least privilege: the SOURCE manifest declares zero
# <uses-permission> elements.  The recovery store is app-private and sharing goes
# out through ACTION_SEND, so the provably required set is empty.
#
# TRAP (two of them):
#   a) the manifest's own header comment contains the literal "<uses-permission>"
#      while declaring none -> comments are stripped before the scan;
#   b) the MERGED manifest gains a signature-level androidx permission at merge
#      time -> only androidApp/src/main/AndroidManifest.xml is ever read.
# ---------------------------------------------------------------------------
echo "[7] manifest-zero-permissions: androidApp/src/main/AndroidManifest.xml declares zero <uses-permission> ..."

if [[ ! -f "$manifest" ]]; then
    result "manifest-zero-permissions" fail "$manifest not found on disk (FR-40 — least privilege cannot be verified)"
else
    permission_hits=$(strip_xml_comments "$manifest" | grep -E '<uses-permission' || true)
    if [[ -z "$permission_hits" ]]; then
        result "manifest-zero-permissions" ok
    else
        result "manifest-zero-permissions" fail "<uses-permission> declared in $manifest (NFR-04 — the app asks the platform for nothing):
$permission_hits"
    fi
fi

# ---------------------------------------------------------------------------
# 8. no-internet-permission
#
# FR-40: no INTERNET permission anywhere — not in a manifest, not in Kotlin, not
# in a test fixture that could be copied into a manifest later.  The app has no
# network surface.
# The merged manifest is excluded (--exclude-dir=build): the merge adds androidx's
# signature-level permission, which is not an INTERNET grant but lives in the same
# file, and scanning generated output is never the invariant.
# ---------------------------------------------------------------------------
echo "[8] no-internet-permission: zero INTERNET references in androidApp/ and shared/ sources ..."

if [[ ! -d androidApp ]] && [[ ! -d shared ]]; then
    result "no-internet-permission" fail "neither androidApp/ nor shared/ exists — INTERNET absence cannot be verified"
else
    internet_hits=$(grep -RInI --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.kotlin \
        'INTERNET' androidApp shared 2>/dev/null | drop_comment_lines)
    if [[ -z "$internet_hits" ]]; then
        result "no-internet-permission" ok
    else
        result "no-internet-permission" fail "INTERNET reference(s) found (FR-40 — the app has no network surface):
$internet_hits"
    fi
fi

# ---------------------------------------------------------------------------
# 9. app-private-storage
#
# NFR-09: recovery lives in <filesDir>/recovery — app-private internal storage.
# Zero getExternalFilesDir / Environment.getExternalStorage* references in the
# production sources (app + shared Android seam).
#
# Scope is production sources only, deliberately: the Robolectric suite asserts
# the recovery path is NOT under getExternalFilesDir(null), so the string occurs
# legitimately in shared/src/androidUnitTest.
# ---------------------------------------------------------------------------
echo "[9] app-private-storage: zero getExternalFilesDir / Environment.getExternalStorage* in app + shared androidMain ..."

storage_hits=""
if [[ -d "$app_main" ]]; then
    storage_hits=$(grep -RInE --include='*.kt' 'getExternalFilesDir|Environment\.getExternalStorage' "$app_main" 2>/dev/null | drop_comment_lines)
fi
if [[ -d "$android_main" ]]; then
    storage_hits="${storage_hits}
$(grep -RInE --include='*.kt' 'getExternalFilesDir|Environment\.getExternalStorage' "$android_main" 2>/dev/null | drop_comment_lines)"
fi
storage_hits=$(printf '%s\n' "$storage_hits" | grep '[^[:space:]]' || true)

if [[ -z "$storage_hits" ]]; then
    result "app-private-storage" ok
else
    result "app-private-storage" fail "external-storage reference(s) in production sources (NFR-09 — recovery is <filesDir>/recovery, app-private only):
$storage_hits"
fi

# ---------------------------------------------------------------------------
# 10. no-delete-call-sites
#
# FR-30: recovery is restore-only.  Zero delete(/deleteAll( call sites in the
# app's production sources.
#
# Scope is androidApp/src/main on purpose: the test fakes legitimately OVERRIDE
# delete()/deleteAll() to prove the app never calls them, so scanning tests would
# invert the invariant.
# ---------------------------------------------------------------------------
echo "[10] no-delete-call-sites: zero delete( / deleteAll( in androidApp/src/main ..."

if [[ ! -d "$app_main" ]]; then
    result "no-delete-call-sites" fail "$app_main not found on disk (FR-30 — the restore-only surface cannot be verified)"
else
    delete_hits=$(grep -RInE --include='*.kt' '\bdelete(All)?\(' "$app_main" 2>/dev/null | drop_comment_lines)
    if [[ -z "$delete_hits" ]]; then
        result "no-delete-call-sites" ok
    else
        result "no-delete-call-sites" fail "delete call site(s) in production sources (FR-30 — recovery is restore-only; test fakes may override, production may not call):
$delete_hits"
    fi
fi

# ---------------------------------------------------------------------------
# 11. no-androidTest-source-set
#
# OQ-20: Tier C (instrumentation) is structurally excluded this wave.  The check
# is the DIRECTORY, never the token: AGP emits lint/bookkeeping tasks whose name
# contains "androidTest", so grepping for the word would false-positive forever.
# ---------------------------------------------------------------------------
echo "[11] no-androidTest-source-set: androidApp/src/androidTest does not exist ..."

if [[ -d "androidApp/src/androidTest" ]]; then
    result "no-androidTest-source-set" fail "androidApp/src/androidTest exists (OQ-20 — Tier C is structurally excluded this wave; an empty or stub source set is the classic false-green)"
else
    result "no-androidTest-source-set" ok
fi

# ---------------------------------------------------------------------------
# 12. single-animator-scale-reader
#
# FR-35 / NFR-05: exactly one reader of Settings.Global.ANIMATOR_DURATION_SCALE in
# the app — the motion seam (ui/chrome/Motion.kt).  A second reader forks the
# reduced-motion policy.
#
# TRAP: Motion.kt names the constant twice more in its own KDoc, so the count is
# taken after comment exclusion (exactly 1 non-comment occurrence).
# ---------------------------------------------------------------------------
echo "[12] single-animator-scale-reader: exactly one non-comment ANIMATOR_DURATION_SCALE reader in androidApp/src/main ..."

if [[ ! -d "$app_main" ]]; then
    result "single-animator-scale-reader" fail "$app_main not found on disk (FR-35 — the motion seam cannot be verified)"
else
    animator_hits=$(grep -RInE --include='*.kt' 'ANIMATOR_DURATION_SCALE' "$app_main" 2>/dev/null | drop_comment_lines)
    animator_count=$(printf '%s\n' "$animator_hits" | grep -c '[^[:space:]]' || true)
    if [[ "$animator_count" -eq 1 ]]; then
        result "single-animator-scale-reader" ok
    elif [[ "$animator_count" -eq 0 ]]; then
        result "single-animator-scale-reader" fail "no ANIMATOR_DURATION_SCALE reader in $app_main (FR-35/NFR-05 — reduced motion is unreadable, the chrome cannot collapse)"
    else
        result "single-animator-scale-reader" fail "$animator_count non-comment ANIMATOR_DURATION_SCALE occurrences — need exactly 1 (FR-35: one motion seam, ui/chrome/Motion.kt):
$animator_hits"
    fi
fi

# ---------------------------------------------------------------------------
# 13. process-lifecycle-single-site
#
# FR-14 structural pin: ProcessLifecycleOwner is referenced only from
# FogliettoApplication.kt.  If an Activity (or the shell) observes the process
# lifecycle itself, rotation/config-change inertness is lost.
#
# TRAP: MainActivity.kt and AppContainer.kt both NAME ProcessLifecycleOwner in
# KDoc while owning neither seam — comment exclusion is what makes this check
# true rather than a false failure.
# ---------------------------------------------------------------------------
echo "[13] process-lifecycle-single-site: ProcessLifecycleOwner referenced only from FogliettoApplication.kt ..."

if [[ ! -d "$app_main" ]]; then
    result "process-lifecycle-single-site" fail "$app_main not found on disk (FR-14 — the lifecycle wiring cannot be verified)"
else
    plo_hits=$(grep -RInE --include='*.kt' 'ProcessLifecycleOwner' "$app_main" 2>/dev/null | drop_comment_lines)
    if [[ -z "$plo_hits" ]]; then
        result "process-lifecycle-single-site" fail "ProcessLifecycleOwner is referenced nowhere in $app_main (FR-14 — the process-lifecycle observer wiring is missing)"
    else
        plo_other=$(printf '%s\n' "$plo_hits" | grep -v "^$app_application:" || true)
        plo_self=$(printf '%s\n' "$plo_hits" | grep -c "^$app_application:" || true)
        if [[ -n "$plo_other" ]]; then
            result "process-lifecycle-single-site" fail "ProcessLifecycleOwner referenced outside $app_application (FR-14 — the Activity and the shell own neither seam):
$plo_other"
        elif [[ "$plo_self" -lt 1 ]]; then
            result "process-lifecycle-single-site" fail "$app_application does not reference ProcessLifecycleOwner (FR-14 — nothing registers the process observer)"
        else
            result "process-lifecycle-single-site" ok
        fi
    fi
fi

# ---------------------------------------------------------------------------
# 14. mainactivity-no-graph
#
# §5.1 conformance rule 9 / FR-06: MainActivity owns neither the graph nor the
# save seam.  It may READ the container (one property) but must not name the save
# coordinator, a shared factory, the AndroidContext seam, or the stop hook.
#
# Narrow predicate, deliberately: the property `container: AppContainer` is the
# sanctioned read-only hand-off, so only the construction/save symbols are banned.
# ---------------------------------------------------------------------------
echo "[14] mainactivity-no-graph: MainActivity.kt names no save coordinator / shared factory / context seam ..."

if [[ ! -f "$main_activity" ]]; then
    result "mainactivity-no-graph" fail "$main_activity not found on disk (§5.1 rule 9 — the Activity ownership contract cannot be verified)"
else
    ma_hits=$(grep -HnE 'LifecycleSaveCoordinator|createAndroid|AndroidContext|onProcessStop' "$main_activity" 2>/dev/null | drop_comment_lines)
    if [[ -z "$ma_hits" ]]; then
        result "mainactivity-no-graph" ok
    else
        result "mainactivity-no-graph" fail "$main_activity references graph-construction / save symbols (§5.1 rule 9 — the composition root owns them):
$ma_hits"
    fi
fi

# ---------------------------------------------------------------------------
# 15. no-hardcoded-ui-copy
#
# NFR-04/NFR-06 + M8 invariant 15: no hard-coded user-visible literal in
# androidApp/src/main/**/*.kt.  Every Text( and contentDescription = must resolve
# to stringResource(...) or to a value passed in.
#
# Block-level heuristic, in three parts, so it cannot false-positive on
# `contentDescription = stringResource(...)` sitting on a different line than the
# icon (the failure mode a naive per-call-site rule has):
#   a) line hits:  Text("… or text = "… or contentDescription = "…
#   b) multi-line: a line ending in Text( / text = whose NEXT line opens with a
#      string literal (grep -A1 context lines are "path-line-content").
#   c) the sink must not be a comment (KDoc examples are documentation).
# Note: AboutSheetModel's proper-name constants (APP_NAME/AUTHOR/LICENSE) are
# values PASSED IN to Text(...) — the sanctioned alternative — so they are not
# flagged here; the sink is what this check owns.
# ---------------------------------------------------------------------------
echo "[15] no-hardcoded-ui-copy: no literal argument reaches Text( / contentDescription = in androidApp/src/main ..."

if [[ ! -d "$app_main" ]]; then
    result "no-hardcoded-ui-copy" fail "$app_main not found on disk (NFR-04 — icon/text labelling cannot be verified)"
else
    literal_hits=$(grep -RInHE --include='*.kt' '(Text\(|text[[:space:]]*=|contentDescription[[:space:]]*=)[[:space:]]*"' "$app_main" 2>/dev/null | drop_comment_lines)
    literal_ml_hits=$(grep -RIE -A1 --include='*.kt' '(Text\(|text[[:space:]]*=)[[:space:]]*$' "$app_main" 2>/dev/null \
        | grep -E '^[^:]+-[0-9]+-[[:space:]]*"' || true)
    if [[ -z "$literal_hits" && -z "$literal_ml_hits" ]]; then
        result "no-hardcoded-ui-copy" ok
    else
        copy_detail=""
        if [[ -n "$literal_hits" ]]; then
            copy_detail="${copy_detail}hard-coded literal at a Text/contentDescription sink (NFR-04 — use stringResource(R.string.…)):
$literal_hits
"
        fi
        if [[ -n "$literal_ml_hits" ]]; then
            copy_detail="${copy_detail}string literal opening the argument block of a Text/text = call (NFR-04 — use stringResource(R.string.…)):
$literal_ml_hits
"
        fi
        result "no-hardcoded-ui-copy" fail "$copy_detail"
    fi
fi

# ---------------------------------------------------------------------------
# 16. imePadding-in-action-row
#
# EC-27 / FR-35 structural pin: the bottom action row applies imePadding(), which
# is what floats it above the open IME instead of letting the keyboard cover it.
#
# The row is located by its composable declaration (not by path) so a file move
# does not silently turn this check vacuous; if the row is ever inlined into the
# shell the gate fails loudly and the predicate must be re-pointed.
# TRAP: EditorActionRow.kt's KDoc names imePadding() (the "structural pin" prose),
# so the hit count is taken after comment exclusion — annotation-less line-based
# counting would still pass, but the message must not point at the KDoc.
# ---------------------------------------------------------------------------
echo "[16] imePadding-in-action-row: the action-row composable applies imePadding() ..."

if [[ ! -d "$app_main" ]]; then
    result "imePadding-in-action-row" fail "$app_main not found on disk (EC-27 — IME insets cannot be verified)"
else
    action_row_file=$(grep -RIl --include='*.kt' 'fun EditorActionRow(' "$app_main" 2>/dev/null | head -1)
    if [[ -z "$action_row_file" ]]; then
        result "imePadding-in-action-row" fail "no composable 'fun EditorActionRow(' found under $app_main (EC-27 — the IME-safe action row is missing or was inlined; re-point this check)"
    else
        ime_hits=$(grep -HnE 'imePadding\(\)' "$action_row_file" 2>/dev/null | drop_comment_lines)
        if [[ -z "$ime_hits" ]]; then
            result "imePadding-in-action-row" fail "$action_row_file does not apply imePadding() (EC-27 — the row will be covered by the keyboard):
$action_row_file"
        else
            result "imePadding-in-action-row" ok
        fi
    fi
fi

# ---------------------------------------------------------------------------
# 17. shared-no-compose-dependency
#
# FR-03 / NFR-07: shared/ declares no Android UI-framework dependency.  A Compose
# or appcompat entry here would drag presentation into the domain module and into
# the iOS build.
# ---------------------------------------------------------------------------
echo "[17] shared-no-compose-dependency: shared/build.gradle.kts declares no compose/appcompat/material dependency ..."

if [[ ! -f "$shared_build" ]]; then
    result "shared-no-compose-dependency" fail "$shared_build not found on disk (NFR-07 — the dependency contract cannot be verified)"
else
    compose_dep_hits=$(grep -HnE 'androidx\.compose|libs\.compose|androidx\.material|appcompat|libs\.androidx\.ui|libs\.androidx\.material3' "$shared_build" 2>/dev/null | drop_comment_lines)
    if [[ -z "$compose_dep_hits" ]]; then
        result "shared-no-compose-dependency" ok
    else
        compose_dep_hits_msg="$compose_dep_hits"
        result "shared-no-compose-dependency" fail "UI-framework dependency in $shared_build (FR-03/NFR-07 — shared is domain + data only):
$compose_dep_hits_msg"
    fi
fi

# ---------------------------------------------------------------------------
# 18. no-okio-in-app
#
# FR-06: repository access flows through the shared factories only.  Zero okio /
# multiplatform-settings IMPORTS anywhere under androidApp/ (production or test):
# their types must not cross the shared module boundary.
#
# TRAP: three KDoc blocks mention okio by name (AppContainer, LifecycleSaveCoordinator
# and two test headers) while importing nothing — comment exclusion is required.
# ---------------------------------------------------------------------------
echo "[18] no-okio-in-app: zero okio / multiplatform-settings imports under androidApp/ ..."

if [[ ! -d androidApp ]]; then
    result "no-okio-in-app" fail "androidApp/ not found on disk (FR-06 — the boundary cannot be verified)"
else
    okio_hits=$(grep -RInE --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.kotlin \
        '^[[:space:]]*import[[:space:]]+(okio|com\.russhwolf)' androidApp 2>/dev/null | drop_comment_lines)
    if [[ -z "$okio_hits" ]]; then
        result "no-okio-in-app" ok
    else
        result "no-okio-in-app" fail "okio / multiplatform-settings import(s) under androidApp/ (FR-06 — use createAndroidRecoveryRepository / createAndroidSettingsRepository):
$okio_hits"
    fi
fi

# ---------------------------------------------------------------------------
# 19. plugin-application-contract
#
# FR-03: the catalog maps exactly three Android/Kotlin plugins, shared/ applies the
# AGP-9 KMP library plugin, and androidApp/ applies the application plugin plus the
# Compose compiler plugin.  (The absence of org.jetbrains.kotlin.android is check 3.)
# ---------------------------------------------------------------------------
echo "[19] plugin-application-contract: catalog maps the 3 plugin ids; shared/ and androidApp/ apply the right aliases ..."

plugin_problems=""
if [[ ! -f "$catalog" ]]; then
    plugin_problems="${plugin_problems} $catalog missing;"
else
    for plugin_id in \
        'com.android.application' \
        'com.android.kotlin.multiplatform.library' \
        'org.jetbrains.kotlin.plugin.compose'
    do
        grep -qF "id = \"$plugin_id\"" "$catalog" \
            || plugin_problems="${plugin_problems} $catalog does not map $plugin_id;"
    done
fi
if [[ ! -f "$shared_build" ]]; then
    plugin_problems="${plugin_problems} $shared_build missing;"
elif ! grep -qF 'alias(libs.plugins.androidKotlinMultiplatformLibrary)' "$shared_build"; then
    plugin_problems="${plugin_problems} $shared_build does not apply alias(libs.plugins.androidKotlinMultiplatformLibrary);"
fi
if [[ ! -f "$app_build" ]]; then
    plugin_problems="${plugin_problems} $app_build missing;"
else
    grep -qF 'alias(libs.plugins.androidApplication)' "$app_build" \
        || plugin_problems="${plugin_problems} $app_build does not apply alias(libs.plugins.androidApplication);"
    grep -qF 'alias(libs.plugins.composeCompiler)' "$app_build" \
        || plugin_problems="${plugin_problems} $app_build does not apply alias(libs.plugins.composeCompiler);"
fi

if [[ -z "$plugin_problems" ]]; then
    result "plugin-application-contract" ok
else
    result "plugin-application-contract" fail "plugin wiring drift (FR-03):$plugin_problems"
fi

# ---------------------------------------------------------------------------
# 20. composition-root-order
#
# FR-04 / §5.1 rule 9: the composition root initialises the shared platform seam
# (AndroidContext.init) BEFORE constructing any shared factory, and the factories
# are constructed only inside AppContainer (never in the Activity or the shell).
#
# Ordering is asserted on the production CALL SITES (`factories.initAndroidContext(`
# must precede `factories.recovery()` / `factories.settings()`), not on the
# `::createAndroid*Repository` references: those appear earlier in the file as
# default-argument references, so a naive "first createAndroid line" check would
# fail on a perfectly ordered tree.
# ---------------------------------------------------------------------------
echo "[20] composition-root-order: initAndroidContext precedes the factory calls; factories only in AppContainer ..."

if [[ ! -f "$app_container" ]]; then
    result "composition-root-order" fail "$app_container not found on disk (FR-04 — startup ordering cannot be verified)"
elif [[ ! -d "$app_main" ]]; then
    result "composition-root-order" fail "$app_main not found on disk (FR-04 — startup ordering cannot be verified)"
else
    container_lines=$(grep -HnE 'initAndroidContext\(|factories\.recovery\(\)|factories\.settings\(\)' "$app_container" 2>/dev/null | drop_comment_lines)
    init_line=$(printf '%s\n' "$container_lines" | grep 'initAndroidContext(' | head -1 | cut -d: -f2)
    recovery_line=$(printf '%s\n' "$container_lines" | grep 'factories\.recovery()' | head -1 | cut -d: -f2)
    settings_line=$(printf '%s\n' "$container_lines" | grep 'factories\.settings()' | head -1 | cut -d: -f2)

    factory_sites=$(grep -RInE --include='*.kt' 'createAndroid(Recovery|Settings)Repository' "$app_main" 2>/dev/null | drop_comment_lines | grep -v "^$app_container:" || true)

    if [[ -z "$init_line" || -z "$recovery_line" || -z "$settings_line" ]]; then
        result "composition-root-order" fail "$app_container does not show the expected 'initAndroidContext(' → 'factories.recovery()' → 'factories.settings()' call sequence (FR-04 — update this check with the new composition-root shape):
$container_lines"
    elif [[ "$init_line" -gt "$recovery_line" ]] || [[ "$init_line" -gt "$settings_line" ]]; then
        result "composition-root-order" fail "AndroidContext.init (line $init_line) does not precede the factory calls (recovery:$recovery_line, settings:$settings_line) in $app_container (FR-04/EC-06 — a factory built first throws or resolves a wrong path)"
    elif [[ -n "$factory_sites" ]]; then
        result "composition-root-order" fail "shared factory referenced outside $app_container (§5.1 rule 9 — single construction site):
$factory_sites"
    else
        result "composition-root-order" ok
    fi
fi

# ---------------------------------------------------------------------------
# 21. no-editor-state-persistence
#
# §4.3 "not persisted, by design" / FR-10: the buffer, caret, selection and undo
# history are ephemeral.  Zero onSaveInstanceState / rememberSaveable in the app's
# production sources — the ON_STOP recovery save is the only persistence path.
#
# Scope is androidApp/src/main: ProcessLifecycleWiringTest legitimately asserts
# that no production class overrides onSaveInstanceState, so the token lives in
# the test sources by design.
# ---------------------------------------------------------------------------
echo "[21] no-editor-state-persistence: zero onSaveInstanceState / rememberSaveable in androidApp/src/main ..."

if [[ ! -d "$app_main" ]]; then
    result "no-editor-state-persistence" fail "$app_main not found on disk (§4.3 — ephemerality cannot be verified)"
else
    persist_hits=$(grep -RInE --include='*.kt' 'onSaveInstanceState|rememberSaveable' "$app_main" 2>/dev/null | drop_comment_lines)
    if [[ -z "$persist_hits" ]]; then
        result "no-editor-state-persistence" ok
    else
        result "no-editor-state-persistence" fail "state-persistence API(s) in production sources (§4.3/FR-10 — buffer/caret/selection/history are ephemeral):
$persist_hits"
    fi
fi

# ---------------------------------------------------------------------------
# 22. no-find-affordance
#
# FR-25: no Find/search affordance and no find/search string key anywhere in the
# Android app.  The iOS gate (m4 check 7) pins the same product decision.
#
# TRAP: two KDoc blocks say "no search bar"/"no Find affordance" while documenting
# the absence — comment exclusion keeps those out of the result.
# ---------------------------------------------------------------------------
echo "[22] no-find-affordance: zero Find/search affordances and zero find/search string keys ..."

if [[ ! -d "$app_main" ]]; then
    result "no-find-affordance" fail "$app_main not found on disk (FR-25 — the Find ban cannot be verified)"
else
    find_hits=$(grep -RInE --include='*.kt' '(findButton|FindAffordance|SearchBar|\bFind\b|\bSearch\b|find_[a-z]|search_[a-z])' "$app_main" 2>/dev/null | drop_comment_lines)
    find_keys=""
    if [[ -f "$en_strings" ]]; then
        find_keys=$( { extract_string_keys "$en_strings"; extract_string_keys "$it_strings"; } 2>/dev/null | grep -E '(^|_)(find|search)' || true)
    fi
    if [[ -z "$find_hits" && -z "$find_keys" ]]; then
        result "no-find-affordance" ok
    else
        find_detail=""
        if [[ -n "$find_hits" ]]; then
            find_detail="${find_detail}Find/search affordance in production sources (FR-25 — no Find exists in Foglietto):
$find_hits
"
        fi
        if [[ -n "$find_keys" ]]; then
            find_detail="${find_detail}find/search string key(s) in the string resources (FR-25):
$find_keys
"
        fi
        result "no-find-affordance" fail "$find_detail"
    fi
fi

# ---------------------------------------------------------------------------
# 23. launcher-icon-declared
#
# FR-41 (Could): if the manifest declares a launcher icon, the referenced adaptive
# icon resource must exist.  Vacuously PASS while no icon is declared (spec: the
# icon is a Could, so its absence is not a gate failure — the false-green would be
# a DECLARED icon whose resource is missing).
# ---------------------------------------------------------------------------
echo "[23] launcher-icon-declared: a declared launcher icon resolves to an adaptive-icon resource ..."

if [[ ! -f "$manifest" ]]; then
    result "launcher-icon-declared" fail "$manifest not found on disk (FR-41 — icon declaration cannot be verified)"
else
    icon_ref=$(strip_xml_comments "$manifest" | grep -E 'android:icon="' || true)
    if [[ -z "$icon_ref" ]]; then
        result "launcher-icon-declared" ok "(vacuous: no android:icon declared yet — FR-41 is a Could)"
    elif [[ -f "$app_main/res/mipmap-anydpi-v26/ic_launcher.xml" ]]; then
        if grep -q '<adaptive-icon' "$app_main/res/mipmap-anydpi-v26/ic_launcher.xml" 2>/dev/null; then
            result "launcher-icon-declared" ok
        else
            result "launcher-icon-declared" fail "$app_main/res/mipmap-anydpi-v26/ic_launcher.xml is not an <adaptive-icon> (FR-41 — the declared launcher icon would not render on API 26+)"
        fi
    else
        result "launcher-icon-declared" fail "the manifest declares a launcher icon but $app_main/res/mipmap-anydpi-v26/ic_launcher.xml does not exist (FR-41 — the referenced resource is missing)"
    fi
fi

# ---------------------------------------------------------------------------
# 24. no-committed-machine-files
#
# §7.2 "clean-clone build": local.properties (machine-specific SDK path) and any
# keystore must never be tracked.  Asserted with git ls-files (tracked-only), so a
# gitignored-but-present file on a dev box is correctly not a failure.
# ---------------------------------------------------------------------------
echo "[24] no-committed-machine-files: local.properties and keystores are untracked ..."

machine_tracked=$(git ls-files -- local.properties '*/local.properties' '*.keystore' '*.jks' 2>/dev/null || true)
if [[ -z "$machine_tracked" ]]; then
    result "no-committed-machine-files" ok
else
    result "no-committed-machine-files" fail "machine-specific/secret file(s) tracked by git (§7.2 — untrack them, .gitignore already covers local.properties/*.keystore/*.jks):
$machine_tracked"
fi

# ---------------------------------------------------------------------------
# 25. m1+m4+m5+m6+m7 still pass
#
# M8 cannot go green while an earlier invariant regresses.  Run the prior gates
# capturing their output; surface only pass/fail plus their own summary lines.
# M8 fails if any exits non-zero.  Mirrors exactly how m7_gate chains its priors.
# ---------------------------------------------------------------------------
echo "[25] m1+m4+m5+m6+m7 still pass: running tool/m1_gate.sh + tool/m4_gate.sh + tool/m5_gate.sh + tool/m6_gate.sh + tool/m7_gate.sh ..."

m1_ok=true
m4_ok=true
m5_ok=true
m6_ok=true
m7_ok=true

if [[ -f "tool/m1_gate.sh" ]]; then
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

if [[ -f "tool/m7_gate.sh" ]]; then
    m7_output=$(bash tool/m7_gate.sh 2>&1)
    m7_exit=$?
    m7_summary=$(echo "$m7_output" | grep "=== M7 gate:" | tail -1)
    if [[ "$m7_exit" -ne 0 ]]; then
        m7_ok=false
    fi
else
    m7_ok=false
    m7_summary="tool/m7_gate.sh not found"
fi

if $m1_ok && $m4_ok && $m5_ok && $m6_ok && $m7_ok; then
    echo "  m1_gate: ${m1_summary}"
    echo "  m4_gate: ${m4_summary}"
    echo "  m5_gate: ${m5_summary}"
    echo "  m6_gate: ${m6_summary}"
    echo "  m7_gate: ${m7_summary}"
    result "m1+m4+m5+m6+m7-still-pass" ok
else
    if ! $m1_ok; then
        result "m1+m4+m5+m6+m7-still-pass" fail "m1_gate FAILED — ${m1_summary}"
    elif ! $m4_ok; then
        result "m1+m4+m5+m6+m7-still-pass" fail "m4_gate FAILED — ${m4_summary}"
    elif ! $m5_ok; then
        result "m1+m4+m5+m6+m7-still-pass" fail "m5_gate FAILED — ${m5_summary}"
    elif ! $m6_ok; then
        result "m1+m4+m5+m6+m7-still-pass" fail "m6_gate FAILED — ${m6_summary}"
    else
        result "m1+m4+m5+m6+m7-still-pass" fail "m7_gate FAILED — ${m7_summary}"
    fi
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
finish
