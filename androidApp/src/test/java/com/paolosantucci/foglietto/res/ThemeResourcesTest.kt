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
 *  - both resource qualifiers define the *same* base style, so no configuration falls back to a
 *    half-themed window;
 *  - the parent is an API-14-safe `NoActionBar` variant — never `Theme.DeviceDefault.DayNight`,
 *    which is API 29+ and would crash the launch on API 26–28 (minSdk 26);
 *  - the base window background is the editor canvas the Compose scheme paints, so the launch
 *    frame and the first composed frame are the same colour;
 *  - `Theme.Foglietto` itself is a one-line delegate to that base, so every qualifier inherits the
 *    canvas instead of restating (and drifting from) it;
 *  - the Android 12+ splash surface (`values-v31` + `values-night-v31`) paints itself with the
 *    launcher icon's own background token — the splash must read as the icon, and only the splash
 *    is allowed to change, never the app window.
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
    fun `the light base theme is a no-action-bar theme painted in the editor canvas`() {
        val base = style("values", BASE_NAME)

        assertTrue(
            "Theme.Foglietto.Base must be a NoActionBar variant so the platform adds no action " +
                "bar under the Compose TopAppBar: ${base.parent}",
            base.parent.contains("NoActionBar"),
        )
        assertFalse(
            "Theme.DeviceDefault.DayNight is API 29+; with minSdk 26 it would crash the launch on " +
                "API 26-28. The day/night split belongs in values/ + values-night/.",
            base.parent.contains("DayNight"),
        )
        assertEquals("#FFFFFF", base.windowBackground)
    }

    @Test
    fun `the night base theme is the dark half of the same style`() {
        val day = style("values", BASE_NAME)
        val night = style("values-night", BASE_NAME)

        assertEquals("values-night must define the same style name.", day.name, night.name)
        assertTrue(
            "The night parent must be a NoActionBar DeviceDefault variant: ${night.parent}",
            night.parent.contains("DeviceDefault") && night.parent.contains("NoActionBar"),
        )
        assertEquals("#1C1B1F", night.windowBackground)
    }

    @Test
    fun `the activity theme delegates to the base in every qualifier`() {
        // A qualified <style> REPLACES its base definition rather than merging into it, so the
        // delegate is what keeps the canvas (and the parent) in every configuration. A variant
        // that inlined its own copy of the items would drift on the next canvas tweak.
        for (directory in listOf("values", "values-night")) {
            val theme = style(directory, THEME_NAME)
            assertEquals(
                "Theme.Foglietto in $directory must delegate to $BASE_NAME.",
                BASE_NAME,
                theme.parent,
            )
        }
    }

    @Test
    fun `the splash background is the launcher icon background`() {
        // The requirement, verbatim: the startup splash uses the icon's background colour. Reading
        // the reference off the adaptive icon (instead of restating @color/ic_launcher_background)
        // makes the two impossible to drift apart.
        val icon = parse(mainDir.resolve("res/mipmap-anydpi-v26/ic_launcher.xml"))
        val iconBackground = icon.elements("background")
            .single()
            .getAttribute("android:drawable")
            .trim()
            .uppercase()
        assertEquals("@COLOR/IC_LAUNCHER_BACKGROUND", iconBackground)

        // Both API-31 qualifiers: night outranks the platform version in resource matching, so the
        // night-qualified file is the only thing that keeps the splash item alive in dark mode.
        for (directory in listOf("values-v31", "values-night-v31")) {
            val splash = style(directory, THEME_NAME)
            assertEquals(
                "The API-31 splash in $directory must paint the launcher icon's background.",
                iconBackground,
                splash.splashBackground,
            )
            assertEquals(
                "Theme.Foglietto in $directory must keep the canvas through $BASE_NAME.",
                BASE_NAME,
                splash.parent,
            )
            assertEquals(
                "The splash must re-skin the splash surface only — $directory must not set a " +
                    "window background of its own.",
                null,
                splash.windowBackground,
            )
        }
    }

    private fun style(directory: String, name: String): ThemeStyle {
        val document = parse(mainDir.resolve("res/$directory/themes.xml"))
        val style = document.elements("style")
            .firstOrNull { it.getAttribute("name") == name }
            ?: error("$directory/themes.xml does not declare <style name=\"$name\">")

        fun item(itemName: String): String? =
            document.elements("item")
                .firstOrNull { it.getAttribute("name") == itemName }
                ?.textContent
                ?.trim()
                ?.uppercase()

        return ThemeStyle(
            name = style.getAttribute("name"),
            parent = style.getAttribute("parent"),
            windowBackground = item("android:windowBackground"),
            splashBackground = item("android:windowSplashScreenBackground"),
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
        val windowBackground: String?,
        val splashBackground: String?,
    )

    private companion object {
        const val THEME_NAME = "Theme.Foglietto"
        const val BASE_NAME = "Theme.Foglietto.Base"
    }
}
