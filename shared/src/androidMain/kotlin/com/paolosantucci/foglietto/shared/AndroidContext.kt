package com.paolosantucci.foglietto.shared

import android.content.Context

/**
 * Process-wide application-context holder for the `shared` Android target.
 *
 * `shared` is domain + data only and its one platform seam — `recoveryBaseDir()` — is
 * `Context`-free, so the Android `actual` needs a way to reach `filesDir` without
 * changing the shared signature. This holder is that seam: the composition root
 * (`FogliettoApplication.onCreate`) calls [init] with the `Application` before any
 * shared factory is constructed, and the `androidMain` actual reads
 * [applicationContext] afterwards.
 *
 * ## Contract
 *
 * - [init] is **idempotent**: a second call neither throws nor replaces the stored
 *   application context with a non-application one (an `Activity` passed by mistake
 *   still resolves to the process `Application`).
 * - Reading [applicationContext] before [init] throws an [IllegalStateException]
 *   whose message names [init] and the `Application.onCreate` ordering, so the
 *   failure is loud and descriptive at process start instead of a mid-session crash.
 *
 * ## Purity
 *
 * This type holds no display strings and imports nothing from `androidx.compose.*`
 * or any UI toolkit — `shared/src/androidMain` stays presentation-free (NFR-07).
 *
 * Spec refs: FR-04, FR-05, EC-06, §5.1.b.
 */
object AndroidContext {

    private val lock = Any()

    private var storedApplicationContext: Context? = null

    /**
     * Stores [context]'s application context. Call from `Application.onCreate` before
     * constructing any shared factory.
     *
     * Idempotent: once an application context is stored, later calls are no-ops (they
     * never replace it with an `Activity` or another wrapper's context).
     */
    fun init(context: Context) {
        synchronized(lock) {
            if (storedApplicationContext == null) {
                storedApplicationContext = context.applicationContext ?: context
            }
        }
    }

    /**
     * The process `Application` context.
     *
     * @throws IllegalStateException when read before [init].
     */
    val applicationContext: Context
        get() = synchronized(lock) { storedApplicationContext }
            ?: throw IllegalStateException(
                "AndroidContext not initialised — call AndroidContext.init(applicationContext) " +
                    "in Application.onCreate before constructing shared factories"
            )

    /**
     * Test-only reset used by the Robolectric suites: the holder is process-scoped
     * state and the JVM reuses one class loader per sdk sandbox, so tests must be able
     * to start from the uninitialised state. Not part of the production contract.
     */
    internal fun resetForTesting() {
        synchronized(lock) { storedApplicationContext = null }
    }
}
