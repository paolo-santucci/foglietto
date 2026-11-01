package com.paolosantucci.foglietto.shared.recovery

import okio.Path

/**
 * Platform-specific base directory for recovery files.
 *
 * This is the ONLY `expect/actual` seam in the `shared` module.
 *
 * ## Implementations
 *
 *   - `iosMain` — `NSFileManager.defaultManager.URLsForDirectory(.documentDirectory, …)`
 *     converted to an `okio.Path` via `.toPath()`. Files placed here are visible in the
 *     iOS Files app (UIFileSharingEnabled / LSSupportsOpeningDocumentsInPlace, set in
 *     `iosApp/`). Compiled only on a macOS host (host-guarded Apple targets in
 *     `shared/build.gradle.kts`).
 *
 *   - `jvmMain` — minimal non-production glue: `System.getProperty("java.io.tmpdir")`
 *     extended with a `"foglietto-recovery"` subdirectory. Exists ONLY so the
 *     `commonMain expect` compiles on the Linux `jvm()` target. Not production-ready;
 *     the JVM target is used solely for `./gradlew check` on Linux CI.
 *
 * ## JVM `actual` rationale (CRITICAL KMP MECHANICS)
 *
 * A `commonMain expect` MUST have an `actual` in every REGISTERED target. On Linux
 * only `jvm()` is registered (Apple targets are host-guarded). Without the `jvmMain`
 * actual, `./gradlew compileKotlinJvm` fails with:
 *   "expected declaration has no actual for member …"
 * The `jvmMain` actual satisfies the compiler; `jvmTest` injects a temp `Path` directly
 * (see `FileRecoveryRepositoryTest`) so the non-production value is never exercised in tests.
 *
 * Spec refs: §5.1.1, §4.1, §4.3; assessment R-A3; plan TASK-01 CRITICAL KMP MECHANICS note.
 */
expect fun recoveryBaseDir(): Path
