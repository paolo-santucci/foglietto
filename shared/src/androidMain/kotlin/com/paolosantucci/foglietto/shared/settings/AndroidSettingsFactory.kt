package com.paolosantucci.foglietto.shared.settings

import android.content.Context
import android.content.SharedPreferences
import com.paolosantucci.foglietto.shared.AndroidContext
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Name of the platform default preferences file, exactly as
 * `android.preference.PreferenceManager.getDefaultSharedPreferences(context)` computes it
 * (`context.getPackageName() + "_preferences"`).
 *
 * Declaring it here instead of calling that deprecated helper keeps the Android API-29+
 * deprecation out of production code while storing settings in the very same file the app and
 * the platform already use.
 */
private const val DEFAULT_PREFERENCES_NAME_SUFFIX = "_preferences"

/**
 * Android production factory for the settings repository.
 *
 * Mirrors `IosSettingsFactoryKt.createIosSettingsRepository()`: multiplatform-settings stays an
 * internal `implementation` detail, so consumers (`androidApp`) see only the shared
 * [SettingsRepository] interface — zero multiplatform-settings types cross this signature (FR-06).
 *
 * The store is the app's **default** `SharedPreferences`
 * ([defaultSharedPreferences]) — the same file `PreferenceManager.getDefaultSharedPreferences`
 * hands back — so settings survive process death and stay scoped to the app's private storage.
 *
 * Must be constructed after `AndroidContext.init(...)` (`FogliettoApplication.onCreate`); the
 * holder throws its loud `IllegalStateException` otherwise.
 *
 * Spec refs: §5.1.b, FR-02, FR-06, NFR-09.
 */
fun createAndroidSettingsRepository(): SettingsRepository =
    SettingsRepositoryImpl(SharedPreferencesSettings(defaultSharedPreferences(AndroidContext.applicationContext)))

/**
 * The application's default `SharedPreferences` handle (single definition of the file name and
 * mode used by [createAndroidSettingsRepository]).
 *
 * `internal` — the app module must receive settings through [SettingsRepository], never by
 * touching the store directly.
 */
internal fun defaultSharedPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(
        context.packageName + DEFAULT_PREFERENCES_NAME_SUFFIX,
        Context.MODE_PRIVATE,
    )
