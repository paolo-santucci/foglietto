package com.paolosantucci.foglietto.shared.recovery

import okio.FileSystem
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** The UTC zone used for every recovery timestamp on Android (NFR-06). */
private val utcTimeZone: TimeZone = TimeZone.getTimeZone("UTC")

/**
 * Android production factory for the recovery repository.
 *
 * Mirrors `IosRecoveryFactoryKt.createIosRecoveryRepository()`: okio stays an internal
 * `implementation` detail, so consumers (`androidApp`) see only the shared [RecoveryRepository]
 * interface — zero okio types cross this signature (FR-06).
 *
 * ## Directory (NFR-09)
 *
 * [recoveryBaseDir] resolves `<filesDir>/recovery` through `AndroidContext`, i.e. app-private
 * internal storage. This factory adds no storage location of its own: every recovery file is
 * created under that one directory by [FileRecoveryRepository].
 *
 * ## `now` lambda — UTC calendar-field decomposition (NFR-06)
 *
 * The injected clock decomposes a [Date] through a [Calendar] pinned to
 * `TimeZone.getTimeZone("UTC")` into the seven Int fields of [RecoveryInstant]. Fields are read
 * from the calendar only — no epoch arithmetic and no millisecond scaling — so filenames are
 * timezone-independent and lexicographically chronological (FR-11).
 *
 * ## Lifecycle
 *
 * Must be constructed after `AndroidContext.init(...)` (`FogliettoApplication.onCreate`); the
 * holder throws its loud `IllegalStateException` otherwise instead of resolving a wrong path.
 *
 * Spec refs: §5.1.b, FR-06, NFR-06, NFR-09, NFR-10.
 */
fun createAndroidRecoveryRepository(): RecoveryRepository =
    createAndroidRecoveryRepository(clock = { Date() })

/**
 * Clock-injected overload used by the Robolectric suite to pin filenames deterministically.
 *
 * `internal` — not part of the API the app module can see; it exists purely as a test seam.
 */
internal fun createAndroidRecoveryRepository(clock: () -> Date): RecoveryRepository =
    FileRecoveryRepository(
        fileSystem = FileSystem.SYSTEM,
        recoveryDir = recoveryBaseDir(),
        now = { utcRecoveryInstant(clock()) },
    )

/**
 * Decomposes [date] into the seven UTC calendar fields of [RecoveryInstant].
 *
 * Pure field extraction from [Calendar]; `Calendar.MONTH` is 0-based so it is shifted by one.
 * No epoch values and no millisecond scaling are involved (NFR-06).
 */
internal fun utcRecoveryInstant(date: Date): RecoveryInstant {
    val calendar = Calendar.getInstance(utcTimeZone).apply { time = date }
    return RecoveryInstant(
        year = calendar.get(Calendar.YEAR),
        month = calendar.get(Calendar.MONTH) + 1,
        day = calendar.get(Calendar.DAY_OF_MONTH),
        hour = calendar.get(Calendar.HOUR_OF_DAY),
        minute = calendar.get(Calendar.MINUTE),
        second = calendar.get(Calendar.SECOND),
        millis = calendar.get(Calendar.MILLISECOND),
    )
}
