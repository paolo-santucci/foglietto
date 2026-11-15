package com.paolosantucci.foglietto

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Tier A manifest contract — plain JVM XML parse, no Robolectric (FR-01, FR-03, NFR-04).
 *
 * Pins the least-privilege manifest contract from the plan (TASK-03):
 *  - zero `<uses-permission>` declarations — the app asks the platform for nothing;
 *  - exactly one launcher activity (`.MainActivity`, exported) so the app has a single
 *    entry point and no accidental extra exported component;
 *  - backups disabled (`allowBackup="false"`) — the buffer is *ephemeral* by contract;
 *  - the activity resizes for the IME (`adjustResize`) so the editor + action row are
 *    never covered by the keyboard;
 *  - the application names its single composition root: `<application android:name=
 *    ".FogliettoApplication">` is what runs the process wiring (TASK-18), and no Activity may take
 *    over save/reset from it (FR-14);
 *  - the application carries the product's proper-noun label and the project theme (I7), so the
 *    home screen shows "Foglietto" and the window is painted by the app, not by the framework.
 */
class ManifestContractTest {

    private val manifestFile: File by lazy {
        listOf(
            File("src/main/AndroidManifest.xml"),
            File("androidApp/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }

    /**
     * The manifest as authored, captured once. Failure fixtures re-parse these exact bytes into
     * their own [Document], so the shared [manifest] tree is never mutated and the file is never
     * written (TASK-05).
     */
    private val manifestBytes: ByteArray by lazy { manifestFile.readBytes() }

    private val manifest: Document by lazy { parseManifest(manifestBytes) }

    @Test
    fun `manifest requests no permissions at all`() {
        assertEquals(
            "The Android manifest must declare zero <uses-permission> entries (least privilege).",
            0,
            manifest.getElementsByTagName("uses-permission").length,
        )
    }

    @Test
    fun `manifest exposes exactly one launcher activity`() {
        val activities = manifest.elements("activity")
        val launcherActivities = activities.filter { activity ->
            activity.children("intent-filter").any { filter ->
                filter.children("action").any { it.getAttribute("android:name") == ACTION_MAIN } &&
                    filter.children("category").any { it.getAttribute("android:name") == CATEGORY_LAUNCHER }
            }
        }

        assertEquals(
            "Exactly one activity must handle MAIN/LAUNCHER.",
            1,
            launcherActivities.size,
        )

        val launcher = launcherActivities.single()
        assertEquals(".MainActivity", launcher.getAttribute("android:name"))
        assertEquals("true", launcher.getAttribute("android:exported"))
        assertEquals(1, activities.size)
    }

    @Test
    fun `manifest keeps backup off and lets the editor resize for the ime`() {
        val application = manifest.elements("application").single()

        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals(
            "TASK-18: <application> must name the single composition root.",
            ".FogliettoApplication",
            application.getAttribute("android:name"),
        )

        val launcher = manifest.elements("activity").single()
        assertEquals("adjustResize", launcher.getAttribute("android:windowSoftInputMode"))
    }

    @Test
    fun `the application carries the product name and the project theme`() {
        val application = manifest.elements("application").single()

        assertEquals(
            "I7: android:label is the product's proper noun, shared verbatim with the iOS " +
                "CFBundleName. It is deliberately not a resource key — the 17-key inventory " +
                "(FR-36, OQ-21) stays closed and `app_name` stays absent.",
            "Foglietto",
            application.getAttribute("android:label"),
        )
        assertEquals(
            "I7: the application must use the project theme (no action bar, window background = " +
                "the editor surface), not the framework default.",
            "@style/Theme.Foglietto",
            application.getAttribute("android:theme"),
        )
    }

    // ------------------------------------------------------------------------------------------
    // TASK-05 — inbound share entry points (FR-21, NFR-04, §5.1.8 manifest contract).
    //
    // The contract is expressed as ONE pure predicate over a parsed Document
    // ([inboundViolations]); the real manifest must yield an empty list, and every failure fixture
    // re-parses the captured bytes into its own Document, mutates that copy and asserts the same
    // predicate names the offender. A fixture therefore cannot pass on a rule the real manifest is
    // not held to.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `main activity is single top and carries exactly the two plain text share filters`() {
        val activity = manifest.mainActivity()

        assertEquals(
            "FR-21: .MainActivity must be android:launchMode=\"singleTop\" so a share is delivered " +
                "to onNewIntent instead of stacking a second instance.",
            "singleTop",
            activity.getAttribute("android:launchMode"),
        )

        val filters = activity.shareFilters()
        assertEquals(
            "FR-21: .MainActivity must declare exactly two non-launcher <intent-filter> blocks " +
                "(ACTION_SEND and ACTION_PROCESS_TEXT). Found: ${filters.map { it.actionNames() }}",
            2,
            filters.size,
        )
        assertEquals(
            "FR-21: the share filters are SEND first, PROCESS_TEXT second (§5.1.8 manifest contract).",
            listOf(listOf(ACTION_SEND), listOf(ACTION_PROCESS_TEXT)),
            filters.map { it.actionNames() },
        )
        filters.forEach { filter ->
            assertTrue(
                "FR-21: every share filter must declare " +
                    "<category android:name=\"$CATEGORY_DEFAULT\"/>. " +
                    "Filter ${filter.actionNames()} declares ${filter.categoryNames()}.",
                filter.categoryNames().contains(CATEGORY_DEFAULT),
            )
            assertEquals(
                "FR-21: the share filters accept text/plain only. Filter ${filter.actionNames()} " +
                    "declares mime type(s) ${filter.mimeTypes()}.",
                listOf(MIME_TEXT_PLAIN),
                filter.mimeTypes(),
            )
        }
    }

    @Test
    fun `manifest stays least privilege with one activity and no service or receiver`() {
        assertEquals(
            "NFR-04: the inbound entry points add no permission — the manifest keeps zero " +
                "<uses-permission> entries.",
            0,
            manifest.elements("uses-permission").size,
        )
        assertEquals(
            "NFR-04: the inbound entry points add no <service> — the share path stays in-process.",
            0,
            manifest.elements("service").size,
        )
        assertEquals(
            "NFR-04: the inbound entry points add no <receiver>.",
            0,
            manifest.elements("receiver").size,
        )

        val activities = manifest.elements("activity")
        assertEquals(
            "NFR-04: .MainActivity stays the single activity — the only entry point is one " +
                "exported activity, found ${activities.map { it.getAttribute("android:name") }}.",
            1,
            activities.size,
        )

        val activity = manifest.mainActivity()
        assertEquals(
            "NFR-04: adding the share filters must not disturb the IME resize contract.",
            "adjustResize",
            activity.getAttribute("android:windowSoftInputMode"),
        )
        assertEquals(
            "NFR-04: the ephemeral buffer is still never resurrected by the platform backup.",
            "false",
            manifest.elements("application").single().getAttribute("android:allowBackup"),
        )
    }

    @Test
    fun `the inbound share contract holds for the real manifest`() {
        val violations = inboundViolations(manifest)

        assertEquals(
            "FR-21/NFR-04: the real manifest must satisfy the inbound share contract " +
                "(singleTop, SEND + PROCESS_TEXT for text/plain, zero permissions/services/" +
                "receivers). Violations:\n" + violations.joinToString("\n"),
            emptyList<String>(),
            violations,
        )
    }

    @Test
    fun `a manifest missing the PROCESS_TEXT filter fails the contract naming PROCESS_TEXT`() {
        val mutated = reparseManifest { document ->
            val activity = document.mainActivity()
            activity.shareFilters()
                .filter { filter -> filter.actionNames().contains(ACTION_PROCESS_TEXT) }
                .forEach { filter -> activity.removeChild(filter) }
        }
        assertEquals(
            "Fixture sanity: the copy must keep only the SEND filter before the contract is asked.",
            1,
            mutated.mainActivity().shareFilters().size,
        )

        val violations = inboundViolations(mutated)

        assertTrue(
            "The contract must reject a manifest without the PROCESS_TEXT share filter and name " +
                "$ACTION_PROCESS_TEXT. Violations: $violations",
            violations.any { it.contains(ACTION_PROCESS_TEXT) },
        )
    }

    @Test
    fun `a manifest without launchMode singleTop fails the contract naming launchMode`() {
        val mutated = reparseManifest { document ->
            document.mainActivity().removeAttribute("android:launchMode")
        }
        assertEquals(
            "Fixture sanity: the copy must no longer carry the attribute before the contract is asked.",
            false,
            mutated.mainActivity().hasAttribute("android:launchMode"),
        )

        val violations = inboundViolations(mutated)

        assertTrue(
            "The contract must reject a manifest whose activity is not singleTop and name " +
                "launchMode. Violations: $violations",
            violations.any { it.contains("launchMode") },
        )
    }

    @Test
    fun `a manifest with an added permission fails the contract naming that permission`() {
        val violations = inboundViolations(
            reparseManifest { document ->
                document.documentElement.appendChild(
                    document.createElement("uses-permission").apply {
                        setAttribute("android:name", ADDED_PERMISSION)
                    },
                )
            },
        )

        assertTrue(
            "The contract must reject a manifest that asks for a permission and name it. " +
                "Violations: $violations",
            violations.any { it.contains(ADDED_PERMISSION) },
        )
    }

    @Test
    fun `a manifest with an added service fails the contract naming the service element`() {
        val violations = inboundViolations(
            reparseManifest { document ->
                document.elements("application").single()
                    .appendChild(document.createElement("service"))
            },
        )

        assertTrue(
            "The contract must reject a manifest that declares a <service> and name it. " +
                "Violations: $violations",
            violations.any { it.contains("<service>") },
        )
    }

    /**
     * The inbound share contract (FR-21, NFR-04, §5.1.8), pure over a parsed [Document].
     *
     * @return every violation as a human-readable message; an empty list means the manifest is
     *   conformant. Pure: no field access, no I/O — the same call judges the real manifest and
     *   every failure fixture.
     */
    private fun inboundViolations(manifest: Document): List<String> {
        val violations = mutableListOf<String>()

        val activities = manifest.elements("activity")
        if (activities.size != 1) {
            violations += "NFR-04: expected exactly one <activity> (the single entry point), " +
                "found ${activities.size}: ${activities.map { it.getAttribute("android:name") }}"
        }

        val activity = activities.singleOrNull { it.getAttribute("android:name") == ACTIVITY_MAIN }
        if (activity == null) {
            violations += "FR-21: no <activity android:name=\"$ACTIVITY_MAIN\">, so the share " +
                "filters have no host activity"
        } else {
            val launchMode = activity.getAttribute("android:launchMode")
            if (launchMode != "singleTop") {
                violations += "FR-21: $ACTIVITY_MAIN android:launchMode must be \"singleTop\" so a " +
                    "share reaches onNewIntent; found \"$launchMode\""
            }

            val windowSoftInputMode = activity.getAttribute("android:windowSoftInputMode")
            if (windowSoftInputMode != "adjustResize") {
                violations += "NFR-04: $ACTIVITY_MAIN android:windowSoftInputMode must stay " +
                    "\"adjustResize\"; found \"$windowSoftInputMode\""
            }

            violations += shareFilterViolations(activity)
        }

        val allowBackup = manifest.elements("application").singleOrNull()
            ?.getAttribute("android:allowBackup")
        if (allowBackup != "false") {
            violations += "NFR-04: <application> android:allowBackup must stay \"false\" (the " +
                "buffer is ephemeral); found \"${allowBackup ?: "<missing application>"}\""
        }

        val forbidden = listOf("uses-permission", "service", "receiver")
        forbidden.forEach { tag ->
            val found = manifest.elements(tag)
            if (found.isNotEmpty()) {
                violations += "NFR-04: the manifest must declare zero <$tag> entries; found " +
                    "${found.size}: " +
                    found.joinToString { it.getAttribute("android:name").ifBlank { "(unnamed <$tag>)" } }
            }
        }

        return violations
    }

    private fun shareFilterViolations(activity: Element): List<String> {
        val violations = mutableListOf<String>()
        val filters = activity.shareFilters()
        val required = listOf(ACTION_SEND, ACTION_PROCESS_TEXT)

        required.forEach { action ->
            val matches = filters.filter { filter -> filter.actionNames().contains(action) }
            val filter = matches.singleOrNull()
            if (filter == null) {
                violations += "FR-21: expected exactly one non-launcher <intent-filter> for $action, " +
                    "found ${matches.size}; non-launcher filters: ${filters.map { it.actionNames() }}"
                return@forEach
            }
            if (!filter.categoryNames().contains(CATEGORY_DEFAULT)) {
                violations += "FR-21: the $action filter must declare " +
                    "<category android:name=\"$CATEGORY_DEFAULT\"/>"
            }
            if (!filter.mimeTypes().contains(MIME_TEXT_PLAIN)) {
                violations += "FR-21: the $action filter must declare " +
                    "<data android:mimeType=\"$MIME_TEXT_PLAIN\"/>"
            }
        }

        filters.filter { filter -> filter.actionNames().any { it !in required } }.forEach { filter ->
            violations += "FR-21: unexpected non-launcher <intent-filter> on the share activity: " +
                "${filter.actionNames()} — only $ACTION_SEND and $ACTION_PROCESS_TEXT may be added"
        }
        if (filters.size != required.size) {
            violations += "FR-21: the share activity must carry exactly ${required.size} " +
                "non-launcher <intent-filter> blocks, found ${filters.size}"
        }
        if (filters.map { it.actionNames() } != required.map { listOf(it) }) {
            violations += "FR-21: the non-launcher filters must be ordered $ACTION_SEND then " +
                "$ACTION_PROCESS_TEXT; found ${filters.map { it.actionNames() }}"
        }

        return violations
    }

    private fun parseManifest(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))

    /** A fresh tree over the captured bytes — the shared [manifest] is never mutated. */
    private fun reparseManifest(mutate: (Document) -> Unit): Document =
        parseManifest(manifestBytes).also(mutate)

    private fun Document.mainActivity(): Element {
        val activity = elements("activity")
            .singleOrNull { it.getAttribute("android:name") == ACTIVITY_MAIN }
        assertNotNull(
            "FR-21: the manifest must declare <activity android:name=\"$ACTIVITY_MAIN\">.",
            activity,
        )
        return activity!!
    }

    private fun Element.shareFilters(): List<Element> =
        children("intent-filter").filterNot { filter ->
            filter.children("action").any { it.getAttribute("android:name") == ACTION_MAIN } &&
                filter.children("category").any { it.getAttribute("android:name") == CATEGORY_LAUNCHER }
        }

    private fun Element.actionNames(): List<String> =
        children("action").map { it.getAttribute("android:name") }

    private fun Element.categoryNames(): List<String> =
        children("category").map { it.getAttribute("android:name") }

    private fun Element.mimeTypes(): List<String> =
        children("data").mapNotNull { data ->
            data.getAttribute("android:mimeType").takeIf { it.isNotEmpty() }
        }

    private fun Document.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.children(tagName: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tagName }
    }

    private companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
        const val MIME_TEXT_PLAIN = "text/plain"
        const val ACTIVITY_MAIN = ".MainActivity"

        // A permission the app must never ask for. Deliberately NOT the network grant the m8
        // gate scans for: the fixture only needs "an unrequested permission", and the gate's
        // denylist word must stay out of the repository.
        const val ADDED_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
