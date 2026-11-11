package com.paolosantucci.foglietto.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Tier A — the platform theme behind `android:theme="@style/Theme.Foglietto"` (I7).
 *
 * The theme is not decoration: without one the window paints the framework default (action bar,
 * pre-Compose colours) before the first frame. These assertions pin that
 *  - both resource qualifiers define the *same* style, so no configuration falls back to a
 *    half-themed window;
 *  - the parent is an API-14-safe `NoActionBar` variant — never `Theme.DeviceDefault.DayNight`,
 *    which is API 29+ and would crash the launch on API 26–28 (minSdk 26);
 *  - the window background is the editor canvas the Compose scheme paints, so the launch frame and
 *    the first composed frame are the same colour.
 *
 * Parsed namespace-unaware, like the other resource contracts (`ManifestContractTest`,
 * `LauncherIconTest`), so attributes stay readable as `android:name`.
 */
class ThemeResourcesTest {

    private val mainDir: File by lazy {
        listOf(File("src/main"), File("androidApp/src/main"))
            .firstOrNull { it.isDirectory }
            ?: error("Android main source set not found from ${File(".").absolutePath}")
    }

    @Test
    fun `the light theme is a no-action-bar theme painted in the editor canvas`() {
        val theme = style("values")

        assertTrue(
            "Theme.Foglietto must be a NoActionBar variant so the platform adds no action bar " +
                "under the Compose TopAppBar: ${theme.parent}",
            theme.parent.contains("NoActionBar"),
        )
        assertFalse(
            "Theme.DeviceDefault.DayNight is API 29+; with minSdk 26 it would crash the launch on " +
                "API 26-28. The day/night split belongs in values/ + values-night/.",
            theme.parent.contains("DayNight"),
        )
        assertEquals("#FFFFFF", theme.windowBackground)
    }

    @Test
    fun `the night theme is the dark half of the same style`() {
        val day = style("values")
        val night = style("values-night")

        assertEquals("values-night must define the same style name.", day.name, night.name)
        assertTrue(
            "The night parent must be a NoActionBar DeviceDefault variant: ${night.parent}",
            night.parent.contains("DeviceDefault") && night.parent.contains("NoActionBar"),
        )
        assertEquals("#1C1B1F", night.windowBackground)
    }

    private fun style(directory: String): ThemeStyle {
        val document = parse(mainDir.resolve("res/$directory/themes.xml"))
        val style = document.elements("style")
            .firstOrNull { it.getAttribute("name") == THEME_NAME }
            ?: error("$directory/themes.xml does not declare <style name=\"$THEME_NAME\">")

        fun item(name: String): String =
            document.elements("item")
                .firstOrNull { it.getAttribute("name") == name }
                ?.textContent
                ?.trim()
                ?: error("$directory/themes.xml does not declare <item name=\"$name\">")

        return ThemeStyle(
            name = style.getAttribute("name"),
            parent = style.getAttribute("parent"),
            windowBackground = item("android:windowBackground").uppercase(),
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

    private data class ThemeStyle(
        val name: String,
        val parent: String,
        val windowBackground: String,
    )

    private companion object {
        const val THEME_NAME = "Theme.Foglietto"
    }
}
