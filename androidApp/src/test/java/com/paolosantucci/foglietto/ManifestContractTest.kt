package com.paolosantucci.foglietto

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
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

    private val manifest: Document by lazy {
        val file = listOf(
            File("src/main/AndroidManifest.xml"),
            File("androidApp/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")

        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
    }

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
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    }
}
