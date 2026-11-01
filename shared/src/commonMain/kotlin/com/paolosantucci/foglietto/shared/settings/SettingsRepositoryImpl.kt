package com.paolosantucci.foglietto.shared.settings

import com.russhwolf.settings.Settings

/**
 * [SettingsRepository] implementation backed by [com.russhwolf.settings.Settings]
 * (multiplatform-settings library).
 *
 * Platform-abstraction strategy (NFR-06): `Settings` IS the platform abstraction
 * already — [com.russhwolf.settings.NSUserDefaultsSettings] on iOS,
 * [com.russhwolf.settings.MapSettings]/[com.russhwolf.settings.PropertiesSettings]
 * in tests. No `expect/actual` is introduced here; the `Settings` instance is
 * injected via the constructor.
 *
 * Load safety guarantees (FR-22, FR-23, FR-24, EC-01, EC-04, S-I1):
 * - Color-scheme: parsed via an explicit `when` expression. Unrecognised or null
 *   values fall through to `AppColorScheme.follow`. `valueOf`/`enumValueOf` are
 *   NEVER used (they throw on unknown values — EC-01).
 * - Font-size: read via `runCatching { settings.getIntOrNull(KEY) }.getOrNull()`.
 *   This absorbs [ClassCastException] thrown by NSUserDefaultsSettings on a
 *   type mismatch (S-I1 / OQ-C finding). A null result (absent or corrupt key)
 *   falls back to the default index 8. The stored value is then clamped to
 *   `[0, AppSettings.slotList.lastIndex]` (read-side clamp, completing the
 *   double-clamp with the write-side clamp in [AppSettings.setFontSizeIndex]).
 *
 * [IMPL NOTE — OQ-C] MapSettings backed by PropertiesSettings does NOT throw
 * ClassCastException on wrong-type (Properties stores everything as String and
 * getIntOrNull returns null on parse failure). NSUserDefaultsSettings (iOS)
 * DOES throw. The `runCatching` wrapper guards both paths. Tests exercise the
 * throw path via a ThrowingSettings stub (see SettingsRepositoryTest).
 *
 * Save contract (FR-25, OQ-02): writes exactly two keys —
 *   [AppSettings.KEY_COLOR_SCHEME] and [AppSettings.KEY_FONT_SIZE].
 * The five dropped GNOME keys (use-monospace-font, check-spelling,
 * save-emergency-files, emergency-recovery-files, line-length) are NEVER written.
 *
 * Spec refs: FR-22, FR-23, FR-24, FR-25, §5.1.2, §4.3, NFR-06.
 */
class SettingsRepositoryImpl(
    private val settings: Settings,
) : SettingsRepository {

    override fun load(): AppSettings {
        val colorScheme = parseColorScheme(settings.getStringOrNull(AppSettings.KEY_COLOR_SCHEME))
        val fontSizeIndex = loadFontSizeIndex()
        return AppSettings(colorScheme = colorScheme, fontSizeIndex = fontSizeIndex)
    }

    override fun save(settings: AppSettings) {
        // Single mutation point — exactly two keys, no dropped GNOME keys (FR-25, OQ-02).
        this.settings.putString(AppSettings.KEY_COLOR_SCHEME, settings.colorScheme.name)
        this.settings.putInt(AppSettings.KEY_FONT_SIZE, settings.fontSizeIndex)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses [value] to [AppColorScheme] using an explicit `when` — never
     * `valueOf` or `enumValueOf` (which throw on unknown values, EC-01).
     *
     * null, absent, or any unrecognised string maps to [AppColorScheme.follow].
     */
    private fun parseColorScheme(value: String?): AppColorScheme = when (value) {
        "light" -> AppColorScheme.light
        "dark" -> AppColorScheme.dark
        else -> AppColorScheme.follow // "follow", null, absent, or any garbage → canonical default
    }

    /**
     * Reads the stored font-size index defensively.
     *
     * [com.russhwolf.settings.Settings.getIntOrNull] on NSUserDefaultsSettings
     * throws [ClassCastException] when the stored type is not an Int. The
     * `runCatching{}.getOrNull()` wrapper absorbs both the null path (absent key)
     * and the throw path (wrong type on NSUserDefaultsSettings) — returning null
     * in both cases.
     *
     * null → default index 8; stored value → clamped to `[0, slotList.lastIndex]`.
     */
    private fun loadFontSizeIndex(): Int {
        val raw = runCatching { settings.getIntOrNull(AppSettings.KEY_FONT_SIZE) }.getOrNull()
        val index = raw ?: 8 // absent or corrupt → canonical default
        return index.coerceIn(0, AppSettings.slotList.lastIndex)
    }
}
