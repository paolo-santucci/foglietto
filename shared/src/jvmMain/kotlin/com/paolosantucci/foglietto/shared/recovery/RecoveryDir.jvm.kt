package com.paolosantucci.foglietto.shared.recovery

import okio.Path
import okio.Path.Companion.toPath

/**
 * JVM `actual` for [recoveryBaseDir].
 *
 * ## Purpose
 *
 * This implementation is NON-PRODUCTION. It exists solely so that the `commonMain`
 * `expect fun recoveryBaseDir()` compiles on the Linux `jvm()` target (the only
 * registered target on Linux CI, where Apple targets are host-guarded).
 *
 * Without this file, `./gradlew compileKotlinJvm` fails with:
 *   "expected declaration has no actual for member `recoveryBaseDir`"
 *
 * ## Usage
 *
 * `jvmTest` (i.e. `FileRecoveryRepositoryTest` in TASK-04) injects a temp [Path]
 * directly into the `FileRecoveryRepository` constructor rather than calling this
 * function — so the value returned here is never exercised in tests.
 *
 * The production code path (iOS) always uses the `iosMain` actual, which returns
 * the NSFileManager Documents directory as an `okio.Path`.
 *
 * Spec refs: §5.1.1, §4.1; plan TASK-01 CRITICAL KMP MECHANICS note; assessment R-A3.
 */
actual fun recoveryBaseDir(): Path =
    (System.getProperty("java.io.tmpdir") + "/foglietto-recovery").toPath()
