package com.paolosantucci.foglietto

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The single composition root: builds the process graph once and bridges the **process** lifecycle
 * to it.
 *
 * ## Why the process lifecycle, not the Activity lifecycle
 *
 * `ProcessLifecycleOwner` reports only when the whole process enters/leaves the foreground. A
 * configuration change (rotation, uiMode / dark-mode toggle) destroys and recreates the Activity
 * while the process stays STARTED, so it emits nothing: no save and no reset happen, and the
 * app-scoped buffer with its caret, selection and history survives untouched (FR-14, EC-12,
 * EC-13). A second visible Activity (split-screen) likewise keeps the process STARTED, so no
 * ON_STOP fires (EC-24). `MainActivity` therefore owns neither seam.
 *
 * ## Ordering
 *
 * [onCreate] initialises the shared platform seam first, then builds the graph, then registers the
 * observer — a save can never run against a half-built graph, and the buffer's cold-start ON_START
 * (an ON_START on an already-empty buffer, a no-op — EC-15) lands after the seams exist.
 *
 * ## Deliberate absences
 *
 * No buffer, caret, selection or history is written to any store or to `savedInstanceState`: the
 * buffer is ephemeral, and the ON_STOP save is the only persistence path (FR-10). The executor is
 * process-scoped and never shut down (it dies with the process).
 */
class FogliettoApplication : Application() {

    /**
     * The process-scoped graph. Built exactly once, in [onCreate], before anything can read it —
     * the Activity host and the Compose shell take every singleton from here.
     */
    lateinit var container: AppContainer
        private set

    private val processRegistration = ProcessLifecycleRegistration()

    override fun onCreate() {
        super.onCreate()
        container = buildAppContainer(this)
        attachProcessLifecycle(ProcessLifecycleOwner.get().lifecycle)
    }

    /**
     * Registers the process-lifecycle observer on [lifecycle], at most once per process.
     *
     * @return `true` when this call registered the observer, `false` when one was already
     *   registered. Exposed to the module's tests only; production registers exactly once from
     *   [onCreate] and there is no other call site.
     */
    internal fun attachProcessLifecycle(lifecycle: Lifecycle): Boolean =
        processRegistration.attach(container, lifecycle)
}

/**
 * The one-shot registration of the process-lifecycle observer.
 *
 * Kept separate from [FogliettoApplication] so the "registered exactly once" contract is a plain,
 * testable unit: the flag flips **before** `addObserver`, so a second attach is refused rather than
 * adding a duplicate observer (which would double every save/reset pair).
 */
internal class ProcessLifecycleRegistration {

    private var registered = false

    /** @return `true` when this call registered [container]'s observer on [lifecycle]. */
    fun attach(container: AppContainer, lifecycle: Lifecycle): Boolean {
        if (registered) return false
        registered = true
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    container.onProcessStarted()
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.onProcessStopped()
                }
            },
        )
        return true
    }
}
