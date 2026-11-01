package com.paolosantucci.foglietto.shared.recovery

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSUserDomainMask

/**
 * iOS `actual` for [recoveryBaseDir].
 *
 * Returns the application's Documents directory as an `okio.Path`. Files placed here
 * are exposed to the iOS Files app via `UIFileSharingEnabled` and
 * `LSSupportsOpeningDocumentsInPlace` (both set in the `iosApp/` Info.plist by TASK-08).
 *
 * ## Runtime
 *
 * Uses Kotlin/Native `platform.Foundation` interop (available only in `iosMain` /
 * Apple targets). This file is compiled exclusively on a macOS host — the Apple targets
 * are host-guarded in `shared/build.gradle.kts` via `HostManager.hostIsMac`, so this
 * file is NEVER compiled on the Linux CI host.
 *
 * ## Implementation notes
 *
 * - `NSFileManager.defaultManager.URLsForDirectory` returns an `NSArray<NSURL>`;
 *   the first element's `path` property gives the absolute POSIX path string.
 * - `.toPath()` converts the POSIX string to an `okio.Path` via
 *   `okio.Path.Companion.toPath()`.
 * - The Documents directory always exists on a real iOS device; no need to create it.
 *
 * Spec refs: §5.1.1; LP §5.3 (UIFileSharingEnabled); assessment R-A3.
 */
actual fun recoveryBaseDir(): Path {
    val urls = NSFileManager.defaultManager.URLsForDirectory(
        NSDocumentDirectory,
        NSUserDomainMask
    )
    val documentsUrl = urls.first() as platform.Foundation.NSURL
    return requireNotNull(documentsUrl.path) {
        "NSFileManager Documents directory returned a null path"
    }.toPath()
}
