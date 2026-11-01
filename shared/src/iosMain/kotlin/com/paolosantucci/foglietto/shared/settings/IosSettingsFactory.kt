package com.paolosantucci.foglietto.shared.settings

import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults

/**
 * iOS production factory for the settings repository.
 * Keeps multiplatform-settings an internal `implementation` dependency — Swift sees only
 * the exported `SettingsRepository` interface via `IosSettingsFactoryKt.createIosSettingsRepository()`.
 */
fun createIosSettingsRepository(): SettingsRepository =
    SettingsRepositoryImpl(NSUserDefaultsSettings(delegate = NSUserDefaults.standardUserDefaults))
