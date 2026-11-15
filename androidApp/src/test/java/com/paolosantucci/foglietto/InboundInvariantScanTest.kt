package com.paolosantucci.foglietto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A (plain JUnit — no Robolectric, no device, no Xcode, no App Group) Linux mirror of the
 * cross-platform inbound structural contracts — plan TASK-14, spec §7.1 lines 809-815 and §7.2
 * lines 908-909, NFR-07.
 *
 * ## Why this suite exists
 *
 * The inbound path is the only feature in this product whose contracts are mostly *delivery*
 * contracts: a fourth Xcode target that must not see the shared module (FR-14), two App Group
 * entitlements that must agree (FR-26), two bundle ids that must not share one provisioning
 * profile (FR-28, NFR-07), a payload that must never be logged (NFR-03) and a drain that must be
 * synchronous (NFR-01). `quality.yml` proves all of it with shell gates and `ios.yml` proves the
 * rest on macOS — this suite is the *typed* local mirror, so a green `./gradlew check` on Linux
 * says something about the iOS half too (see the enforcement matrix, spec §7.2).
 *
 * Every rule here is a text predicate over an authored file, so the scan itself is the risk: a
 * predicate that resolves the wrong path, or that stops matching after a file is reworded, passes
 * silently while enforcing nothing. Two devices keep the suite honest:
 *
 *  1. **Path resolution is asserted, not assumed.** [the scan resolves every authored path it pins]
 *     fails loudly with the resolved repository root when a path moves, instead of throwing an
 *     opaque `FileNotFoundException` from an unrelated test.
 *  2. **Every predicate is re-run over a mutated in-memory copy** (S7). Each fixture mutates one
 *     string (never a file on disk), asserts the mutation landed, and asserts the predicate reports
 *     a violation that names *the file and the rule*. A rule whose message stopped naming the file
 *     cannot pass this suite even if it still fires.
 *
 * Comments are stripped before every code scan (the `ProcessLifecycleWiringTest` /
 * `MainActivityShareWiringTest` idiom), so prose can neither satisfy nor trip a rule. The two
 * deliberate prose exceptions are the ones the spec makes explicitly: the Swift `print(` inside a
 * comment (S4) and the NFR-01 synchrony wording inside `ShareInboxDrainer.swift`'s header (S5).
 *
 * Scope note (S5): `ShareViewController.swift` legitimately performs one `Task { @MainActor in }`
 * hop — `NSItemProvider`'s completion handler is not guaranteed to run on the main actor, and that
 * hop is a scheduling constraint of the extension API, not a delay (§5.1.6). It is therefore
 * deliberately **outside** the synchrony file list; [the synchrony rule deliberately excludes the
 * extension hop] pins that exclusion so the rule is never "fixed" by widening it.
 *
 * @see AppContainerShareTest the behavioural half of the Android ordering contract
 * @see ProcessLifecycleWiringTest the `m8` structural pin this suite follows as an idiom
 */
class InboundInvariantScanTest {

    // ── The contracts ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 the share extension is isolated from the shared module and the host graph`() {
        assertEquals(
            "The Share Extension reads and writes the App Group inbox and nothing else: it must " +
                "import no KMP module, link no `shared.xcframework` and name no host-graph " +
                "symbol. A violation means the extension gained a dependency on code that only " +
                "the host app links (FR-14, D1).",
            emptyList<String>(),
            isolationViolations(scan),
        )
    }

    @Test
    fun `S2 both entitlements declare the app group and the extension activates on text`() {
        assertEquals(
            "The host and the extension must declare the SAME App Group, and the extension must " +
                "activate for both text UTIs — otherwise the payload is written into a container " +
                "the host cannot read, or the extension never appears in the sheet (FR-12, " +
                "FR-26).",
            emptyList<String>(),
            entitlementViolations(scan),
        )
    }

    @Test
    fun `S3 the release pipeline wires two bundle ids to two distinct profiles`() {
        assertEquals(
            "Each signed target needs its own provisioning profile: two bundle ids in " +
                "`provisioningProfiles` with two DISTINCT placeholders, per-target " +
                "`PROVISIONING_PROFILE_SPECIFIER` values in the project, and no project-wide " +
                "override in the archive step (FR-28, NFR-07).",
            emptyList<String>(),
            releaseWiringViolations(scan),
        )
    }

    @Test
    fun `S4 no inbound file logs the payload or reports analytics`() {
        assertEquals(
            "An inbound payload is user text: it must never reach a log, a console, or an " +
                "analytics call on either platform (NFR-03).",
            emptyList<String>(),
            privacyViolations(scan),
        )
    }

    @Test
    fun `S5 the inbound path is synchronous on both platforms`() {
        assertEquals(
            "The drain must complete inside the same main-actor turn as the reset it follows: " +
                "zero `Task`/`DispatchQueue`/`async`/`await` in the three inbound files and at " +
                "the two drain call sites (NFR-01, FR-16, FR-17).",
            emptyList<String>(),
            synchronyViolations(scan),
        )
    }

    @Test
    fun `S6 both adapters delegate acceptance to the shared rule exactly once`() {
        assertEquals(
            "Acceptance (blank rejection plus CRLF normalisation) has one owner — the shared " +
                "`acceptIncomingText`. Each platform adapter calls it exactly once, and neither " +
                "rewrites CRLF or trims for acceptance locally (FR-01, NFR-05, D5).",
            emptyList<String>(),
            parityViolations(scan),
        )
    }

    // ── The scan itself ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the scan resolves every authored path it pins`() {
        SCANNED_FILES.forEach { relative ->
            assertTrue(
                "$relative must exist below ${repoRoot.path} — TASK-14 scans repo-relative paths " +
                    "from either the module or the repository working directory.",
                repoFile(relative).isFile,
            )
            assertTrue(
                "$relative must not be empty: an empty file would make its predicate vacuous.",
                scan.text(relative).isNotBlank(),
            )
        }
        assertTrue(
            "$SHARE_EXTENSION_DIR must hold the extension's authored files — a missing directory " +
                "would make the isolation rule (FR-14) vacuously green.",
            extensionFiles().isNotEmpty(),
        )
    }

    @Test
    fun `the synchrony rule deliberately excludes the extension hop`() {
        // §5.1.6: `NSItemProvider`'s completion handler is not guaranteed to run on the main
        // actor, so ShareViewController hops back with one `Task { @MainActor in }`. That is an
        // API constraint, not a delay: adding the file to the NFR-01 scan would be a false
        // positive that pressures the next reader into deleting the hop.
        assertFalse(
            "NFR-01's synchrony file list must stay [$SHARE_INBOX, $SHARE_INBOX_DRAINER, " +
                "$LIFECYCLE_COORDINATOR] plus the two drain call sites — " +
                "$SHARE_VIEW_CONTROLLER legitimately hops once and is out of scope (S5).",
            SYNCHRONY_FILES.contains(SHARE_VIEW_CONTROLLER),
        )
    }

    // ── S7 failure fixtures: one mutation per case, in memory only ──────────────────────────────

    @Test
    fun `S7 a KMP import in the extension is reported against the file`() {
        val mutated = mutate(SHARE_VIEW_CONTROLLER) {
            it.replace("import UIKit", "import UIKit\nimport shared")
        }
        assertReported(isolationViolations(mutated), SHARE_VIEW_CONTROLLER, RULE_S1)
    }

    @Test
    fun `S7 a linked shared framework in the extension is reported against the file`() {
        val mutated = mutate(SHARE_VIEW_CONTROLLER) {
            it + "\n// The extension must never do this:\nlet linked = \"shared.xcframework\"\n"
        }
        assertReported(isolationViolations(mutated), SHARE_VIEW_CONTROLLER, RULE_S1)
    }

    @Test
    fun `S7 a host recovery symbol in the extension is reported against the file`() {
        val mutated = mutate(SHARE_VIEW_CONTROLLER) {
            it + "\nprivate func leaked(_ repository: Any) { SaveNoteToRecovery(repository: repository) }\n"
        }
        assertReported(isolationViolations(mutated), SHARE_VIEW_CONTROLLER, RULE_S1)
    }

    @Test
    fun `S7 a host recovery factory in the extension is reported against the file`() {
        val mutated = mutate(SHARE_VIEW_CONTROLLER) {
            it + "\nprivate func leaked() { createIosRecoveryRepository() }\n"
        }
        assertReported(isolationViolations(mutated), SHARE_VIEW_CONTROLLER, RULE_S1)
    }

    @Test
    fun `S7 forbidden tokens inside extension comments are not reported`() {
        // The rule pins code, not prose: the extension's own header explains *why* it imports no
        // KMP module, and a prose sentence must never satisfy the predicate (or trip it).
        val mutated = mutate(SHARE_VIEW_CONTROLLER) {
            it + "\n// import shared and acceptIncomingText are forbidden here\n/* SaveNoteToRecovery */"
        }
        assertEquals(
            "Comment lines must be filtered before the isolation scan runs (FR-14).",
            emptyList<String>(),
            isolationViolations(mutated),
        )
    }

    @Test
    fun `S7 a host entitlement without the app group is reported against the file`() {
        val mutated = mutate(HOST_ENTITLEMENTS) {
            it.replace("<string>$APP_GROUP_ID</string>", "")
        }
        assertReported(entitlementViolations(mutated), HOST_ENTITLEMENTS, RULE_S2)
    }

    @Test
    fun `S7 an extension entitlement without the app group is reported against the file`() {
        val mutated = mutate(EXTENSION_ENTITLEMENTS) {
            it.replace(APP_GROUP_ID, "group.paolosantucci.other")
        }
        assertReported(entitlementViolations(mutated), EXTENSION_ENTITLEMENTS, RULE_S2)
    }

    @Test
    fun `S7 a missing share-services extension point is reported against the plist`() {
        val mutated = mutate(EXTENSION_PLIST) {
            it.replace("com.apple.share-services", "com.apple.other-services")
        }
        assertReported(entitlementViolations(mutated), EXTENSION_PLIST, RULE_S2)
    }

    @Test
    fun `S7 a missing plain-text activation UTI is reported against the plist`() {
        val mutated = mutate(EXTENSION_PLIST) { it.replace("public.plain-text", "public.rtf") }
        assertReported(entitlementViolations(mutated), EXTENSION_PLIST, RULE_S2)
    }

    @Test
    fun `S7 a missing text activation UTI is reported against the plist`() {
        val mutated = mutate(EXTENSION_PLIST) { it.replace("public.text", "public.xml") }
        assertReported(entitlementViolations(mutated), EXTENSION_PLIST, RULE_S2)
    }

    @Test
    fun `S7 two bundle ids sharing one placeholder is reported against the template`() {
        val mutated = mutate(EXPORT_TEMPLATE) {
            it.replace(EXTENSION_PLACEHOLDER, APP_PLACEHOLDER)
        }
        assertReported(releaseWiringViolations(mutated), EXPORT_TEMPLATE, RULE_S3)
    }

    @Test
    fun `S7 a template without the extension bundle id is reported against the template`() {
        val mutated = mutate(EXPORT_TEMPLATE) {
            it.replace("<key>$EXTENSION_BUNDLE_ID</key>", "")
        }
        assertReported(releaseWiringViolations(mutated), EXPORT_TEMPLATE, RULE_S3)
    }

    @Test
    fun `S7 an extension signed with the app specifier is reported against the project`() {
        val mutated = mutate(PBXPROJ) {
            it.replace(EXTENSION_SPECIFIER, APP_SPECIFIER)
        }
        assertReported(releaseWiringViolations(mutated), PBXPROJ, RULE_S3)
    }

    @Test
    fun `S7 a re-added project-wide profile override is reported against the workflow`() {
        val mutated = mutate(IOS_WORKFLOW) {
            it + "\n            $GLOBAL_OVERRIDE\$PROFILE_UUID\" \\\\\n"
        }
        assertReported(releaseWiringViolations(mutated), IOS_WORKFLOW, RULE_S3)
    }

    @Test
    fun `S7 a workflow without the extension profile secret is reported against the workflow`() {
        val mutated = mutate(IOS_WORKFLOW) {
            it.replace(EXTENSION_PROFILE_SECRET, "")
        }
        assertReported(releaseWiringViolations(mutated), IOS_WORKFLOW, RULE_S3)
    }

    @Test
    fun `S7 a Swift NSLog is reported against the file`() {
        val mutated = mutate(SHARE_INBOX) { it + "\nlet logged = NSLog(\"inbox\")" }
        assertReported(privacyViolations(mutated), SHARE_INBOX, RULE_S4)
    }

    @Test
    fun `S7 a Kotlin Log call is reported against the file`() {
        val mutated = mutate(APP_CONTAINER) {
            it + "\nprivate fun debug(payload: String) { android.util.Log.d(\"Foglietto\", payload) }"
        }
        assertReported(privacyViolations(mutated), APP_CONTAINER, RULE_S4)
    }

    @Test
    fun `S7 an analytics call in the extension is reported against the file`() {
        val mutated = mutate(SHARE_VIEW_CONTROLLER) { it + "\nlet tracker = FirebaseAnalytics()" }
        assertReported(privacyViolations(mutated), SHARE_VIEW_CONTROLLER, RULE_S4)
    }

    @Test
    fun `S7 a print call inside a comment is not reported`() {
        val mutated = mutate(SHARE_INBOX) {
            it + "\n// print(payload) would leak the share\n/* NSLog(\"inbox\") */"
        }
        assertEquals(
            "The Swift `print(` exception is comment-only: prose must not trip the privacy scan " +
                "(NFR-03).",
            emptyList<String>(),
            privacyViolations(mutated),
        )
    }

    @Test
    fun `S7 a Task hop in the drainer is reported against the file`() {
        val mutated = mutate(SHARE_INBOX_DRAINER) { it + "\nfunc hop() { Task { } }" }
        assertReported(synchronyViolations(mutated), SHARE_INBOX_DRAINER, RULE_S5)
    }

    @Test
    fun `S7 a dispatch hop in the inbox is reported against the file`() {
        val mutated = mutate(SHARE_INBOX) {
            it + "\nfunc later(_ body: @escaping () -> Void) { DispatchQueue.main.async { body() } }"
        }
        assertReported(synchronyViolations(mutated), SHARE_INBOX, RULE_S5)
    }

    @Test
    fun `S7 an async save path is reported against the coordinator`() {
        val mutated = mutate(LIFECYCLE_COORDINATOR) { it + "\nfunc later() async { }" }
        assertReported(synchronyViolations(mutated), LIFECYCLE_COORDINATOR, RULE_S5)
    }

    @Test
    fun `S7 an await in the save path is reported against the coordinator`() {
        val mutated = mutate(LIFECYCLE_COORDINATOR) {
            it + "\nfunc resume() { await saveCurrentBuffer() }"
        }
        assertReported(synchronyViolations(mutated), LIFECYCLE_COORDINATOR, RULE_S5)
    }

    @Test
    fun `S7 a Task hop at the cold-start call site is reported against the file`() {
        val mutated = mutate(APP_ROOT) {
            it.replace("drainer.drain()", "Task { drainer.drain() }")
        }
        assertReported(synchronyViolations(mutated), APP_ROOT, RULE_S5)
    }

    @Test
    fun `S7 a dispatch hop at the warm call site is reported against the file`() {
        val mutated = mutate(CONTENT_VIEW) {
            it.replace(
                "coordinator.onActive()",
                "DispatchQueue.main.async { coordinator.onActive() }",
            )
        }
        assertReported(synchronyViolations(mutated), CONTENT_VIEW, RULE_S5)
    }

    @Test
    fun `S7 a second acceptance call in the container is reported against the file`() {
        val mutated = mutate(APP_CONTAINER) {
            it.replace(
                "pending = acceptIncomingText(raw)",
                "pending = acceptIncomingText(raw)\n        acceptIncomingText(raw)",
            )
        }
        assertReported(parityViolations(mutated), APP_CONTAINER, RULE_S6)
    }

    @Test
    fun `S7 a missing acceptance call in the app root is reported against the file`() {
        val mutated = mutate(APP_ROOT) {
            it.replace("IncomingTextKt.acceptIncomingText(raw: \$0)", "raw")
        }
        assertReported(parityViolations(mutated), APP_ROOT, RULE_S6)
    }

    @Test
    fun `S7 a local CRLF rewrite in the container is reported against the file`() {
        val mutated = mutate(APP_CONTAINER) {
            it + "\nprivate fun local(raw: String?) = raw?.replace(\"\\r\\n\", \"\\n\")"
        }
        assertReported(parityViolations(mutated), APP_CONTAINER, RULE_S6)
    }

    @Test
    fun `S7 a local blank guard in the app root is reported against the file`() {
        val mutated = mutate(APP_ROOT) {
            it + "\nprivate func isBlank(_ raw: String?) -> Bool { raw?.trim().isEmpty() ?? false }"
        }
        assertReported(parityViolations(mutated), APP_ROOT, RULE_S6)
    }

    // ── S1 extension isolation (FR-14, D1) ──────────────────────────────────────────────────────

    /**
     * Every authored file under [SHARE_EXTENSION_DIR], comment lines filtered, must be free of
     * the host graph: no KMP import, no framework link and none of the three host symbols that
     * would drag the shared module (and therefore `<filesDir>`-style I/O and the recovery store)
     * into the appex. Mirrors `m4` check 14 (spec §7.1 line 811).
     */
    private fun isolationViolations(scan: Scan): List<String> =
        extensionFiles().flatMap { file ->
            val code = scan.code(file)
            EXTENSION_FORBIDDEN.filter { code.contains(it) }.map { token ->
                report(
                    file,
                    RULE_S1,
                    "the Share Extension must not name `$token` — it may only read and write the " +
                        "App Group inbox (FR-14, D1)",
                )
            }
        }

    // ── S2 entitlements + activation (FR-12, FR-26) ────────────────────────────────────────────

    /**
     * Both entitlements carry the App Group (a group id outside
     * `com.apple.security.application-groups` is not an entitlement), and the extension's
     * `Info.plist` declares the share-services extension point plus both text UTIs in its
     * activation rule. Spec §7.1 line 812.
     */
    private fun entitlementViolations(scan: Scan): List<String> {
        val problems = mutableListOf<String>()

        ENTITLEMENT_FILES.forEach { file ->
            val text = scan.text(file)
            if (!text.contains(APP_GROUP_ID)) {
                problems += report(
                    file,
                    RULE_S2,
                    "the App Group `$APP_GROUP_ID` is missing — the two processes would not share " +
                        "a container (FR-26)",
                )
            }
            if (!text.contains(APP_GROUPS_KEY)) {
                problems += report(
                    file,
                    RULE_S2,
                    "`$APP_GROUPS_KEY` is missing — a bare group id is not an App Group " +
                        "entitlement (FR-26)",
                )
            }
        }

        val plist = scan.text(EXTENSION_PLIST)
        ACTIVATION_TOKENS.forEach { token ->
            if (!plist.contains(token)) {
                problems += report(
                    EXTENSION_PLIST,
                    RULE_S2,
                    "`$token` is missing — the extension point must be share-services and the " +
                        "activation rule must accept both text UTIs (FR-12, FR-26)",
                )
            }
        }
        return problems
    }

    // ── S3 release wiring (FR-28, NFR-07) ──────────────────────────────────────────────────────

    /**
     * Two bundle ids, two **distinct** profile placeholders, per-target specifiers in the project
     * and no project-wide override in the archive step. The last one is the failure that signs the
     * extension with the app's profile and only surfaces at `-exportArchive` time (FR-28, NFR-07).
     */
    private fun releaseWiringViolations(scan: Scan): List<String> {
        val problems = mutableListOf<String>()
        problems += placeholderViolations(scan.text(EXPORT_TEMPLATE))

        // Raw text on purpose: a pbxproj comment cannot smuggle a build setting into the project.
        val project = scan.text(PBXPROJ)
        if (!project.contains(APP_SPECIFIER)) {
            problems += report(
                PBXPROJ,
                RULE_S3,
                "the host target must resolve its profile through `$APP_SPECIFIER` (FR-28, NFR-07)",
            )
        }
        if (!project.contains(EXTENSION_SPECIFIER)) {
            problems += report(
                PBXPROJ,
                RULE_S3,
                "the extension target must resolve its own profile through `$EXTENSION_SPECIFIER` " +
                    "— sharing the host specifier signs the appex with the wrong profile " +
                    "(FR-28, NFR-07)",
            )
        }

        val workflow = stripYamlComments(scan.text(IOS_WORKFLOW))
        if (workflow.contains(GLOBAL_OVERRIDE)) {
            problems += report(
                IOS_WORKFLOW,
                RULE_S3,
                "`$GLOBAL_OVERRIDE…` is a project-wide profile override: it re-signs the share " +
                    "extension with the app profile and defeats the per-target specifiers " +
                    "(FR-28, NFR-07)",
            )
        }
        if (!workflow.contains(EXTENSION_PROFILE_SECRET)) {
            problems += report(
                IOS_WORKFLOW,
                RULE_S3,
                "`$EXTENSION_PROFILE_SECRET` is missing — the release job cannot install the " +
                    "extension's provisioning profile (FR-28, NFR-07)",
            )
        }
        return problems
    }

    /**
     * `provisioningProfiles` must hold exactly the two known bundle ids, each mapped to its own
     * non-empty placeholder. A missing key fails to sign one target; a shared placeholder silently
     * signs both targets with one profile (FR-28).
     */
    private fun placeholderViolations(template: String): List<String> {
        val entries = provisioningProfileEntries(template)
        val problems = mutableListOf<String>()

        if (entries.size != EXPECTED_BUNDLE_IDS.size) {
            problems += report(
                EXPORT_TEMPLATE,
                RULE_S3,
                "`provisioningProfiles` must map exactly ${EXPECTED_BUNDLE_IDS.size} bundle ids, " +
                    "found ${entries.size}: ${entries.map { it.first }} (FR-28)",
            )
        }

        val mapped = entries.map { it.first }.toSet()
        (EXPECTED_BUNDLE_IDS - mapped).forEach { id ->
            problems += report(
                EXPORT_TEMPLATE,
                RULE_S3,
                "bundle id `$id` is missing from `provisioningProfiles` — it would export " +
                    "unsigned (FR-28, NFR-07)",
            )
        }
        (mapped - EXPECTED_BUNDLE_IDS).forEach { id ->
            problems += report(
                EXPORT_TEMPLATE,
                RULE_S3,
                "unexpected bundle id `$id` in `provisioningProfiles` (FR-28)",
            )
        }

        val placeholders = entries.map { it.second }
        if (placeholders.distinct().size != placeholders.size) {
            problems += report(
                EXPORT_TEMPLATE,
                RULE_S3,
                "`$HOST_BUNDLE_ID` and `$EXTENSION_BUNDLE_ID` must map to two DISTINCT " +
                    "placeholders, found ${placeholders} — one placeholder signs both targets " +
                    "with the same profile (FR-28, NFR-07)",
            )
        }
        if (placeholders.any { it.isBlank() }) {
            problems += report(
                EXPORT_TEMPLATE,
                RULE_S3,
                "a `provisioningProfiles` placeholder is blank — the sed step would emit an " +
                    "empty profile (FR-28)",
            )
        }
        return problems
    }

    /** The `<key>bundle id</key><string>placeholder</string>` pairs of `provisioningProfiles`. */
    private fun provisioningProfileEntries(template: String): List<Pair<String, String>> {
        val keyAt = template.indexOf("<key>$PROVISIONING_PROFILES</key>")
        if (keyAt < 0) return emptyList()

        val dictAt = template.indexOf("<dict>", keyAt)
        if (dictAt < 0) return emptyList()

        val dictEnd = template.indexOf("</dict>", dictAt)
        if (dictEnd < 0) return emptyList()

        return PLACEHOLDER_ENTRY.findAll(template.substring(dictAt, dictEnd))
            .map { it.groupValues[1] to it.groupValues[2].trim() }
            .toList()
    }

    // ── S4 privacy (NFR-03) ────────────────────────────────────────────────────────────────────

    /**
     * The payload is user text: no console, no log facility, no analytics identifier in any file
     * that touches it on either platform. Comment lines are filtered first, which is exactly the
     * spec's Swift `print(` exception (NFR-03, §7.2 line 908).
     */
    private fun privacyViolations(scan: Scan): List<String> =
        PRIVACY_FILES.flatMap { file ->
            PRIVACY_MARKERS.findAll(scan.code(file))
                .map { it.value }
                .distinct()
                .map { marker ->
                    report(
                        file,
                        RULE_S4,
                        "`$marker` would expose the inbound payload — the share is never logged " +
                            "and never reported to analytics (NFR-03)",
                    )
                }
                .toList()
        }

    // ── S5 synchrony (NFR-01) ─────────────────────────────────────────────────────────────────

    /**
     * Zero whole-word `Task`/`DispatchQueue`/`async`/`await` in the three inbound files **and** at
     * the two call sites that drain them: a hop there would let the first rendered frame miss the
     * seed and would let the `.active` reset and the seed land in different turns (NFR-01, FR-16,
     * FR-17, EC-19).
     */
    private fun synchronyViolations(scan: Scan): List<String> {
        val problems = mutableListOf<String>()

        SYNCHRONY_FILES.forEach { file ->
            problems += concurrencyHits(scan.code(file), file, "the whole file")
        }

        problems += callSiteViolations(
            scan = scan,
            file = APP_ROOT,
            marker = COLD_START_MARKER,
            callSite = "ColdStartSeeder.seed() (FR-16 cold start)",
        )
        problems += callSiteViolations(
            scan = scan,
            file = CONTENT_VIEW,
            marker = WARM_DRAIN_MARKER,
            callSite = "ContentView.dispatchScenePhase() (FR-17 warm activation)",
        )
        return problems
    }

    /** One drain call site: its body must be hop-free, so the seed lands in the caller's turn. */
    private fun callSiteViolations(
        scan: Scan,
        file: String,
        marker: String,
        callSite: String,
    ): List<String> {
        val code = scan.code(file)
        val body = bodyRange(code, marker)
            ?: return listOf(
                report(
                    file,
                    RULE_S5,
                    "`$callSite` is not found — the drain call site this rule pins has no body to " +
                        "scan (NFR-01, FR-16, FR-17)",
                ),
            )
        return concurrencyHits(code.substring(body), file, callSite)
    }

    private fun concurrencyHits(code: String, file: String, where: String): List<String> =
        CONCURRENCY.findAll(code)
            .map { it.value }
            .distinct()
            .map { token ->
                report(
                    file,
                    RULE_S5,
                    "`$token` appears in $where — the inbound path must be fully synchronous " +
                        "(NFR-01)",
                )
            }
            .toList()

    // ── S6 adapter parity (FR-01, NFR-05) ─────────────────────────────────────────────────────

    /**
     * One acceptance rule, two adapters, one call each. The adapters only *carry* the raw payload:
     * a local CRLF rewrite or a local blank guard here would fork the product's notion of "inbound
     * text" and drift from the shared table (FR-01, NFR-05, D5).
     */
    private fun parityViolations(scan: Scan): List<String> =
        ADAPTER_FILES.flatMap { file ->
            val code = scan.code(file)
            val problems = mutableListOf<String>()

            val calls = countOf(code, ACCEPTANCE_CALL)
            if (calls != 1) {
                problems += report(
                    file,
                    RULE_S6,
                    "expected exactly one `acceptIncomingText(` call, found $calls — acceptance " +
                        "has exactly one owner (FR-01, NFR-05, D5)",
                )
            }
            CRLF_REWRITES.filter { code.contains(it) }.forEach { rewrite ->
                problems += report(
                    file,
                    RULE_S6,
                    "local CRLF rewriting (`$rewrite`) forks the shared normalisation rule — the " +
                        "adapter must pass the raw payload through (FR-01, NFR-05)",
                )
            }
            if (code.contains(BLANK_GUARD)) {
                problems += report(
                    file,
                    RULE_S6,
                    "`$BLANK_GUARD` is a local acceptance branch — blank rejection belongs to the " +
                        "shared rule alone (FR-01, NFR-05, D5)",
                )
            }
            problems
        }

    // ── The scanned tree ──────────────────────────────────────────────────────────────────────

    /**
     * One file's worth of the tree. Predicates read through [text]/[code], so a fixture can
     * replace a single file's content in memory ([with]) and every rule re-runs over the mutated
     * world without touching the disk (S7).
     */
    private inner class Scan(private val overrides: Map<String, String> = emptyMap()) {

        /** The authored content of [relative], or the mutated copy a fixture installed. */
        fun text(relative: String): String =
            overrides[relative] ?: repoFile(relative).readText()

        /** [text] with comments removed, so the rules pin code and not prose. */
        fun code(relative: String): String = stripComments(text(relative))

        /** The same tree with one file's content replaced in memory. */
        fun with(relative: String, replacement: String): Scan =
            Scan(overrides + (relative to replacement))
    }

    private val scan = Scan()

    /** The repository root, resolved from either the module directory or the repository root. */
    private val repoRoot: File by lazy {
        val markers = listOf(PBXPROJ, IOS_WORKFLOW)
        listOf(File("."), File(".."), File("../.."))
            .map { it.absoluteFile }
            .firstOrNull { root -> markers.all { File(root, it).isFile } }
            ?.canonicalFile
            ?: error(
                "No repository root from ${File(".").absolutePath}: none of the candidates held " +
                    "all of $markers",
            )
    }

    private fun repoFile(relative: String): File = File(repoRoot, relative)

    /** Every authored file under the extension directory, as a repo-relative path. */
    private fun extensionFiles(): List<String> {
        val root = repoFile(SHARE_EXTENSION_DIR)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.toRelativeString(repoRoot) }
            .sorted()
            .toList()
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    /**
     * [file]'s content with [transform] applied, as a one-file mutated tree.
     *
     * The assertion that the mutation actually landed is part of the contract: a fixture built
     * from a `replace()` that no longer matches the (reworded) source would otherwise "prove" a
     * predicate that reports nothing.
     */
    private fun mutate(file: String, transform: (String) -> String): Scan {
        val original = scan.text(file)
        val replacement = transform(original)
        assertNotEquals("The fixture must actually mutate `$file` (S7).", original, replacement)
        return scan.with(file, replacement)
    }

    /** A fixture passes only when the rule fires **and** names the file and the rule. */
    private fun assertReported(violations: List<String>, file: String, rule: String) {
        assertTrue(
            "The mutated `$file` must be reported under `$rule` with a message naming the file " +
                "and the rule, got $violations",
            violations.any { it.contains(file) && it.contains(rule) },
        )
    }

    // ── Source plumbing ───────────────────────────────────────────────────────────────────────

    /**
     * Code lines only: block comments and full-line `//` comments removed, so the rules pin code
     * and not prose. The same idiom as `ProcessLifecycleWiringTest` /
     * `MainActivityShareWiringTest`; the YAML workflow goes through [stripYamlComments] instead.
     */
    private fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT, " ")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** YAML comments are `#`; shell comments inside `run:` blocks are stripped by the same pass. */
    private fun stripYamlComments(text: String): String =
        text.lineSequence()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")

    /** Non-overlapping occurrences of [needle] in [haystack]. */
    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    /** The `open..close` range of the brace block whose signature starts with [marker], or null. */
    private fun bodyRange(text: String, marker: String): IntRange? {
        val start = text.indexOf(marker)
        if (start < 0) return null
        val open = text.indexOf('{', start)
        if (open < 0) return null
        val close = blockEnd(text, open)
        return if (close < 0) null else open..close
    }

    /** Index of the brace closing the block opened at [openIndex]. */
    private fun blockEnd(text: String, openIndex: Int): Int {
        var depth = 0
        for (index in openIndex until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun report(file: String, rule: String, detail: String): String = "$file: $rule — $detail"

    private companion object {
        // ── Paths (repo-relative; the working directory may be the module) ──────────────────────
        const val SHARE_EXTENSION_DIR = "iosApp/ShareExtension"
        const val SHARE_VIEW_CONTROLLER = "$SHARE_EXTENSION_DIR/ShareViewController.swift"
        const val EXTENSION_PLIST = "$SHARE_EXTENSION_DIR/Info.plist"
        const val EXTENSION_ENTITLEMENTS = "$SHARE_EXTENSION_DIR/ShareExtension.entitlements"
        const val HOST_ENTITLEMENTS = "iosApp/iosApp/iosApp.entitlements"
        const val SHARE_INBOX = "iosApp/iosApp/Share/ShareInbox.swift"
        const val SHARE_INBOX_DRAINER = "iosApp/iosApp/Share/ShareInboxDrainer.swift"
        const val LIFECYCLE_COORDINATOR = "iosApp/iosApp/LifecycleSaveCoordinator.swift"
        const val APP_ROOT = "iosApp/iosApp/iosAppApp.swift"
        const val CONTENT_VIEW = "iosApp/iosApp/ContentView.swift"
        const val PBXPROJ = "iosApp/iosApp.xcodeproj/project.pbxproj"
        const val EXPORT_TEMPLATE = "ExportOptions.plist.template"
        const val IOS_WORKFLOW = ".github/workflows/ios.yml"
        const val ANDROID_MAIN = "androidApp/src/main/java/com/paolosantucci/foglietto"
        const val APP_CONTAINER = "$ANDROID_MAIN/AppContainer.kt"
        const val MAIN_ACTIVITY = "$ANDROID_MAIN/MainActivity.kt"
        const val SHARE_INTENT_READER = "$ANDROID_MAIN/state/ShareIntentReader.kt"

        /** Every path this suite pins, so a moved file fails one loud test, not five vague ones. */
        val SCANNED_FILES = listOf(
            SHARE_VIEW_CONTROLLER,
            EXTENSION_PLIST,
            EXTENSION_ENTITLEMENTS,
            HOST_ENTITLEMENTS,
            SHARE_INBOX,
            SHARE_INBOX_DRAINER,
            LIFECYCLE_COORDINATOR,
            APP_ROOT,
            CONTENT_VIEW,
            PBXPROJ,
            EXPORT_TEMPLATE,
            IOS_WORKFLOW,
            APP_CONTAINER,
            MAIN_ACTIVITY,
            SHARE_INTENT_READER,
        )

        // ── Rule labels: every violation names one ───────────────────────────────────────────────
        const val RULE_S1 = "S1 extension-isolation (FR-14)"
        const val RULE_S2 = "S2 entitlements+activation (FR-12, FR-26)"
        const val RULE_S3 = "S3 release-wiring (FR-28, NFR-07)"
        const val RULE_S4 = "S4 privacy (NFR-03)"
        const val RULE_S5 = "S5 synchrony (NFR-01)"
        const val RULE_S6 = "S6 adapter-parity (FR-01, NFR-05)"

        // ── S1 ───────────────────────────────────────────────────────────────────────────────────
        val EXTENSION_FORBIDDEN = listOf(
            "import shared",
            "shared.xcframework",
            "acceptIncomingText",
            "SaveNoteToRecovery",
            "createIosRecovery",
        )

        // ── S2 ───────────────────────────────────────────────────────────────────────────────────
        const val APP_GROUP_ID = "group.paolosantucci.foglietto"
        const val APP_GROUPS_KEY = "com.apple.security.application-groups"
        val ENTITLEMENT_FILES = listOf(HOST_ENTITLEMENTS, EXTENSION_ENTITLEMENTS)
        val ACTIVATION_TOKENS = listOf(
            "com.apple.share-services",
            "public.plain-text",
            "public.text",
        )

        // ── S3 ───────────────────────────────────────────────────────────────────────────────────
        const val PROVISIONING_PROFILES = "provisioningProfiles"
        const val HOST_BUNDLE_ID = "com.paolosantucci.foglietto"
        const val EXTENSION_BUNDLE_ID = "com.paolosantucci.foglietto.ShareExtension"
        val EXPECTED_BUNDLE_IDS = setOf(HOST_BUNDLE_ID, EXTENSION_BUNDLE_ID)
        const val APP_PLACEHOLDER = "__APP_PROFILE_UUID__"
        const val EXTENSION_PLACEHOLDER = "__EXTENSION_PROFILE_UUID__"
        const val APP_SPECIFIER = "\$(IOS_APP_PROFILE_SPECIFIER)"
        const val EXTENSION_SPECIFIER = "\$(IOS_EXTENSION_PROFILE_SPECIFIER)"
        const val GLOBAL_OVERRIDE = "PROVISIONING_PROFILE_SPECIFIER=\""
        const val EXTENSION_PROFILE_SECRET = "IOS_EXTENSION_PROVISIONING_PROFILE_BASE64"
        val PLACEHOLDER_ENTRY = Regex("""<key>([^<]+)</key>\s*<string>([^<]*)</string>""")

        // ── S4 ───────────────────────────────────────────────────────────────────────────────────
        val PRIVACY_FILES = listOf(
            MAIN_ACTIVITY,
            APP_CONTAINER,
            SHARE_INTENT_READER,
            SHARE_INBOX,
            SHARE_INBOX_DRAINER,
            SHARE_VIEW_CONTROLLER,
        )

        /**
         * `\bLog\.` is the Kotlin/Android facility; `println(`/`print(` the console;
         * `NSLog(`/`os_log(` the Apple log facilities. The analytics names are the SDK entry
         * points this product has never had and must not gain.
         */
        val PRIVACY_MARKERS = Regex(
            """\bLog\.|println\(|NSLog\(|os_log\(|print\(""" +
                """|FirebaseAnalytics|FirebaseCrashlytics|Crashlytics|Mixpanel|Amplitude""" +
                """|AppsFlyer|Sentry|Analytics|logEvent\(|trackEvent\(|setUserId\(""",
        )

        // ── S5 ───────────────────────────────────────────────────────────────────────────────────
        val SYNCHRONY_FILES = listOf(SHARE_INBOX, SHARE_INBOX_DRAINER, LIFECYCLE_COORDINATOR)
        val CONCURRENCY = Regex("""\bTask\b|\bDispatchQueue\b|\basync\b|\bawait\b""")
        const val COLD_START_MARKER = "static func seed("
        const val WARM_DRAIN_MARKER = "static func dispatchScenePhase("

        // ── S6 ───────────────────────────────────────────────────────────────────────────────────
        val ADAPTER_FILES = listOf(APP_CONTAINER, APP_ROOT)
        const val ACCEPTANCE_CALL = "acceptIncomingText("
        const val BLANK_GUARD = "trim().isEmpty()"

        /**
         * A local CRLF rewrite in either language: Kotlin `replace(`, Swift
         * `replacingOccurrences(of:)`.
         */
        val CRLF_REWRITES = listOf(
            "replace(\"\\r\\n\"",
            "replacingOccurrences(of: \"\\r\\n\"",
        )

        val BLOCK_COMMENT = Regex("""(?s)/\*.*?\*/""")
    }
}
