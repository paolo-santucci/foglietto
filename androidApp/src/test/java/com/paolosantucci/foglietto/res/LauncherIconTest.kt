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
 *  - every layer resolves to its drawable either inline (`android:drawable` on the layer) or
 *    through a single wrapper child — `<inset android:drawable="…" />` — which is how the
 *    artwork is given breathing room inside the mask;
 *  - the foreground layer is a real `<vector>` (an inline bitmap would silently bloat and
 *    blur — the derivation is a faithful vector mark, per OQ-05) with a square viewport, every
 *    path painted;
 *  - the background layer resolves to a declared `<color>` which aliases `@color/brand_yellow`,
 *    and that token is the canon brand light token (`#F6D32D`, ui-design-bible §Colour & branding
 *    tokens).
 *
 * The foreground is an imported illustration: it carries its own palette (hex fills plus
 * `<aapt:attr>` gradient stops), so the test asserts that every path is *painted*, not that it
 * references a single brand token — that invariant retired with the hand-drawn placeholder mark.
 *
 * The XML is parsed namespace-unaware (like [com.paolosantucci.foglietto.ManifestContractTest]),
 * so attributes stay readable as `android:name` (and `aapt:attr` keeps its literal tag name).
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

            assertEquals(
                "$name.xml foreground must be the vector mark.",
                "@drawable/ic_launcher_foreground",
                drawableReference(icon.documentElement.children("foreground").single(), name),
            )
            assertEquals(
                "$name.xml background must be the brand colour resource.",
                "@color/ic_launcher_background",
                drawableReference(icon.documentElement.children("background").single(), name),
            )
            // The layer wrappers themselves must exist, with no stray duplicates.
            assertEquals(1, icon.documentElement.children("background").size)
            assertEquals(1, icon.documentElement.children("foreground").size)
            assertEquals(1, icon.documentElement.children("monochrome").size)
        }
    }

    @Test
    fun `both adaptive icons declare the same monochrome layer`() {
        // S11: the themed-icon layer. The launcher consumes it as an alpha mask and tints it, so
        // pointing it at the mark's vector (rather than duplicating the paths) is the contract.
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = parse(mainDir.resolve("res/mipmap-anydpi-v26/$name.xml"))
            val monochrome = icon.documentElement.children("monochrome").single()

            assertEquals(
                "$name.xml must declare a <monochrome> layer for API 33+ themed icons.",
                "@drawable/ic_launcher_foreground",
                drawableReference(monochrome, name),
            )
        }
    }

    @Test
    fun `the foreground is a square vector whose every path is painted`() {
        val foreground = parse(mainDir.resolve("res/drawable/ic_launcher_foreground.xml"))
        val root = foreground.documentElement

        assertEquals("vector", root.tagName)

        val width = root.getAttribute("android:width")
        val height = root.getAttribute("android:height")
        assertTrue(
            "The foreground vector must declare a square intrinsic size (was $width x $height).",
            width.isNotEmpty() && width == height,
        )

        val viewportWidth = root.getAttribute("android:viewportWidth").toFloatOrNull()
        val viewportHeight = root.getAttribute("android:viewportHeight").toFloatOrNull()
        assertTrue(
            "The foreground vector must declare a square viewport (was $viewportWidth x $viewportHeight).",
            viewportWidth != null && viewportWidth == viewportHeight && viewportWidth > 0f,
        )

        val paths = foreground.elements("path")
        assertTrue("The foreground vector must draw at least one path.", paths.isNotEmpty())
        for (path in paths) {
            val fill = path.getAttribute("android:fillColor")
            val stroke = path.getAttribute("android:strokeColor")
            val gradient = path.children("aapt:attr")
                .firstOrNull { it.getAttribute("name") == "android:fillColor" }
            val painted = listOf(fill, stroke).any { PAINT.matches(it) } || gradient != null
            assertTrue(
                "Every foreground path must be painted: a #RRGGBB[AA] fill, a @color/<name> " +
                    "reference, or an <aapt:attr name=\"android:fillColor\"> gradient. Offending " +
                    "path starts with ${path.getAttribute("android:pathData").take(24)}",
                painted,
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

    /**
     * Resolves the drawable an `<adaptive-icon>` layer points at. The reference is either inline
     * (`<foreground android:drawable="…" />`) or carried by a single wrapper child
     * (`<foreground><inset android:drawable="…" /></foreground>`), which is how the artwork is
     * inset inside the launcher mask.
     */
    private fun drawableReference(layer: Element, iconName: String): String {
        val inline = layer.getAttribute("android:drawable")
        if (inline.isNotEmpty()) return inline

        val wrapper = layer.allChildren().singleOrNull()
        assertTrue(
            "$iconName.xml <${layer.tagName}> carries no android:drawable, directly or in a " +
                "single wrapper child.",
            wrapper != null,
        )
        return wrapper!!.getAttribute("android:drawable")
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

    private fun Element.allChildren(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
    }

    private companion object {
        /** A restated hex fill (`#RRGGBB` / `#AARRGGBB`) or a palette reference (`@color/name`). */
        val PAINT = Regex("@color/[a-z0-9_]+|#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")
    }
}
