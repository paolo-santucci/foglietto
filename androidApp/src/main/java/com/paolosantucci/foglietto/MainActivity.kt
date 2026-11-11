package com.paolosantucci.foglietto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.paolosantucci.foglietto.ui.FogliettoApp
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/**
 * The single Android activity: a thin Compose host for the app shell ([FogliettoApp]).
 *
 * It owns exactly two things — edge-to-edge window layout and the Compose content root — plus the
 * read-only hand-off of the process graph it inherits from its application. It deliberately does
 * **not** own lifecycle save/reset and never builds the graph: buffer persistence is triggered by
 * the process-lifecycle observer in the composition root (`FogliettoApplication`), so activity
 * recreation and configuration changes can never double-save or wrongly clear the buffer (FR-14).
 *
 * Insets are consumed by the shell's components, not by manual padding here: the `Scaffold`'s top
 * bar, the editor and the self-insetting action row each own their band (FR-18).
 */
class MainActivity : ComponentActivity() {

    /**
     * The process-scoped graph, owned by the application and read here for the Compose tree.
     *
     * Every singleton the shell needs — editor, recent-notes holder, save coordinator (Clear path),
     * share seam, settings — comes from this one instance; nothing is re-created per Activity.
     */
    internal val container: AppContainer
        get() = (application as FogliettoApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge stays on; the shell hands the insets out to the components that own them.
        enableEdgeToEdge()
        setContent {
            FogliettoTheme {
                FogliettoApp(container = container)
            }
        }
    }
}
