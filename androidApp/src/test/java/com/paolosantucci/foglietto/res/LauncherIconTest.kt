package com.paolosantucci.foglietto.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Tier A launcher-icon contract — plain JVM XML parse, no Robolectric (TASK-12, FR-41/OQ-05).
 *
 * Pins the adaptive-icon wiring the platform needs at install time:
 *  - `<application>` points at both `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`
 *    (the round variant is what launchers request on masked devices);
 *  - each `mipmap-anydpi-v26/` icon is a well-formed `<adaptive-icon>` carrying a
 *    `<foreground>`, a `<background>` and a `<monochrome>` layer (themed icons, API 33+);
 *  - the foreground layer is a real `<vector>` (an inline bitmap would silently bloat and
 *    blur — the derivation is a faithful vector mark, per OQ-05) painted by reference to the
 *    `@color/brand_brown` token, never a restated hex (S11);
 *  - the background layer resolves to a declared `<color>` which aliases `@color/brand_yellow`,
 *    and that token is the canon brand light token (`#F6D32D`, ui-design-bible §Colour & branding
 *    tokens).
 *
 * The XML is parsed namespace-unaware (like [com.paolosantucci.foglietto.ManifestContractTest]),
 * so attributes stay readable as `android:name`.
 */
class LauncherIconTest {

    private val mainDir: File by lazy {
        listOf(File("src/main"), File("androidApp/src/main"))
            .firstOrNull { it.isDirectory }
            ?: error("Android main source set not found from ${File(".").absolutePath}")
    }

    private val manifest: Document by lazy { parse(mainDir.resolve("AndroidManifest.xml")) }

    @Test
    fun `the manifest declares the adaptive icon`() {
        val application = manifest.elements("application").single()

        assertEquals(
            "android:icon must point at the adaptive icon mipmap.",
            "@mipmap/ic_launcher",
            application.getAttribute("android:icon"),
        )
        assertEquals(
            "android:roundIcon must point at the round adaptive icon mipmap.",
            "@mipmap/ic_launcher_round",
            application.getAttribute("android:roundIcon"),
        )
    }

    @Test
    fun `the adaptive icon layers resolve`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = parse(mainDir.resolve("res/mipmap-anydpi-v26/$name.xml"))

            assertEquals(
                "$name.xml must be an <adaptive-icon>.",
                "adaptive-icon",
                icon.documentElement.tagName,
            )

            val foreground = icon.documentElement.children("foreground").single()
            val background = icon.documentElement.children("background").single()

            assertEquals(
                "$name.xml foreground must be the vector mark.",
                "@drawable/ic_launcher_foreground",
                foreground.getAttribute("android:drawable"),
            )
            assertEquals(
                "$name.xml background must be the brand colour resource.",
                "@color/ic_launcher_background",
                background.getAttribute("android:drawable"),
            )
        }
    }

    @Test
    fun `both adaptive icons declare the same monochrome layer`() {
        // S11: the themed-icon layer. The launcher consumes it as an alpha mask and tints it, so
        // reusing the mark's vector (rather than duplicating the path) is the whole contract.
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = parse(mainDir.resolve("res/mipmap-anydpi-v26/$name.xml"))
            val monochrome = icon.documentElement.children("monochrome").single()

            assertEquals(
                "$name.xml must declare a <monochrome> layer for API 33+ themed icons.",
                "@drawable/ic_launcher_foreground",
                monochrome.getAttribute("android:drawable"),
            )
        }
    }

    @Test
    fun `the foreground is a vector mark in the brand ink`() {
        val foreground = parse(mainDir.resolve("res/drawable/ic_launcher_foreground.xml"))

        assertEquals("vector", foreground.documentElement.tagName)
        assertEquals("108dp", foreground.documentElement.getAttribute("android:width"))
        assertEquals("108dp", foreground.documentElement.getAttribute("android:height"))
        assertTrue(
            "The vector must declare a 108dp viewport (adaptive-icon canvas).",
            foreground.documentElement.getAttribute("android:viewportWidth") == "108" &&
                foreground.documentElement.getAttribute("android:viewportHeight") == "108",
        )

        val paths = foreground.elements("path")
        assertTrue("The foreground vector must draw at least one path.", paths.isNotEmpty())
        for (path in paths) {
            val paint = if (path.hasAttribute("android:fillColor")) {
                path.getAttribute("android:fillColor")
            } else {
                path.getAttribute("android:strokeColor")
            }
            assertEquals(
                "Every foreground path must be painted in the brand dark token, by reference " +
                    "(S11: @color/brand_brown, never a restated hex).",
                "@color/brand_brown",
                paint.lowercase(),
            )
        }
    }

    @Test
    fun `the brand tokens hold the canon pairing`() {
        val values = parse(mainDir.resolve("res/values/colors.xml"))

        val tokens = values.elements("color").associate { color ->
            color.getAttribute("name") to color.textContent.trim().uppercase()
        }

        assertEquals(
            "The adaptive-icon background must be the canon brand light token (#F6D32D).",
            "#F6D32D",
            tokens["brand_yellow"],
        )
        assertEquals(
            "The launcher mark must be in the canon brand dark token (#873000).",
            "#873000",
            tokens["brand_brown"],
        )
    }

    @Test
    fun `the background colour aliases the brand light token`() {
        // The adaptive background must not restate the hex: it references the palette resource, so
        // a brand tweak cannot leave the launcher behind (S11).
        val values = parse(mainDir.resolve("res/values/ic_launcher_background.xml"))
        val color = values.elements("color")
            .single { it.getAttribute("name") == "ic_launcher_background" }

        assertEquals(
            "The adaptive-icon background must alias @color/brand_yellow.",
            "@color/brand_yellow",
            color.textContent.trim().lowercase(),
        )
    }

    private fun parse(file: File): Document {
        assertTrue("Missing resource: ${file.absolutePath}", file.isFile)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
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
}
