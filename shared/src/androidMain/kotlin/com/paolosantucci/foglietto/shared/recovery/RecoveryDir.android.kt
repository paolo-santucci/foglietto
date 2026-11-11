package com.paolosantucci.foglietto.shared.recovery

import com.paolosantucci.foglietto.shared.AndroidContext
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * Android `actual` for [recoveryBaseDir].
 *
 * Resolves `<filesDir>/recovery` through [AndroidContext] — app-private internal
 * storage, never external/shared storage (NFR-09). It shares **nothing** with the
 * non-production `jvmMain` actual (`java.io.tmpdir/foglietto-recovery`): the KMP
 * default hierarchy isolates `jvmMain` from `androidMain`, and this file references
 * no `java.io.tmpdir` value (FR-05).
 *
 * Called from the Android factories (constructed in `FogliettoApplication.onCreate`
 * after `AndroidContext.init`); reading it before init throws the holder's loud
 * `IllegalStateException` rather than silently resolving a wrong directory.
 *
 * Spec refs: FR-05, NFR-09, §5.1.b.
 */
actual fun recoveryBaseDir(): Path =
    AndroidContext.applicationContext.filesDir.toOkioPath() / "recovery"
