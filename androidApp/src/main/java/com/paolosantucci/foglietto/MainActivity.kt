package com.paolosantucci.foglietto

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.paolosantucci.foglietto.state.ShareIntentReader
import com.paolosantucci.foglietto.ui.FogliettoApp
import com.paolosantucci.foglietto.ui.theme.FogliettoTheme

/**
 * The single Android activity: a thin Compose host for the app shell ([FogliettoApp]) and a
 * two-line forwarder for inbound share text.
 *
 * It owns exactly three things — edge-to-edge window layout, the Compose content root, and handing
 * a share payload from an intent to the process graph (FR-22). It deliberately does **not** own
 * lifecycle save/reset and never builds the graph: buffer persistence is triggered by the
 * process-lifecycle observer in the composition root (`FogliettoApplication`), so activity
 * recreation and configuration changes can never double-save or wrongly clear the buffer (FR-14).
 *
 * The launch forward is guarded by `savedInstanceState == null` on purpose (EC-23): a
 * configuration change re-delivers the launch intent, and re-seeding it would manufacture a
 * collision save of text the user never replaced. Whether the payload is applied now or stashed
 * until the next foreground return is the graph's decision, never this Activity's (FR-23).
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
        // EC-23: a re-created Activity must not re-seed the payload it already handed over.
        if (savedInstanceState == null) {
            forwardInbound(intent)
        }
        // Edge-to-edge stays on; the shell hands the insets out to the components that own them.
        enableEdgeToEdge()
        setContent {
            FogliettoTheme {
                FogliettoApp(container = container)
            }
        }
    }

    /**
     * A share arriving while the Activity is already alive (§5.1.8): record the new intent first —
     * `getIntent()` must agree with the payload that was just read — then forward it.
     *
     * The parameter is non-null because `ComponentActivity` declares the override that way; the
     * forwarder stays nullable-tolerant so a platform null cannot crash the hand-off.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardInbound(intent)
    }

    /**
     * Reads the inbound payload off [intent] and hands it to the graph (FR-22, FR-09).
     *
     * Extraction is the shared reader's job (one notion of "inbound text", D5) and acceptance is
     * the shared rule's, reached through the graph — so a blank payload is passed on verbatim and
     * rejected there, not here. A non-inbound intent (a plain launcher start) yields `null` and
     * touches nothing, so an ordinary cold start is not an edit.
     */
    private fun forwardInbound(intent: Intent?) {
        val raw = ShareIntentReader.read(intent?.action) { intent?.getCharSequenceExtra(it) }
        if (raw != null) container.acceptInboundText(raw)
    }
}
