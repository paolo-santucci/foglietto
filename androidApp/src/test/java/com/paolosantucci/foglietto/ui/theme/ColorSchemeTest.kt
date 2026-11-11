package com.paolosantucci.foglietto.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A — the colour scheme contract (NFR-04 contrast, Keep-style neutrality).
 *
 * The scheme is asserted *by role*, because the roles are what the widgets read: the action
 * buttons take `secondaryContainer`/`onSecondaryContainer` (that is the pair that turned purple
 * under the Material baseline scheme), the sheet takes `surfaceContainerLow`, and the editor ink is
 * `onSurface` over `surfaceVariant`.
 *
 * Contrast is computed here rather than eyeballed: WCAG 2.1 AA wants ≥ 4.5:1 for body text, and
 * the icons inside the tonal circles are 24dp glyphs, which are not "large text" — so 4.5:1 is the
 * floor, not 3:1.
 *
 * Plain JVM: `Color` is a value class over `ULong` with pure-Kotlin arithmetic, and
 * `lightColorScheme`/`darkColorScheme` are plain functions — no Android runtime is needed.
 */
class ColorSchemeTest {

    @Test
    fun `the light scheme is driven by the neutral palette`() {
        assertScheme(
            scheme = FogliettoLightColorScheme,
            surface = 0xFFFFFFFF,
            onSurface = 0xFF1F1F1F,
            secondaryContainer = 0xFFE9EEF6,
            onSecondaryContainer = 0xFF3C4043,
            primary = 0xFF1A73E8,
            onPrimary = 0xFFFFFFFF,
            surfaceVariant = 0xFFF0F3F8,
            onSurfaceVariant = 0xFF444746,
            outlineVariant = 0xFFDADCE0,
            surfaceContainerLow = 0xFFFFFFFF,
            surfaceContainerHigh = 0xFFF0F3F8,
        )
    }

    @Test
    fun `the dark scheme is driven by the same palette, dark column`() {
        assertScheme(
            scheme = FogliettoDarkColorScheme,
            surface = 0xFF202020,
            onSurface = 0xFFE3E3E3,
            secondaryContainer = 0xFF3C4043,
            onSecondaryContainer = 0xFFE3E3E3,
            primary = 0xFFA8C7FA,
            onPrimary = 0xFF062E6F,
            surfaceVariant = 0xFF3C4043,
            onSurfaceVariant = 0xFFC4C7C5,
            outlineVariant = 0xFF5F6368,
            surfaceContainerLow = 0xFF2A2B2E,
            surfaceContainerHigh = 0xFF333538,
        )
    }

    @Test
    fun `no painted role resolves to the Material baseline purple`() {
        // The baseline light scheme is seeded on #6750A4 and the lavender container family; the
        // dark scheme swaps in #D0BCFF/#381E72. One missed role is enough to paint a purple circle
        // again, so every role the chrome can reach is scanned.
        val purpleTones = setOf(
            0xFF6750A4L,
            0xFFD0BCFFL,
            0xFFEADDFFL,
            0xFFE8DEF8L,
            0xFFE9DDFFL,
            0xFF4F378BL,
            0xFF381E72L,
            0xFF21005DL,
            0xFF625B71L,
            0xFF7D5260L,
            0xFFFFD8E4L,
            0xFFCCC2DCL,
        )

        listOf(FogliettoLightColorScheme, FogliettoDarkColorScheme).forEach { scheme ->
            scheme.paintedRoles().forEach { (role, color) ->
                val argb = color.toArgb().toLong() and 0xFFFFFFFFL
                assertFalse(
                    "$role resolves to the Material baseline purple " +
                        "#${argb.toString(16).uppercase(Locale.ROOT)} — the scheme must be neutral.",
                    purpleTones.contains(argb),
                )
            }
        }
    }

    @Test
    fun `the tonal button pair meets AA contrast in both modes`() {
        assertContrastAtLeast(
            foreground = FogliettoLightColorScheme.onSecondaryContainer,
            background = FogliettoLightColorScheme.secondaryContainer,
            minimum = AA_BODY_TEXT,
            label = "light onSecondaryContainer/secondaryContainer",
        )
        assertContrastAtLeast(
            foreground = FogliettoDarkColorScheme.onSecondaryContainer,
            background = FogliettoDarkColorScheme.secondaryContainer,
            minimum = AA_BODY_TEXT,
            label = "dark onSecondaryContainer/secondaryContainer",
        )
    }

    @Test
    fun `body ink on the secondary fill meets AA contrast in both modes`() {
        assertContrastAtLeast(
            foreground = FogliettoLightColorScheme.onSurface,
            background = FogliettoLightColorScheme.surfaceVariant,
            minimum = AA_BODY_TEXT,
            label = "light onSurface/surfaceVariant",
        )
        assertContrastAtLeast(
            foreground = FogliettoDarkColorScheme.onSurface,
            background = FogliettoDarkColorScheme.surfaceVariant,
            minimum = AA_BODY_TEXT,
            label = "dark onSurface/surfaceVariant",
        )
    }

    @Test
    fun `the canvas and its ink meet AA contrast in both modes`() {
        assertContrastAtLeast(
            foreground = FogliettoLightColorScheme.onSurface,
            background = FogliettoLightColorScheme.surface,
            minimum = AA_BODY_TEXT,
            label = "light onSurface/surface",
        )
        assertContrastAtLeast(
            foreground = FogliettoDarkColorScheme.onSurface,
            background = FogliettoDarkColorScheme.surface,
            minimum = AA_BODY_TEXT,
            label = "dark onSurface/surface",
        )
    }

    @Test
    fun `the sheet surface carries legible supporting ink`() {
        assertContrastAtLeast(
            foreground = FogliettoLightColorScheme.onSurfaceVariant,
            background = FogliettoLightColorScheme.surfaceContainerLow,
            minimum = AA_BODY_TEXT,
            label = "light onSurfaceVariant/surfaceContainerLow",
        )
        assertContrastAtLeast(
            foreground = FogliettoDarkColorScheme.onSurfaceVariant,
            background = FogliettoDarkColorScheme.surfaceContainerLow,
            minimum = AA_BODY_TEXT,
            label = "dark onSurfaceVariant/surfaceContainerLow",
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun assertScheme(
        scheme: ColorScheme,
        surface: Long,
        onSurface: Long,
        secondaryContainer: Long,
        onSecondaryContainer: Long,
        primary: Long,
        onPrimary: Long,
        surfaceVariant: Long,
        onSurfaceVariant: Long,
        outlineVariant: Long,
        surfaceContainerLow: Long,
        surfaceContainerHigh: Long,
    ) {
        assertEquals("surface", Color(surface), scheme.surface)
        assertEquals("onSurface", Color(onSurface), scheme.onSurface)
        assertEquals("background follows surface", scheme.surface, scheme.background)
        assertEquals("onBackground follows onSurface", scheme.onSurface, scheme.onBackground)
        assertEquals("secondaryContainer", Color(secondaryContainer), scheme.secondaryContainer)
        assertEquals("onSecondaryContainer", Color(onSecondaryContainer), scheme.onSecondaryContainer)
        assertEquals("primary", Color(primary), scheme.primary)
        assertEquals("onPrimary", Color(onPrimary), scheme.onPrimary)
        assertEquals("surfaceVariant", Color(surfaceVariant), scheme.surfaceVariant)
        assertEquals("onSurfaceVariant", Color(onSurfaceVariant), scheme.onSurfaceVariant)
        assertEquals("outlineVariant", Color(outlineVariant), scheme.outlineVariant)
        assertEquals("surfaceContainerLow", Color(surfaceContainerLow), scheme.surfaceContainerLow)
        assertEquals("surfaceContainerHigh", Color(surfaceContainerHigh), scheme.surfaceContainerHigh)
        // The tonal roles that are not painted yet must not fall back to the baseline either.
        assertEquals(
            "primaryContainer mirrors the neutral secondary container",
            scheme.secondaryContainer,
            scheme.primaryContainer,
        )
        assertEquals(
            "tertiaryContainer mirrors the neutral secondary container",
            scheme.secondaryContainer,
            scheme.tertiaryContainer,
        )
        assertEquals("surfaceContainer mirrors surfaceVariant", scheme.surfaceVariant, scheme.surfaceContainer)
        assertEquals("surfaceTint is flat (no hue shift on elevation)", scheme.surface, scheme.surfaceTint)
    }

    /** Every role this app's chrome (or a stock M3 component it hosts) can paint. */
    private fun ColorScheme.paintedRoles(): List<Pair<String, Color>> = listOf(
        "primary" to primary,
        "onPrimary" to onPrimary,
        "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer,
        "secondary" to secondary,
        "onSecondary" to onSecondary,
        "secondaryContainer" to secondaryContainer,
        "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary,
        "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer,
        "surface" to surface,
        "onSurface" to onSurface,
        "surfaceVariant" to surfaceVariant,
        "onSurfaceVariant" to onSurfaceVariant,
        "outlineVariant" to outlineVariant,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainer" to surfaceContainer,
        "surfaceTint" to surfaceTint,
        "inversePrimary" to inversePrimary,
    )

    private fun assertContrastAtLeast(
        foreground: Color,
        background: Color,
        minimum: Double,
        label: String,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "${label} is ${String.format(Locale.ROOT, "%.2f", ratio)}:1 — below the $minimum:1 floor.",
            ratio >= minimum,
        )
    }

    /** WCAG 2.1 §1.4.3 relative luminance, then the contrast ratio. */
    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = relativeLuminance(foreground)
        val second = relativeLuminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)

    private fun linearize(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    private companion object {
        /** WCAG 2.1 AA, normal text and UI glyphs. */
        const val AA_BODY_TEXT = 4.5
    }
}
