package com.paolosantucci.foglietto.shared.settings

/**
 * Repository contract for persisting and retrieving [AppSettings].
 *
 * Contract:
 * - [load] always returns a valid [AppSettings]. If no persisted value exists
 *   for a given key, the field's default value is used. This method never throws
 *   on missing or corrupt keys (FR-22, FR-23, EC-01, EC-04).
 * - [save] is the single mutation point for all settings. Writes exactly the two
 *   MVP keys ([AppSettings.KEY_COLOR_SCHEME] and [AppSettings.KEY_FONT_SIZE]);
 *   none of the five dropped GNOME keys (OQ-02, FR-25).
 *
 * Non-suspend by design: the multiplatform-settings [com.russhwolf.settings.Settings]
 * API is synchronous; no coroutine overhead is warranted (spec §5.1.2 / NFR-06).
 *
 * Spec refs: FR-22, FR-23, FR-24, FR-25, §4.3 settings round-trip.
 */
interface SettingsRepository {
    /**
     * Loads persisted settings.
     *
     * Returns [AppSettings] with defaults for any keys absent from or corrupt in
     * the store. Never throws on missing keys or wrong-typed values.
     */
    fun load(): AppSettings

    /**
     * Persists [settings] to the backing store.
     *
     * Writes exactly two keys: [AppSettings.KEY_COLOR_SCHEME] (the enum name
     * as a String) and [AppSettings.KEY_FONT_SIZE] (the Int index). No other
     * keys are written. Single mutation point for all callers.
     */
    fun save(settings: AppSettings)
}
