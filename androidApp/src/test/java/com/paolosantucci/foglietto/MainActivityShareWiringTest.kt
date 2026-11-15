package com.paolosantucci.foglietto

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier A (plain JUnit — no Robolectric, the Activity is never constructed) source-level contract
 * for the inbound share forwarder in `MainActivity.kt` — plan TASK-08, spec FR-22, FR-09, FR-21,
 * EC-23; §5.1.8 activity contract and §7.1 lines 801-804.
 *
 * Why a source scan instead of a Robolectric host test: the Activity's whole share contract is
 * *structural* — which intent is read, where the read sits, and what the Activity is not allowed
 * to name — and none of it is observable behaviour that constructing the Activity would prove.
 * The scan reads the authored file, strips comments so prose cannot satisfy (or trip) a rule, and
 * every rule is paired with a failure fixture built from a mutated copy of the same text, so a
 * predicate that reports nothing on a broken Activity cannot pass audibly.
 *
 * The two silent failure modes this suite exists for:
 *
 *  - a launch forward outside the `savedInstanceState == null` guard re-seeds the buffer on every
 *    configuration change and manufactures a collision save of text the user never replaced
 *    (EC-23) — the seed looks correct in every behavioural test and is still wrong;
 *  - a lifecycle/save/graph symbol in the Activity moves the save and reset seams off the process
 *    owner (FR-14, the `m8` check-14 pin), which is exactly the wiring that is missing on a
 *    rotation and therefore invisible until a real device drops a note.
 */
class MainActivityShareWiringTest {

    // ── The contract ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the activity satisfies every inbound wiring rule`() {
        assertEquals(
            "MainActivity must satisfy the whole inbound contract (FR-22, FR-09, EC-23).",
            emptyList<String>(),
            violations(source),
        )
    }

    @Test
    fun `the single reader call lives inside the forwarder`() {
        assertEquals(
            "Inbound extraction must happen once, in one place, through the shared reader.",
            emptyList<String>(),
            readerViolations(stripComments(source)),
        )
    }

    @Test
    fun `the launch forward is guarded by the saved instance state`() {
        assertEquals(
            "A configuration change must be unable to re-seed the already-seeded payload (EC-23).",
            emptyList<String>(),
            launchGuardViolations(stripComments(source)),
        )
    }

    @Test
    fun `onNewIntent orders super then setIntent then forward`() {
        assertEquals(
            "onNewIntent must call super.onNewIntent, then setIntent, then the forwarder (§5.1.8).",
            emptyList<String>(),
            newIntentViolations(stripComments(source)),
        )
    }

    @Test
    fun `the forwarder checks the payload before seeding the buffer`() {
        assertEquals(
            "A null payload must never reach the graph as an empty-buffer edit (NFR-03).",
            emptyList<String>(),
            seedGuardViolations(stripComments(source)),
        )
    }

    @Test
    fun `the activity names no lifecycle save graph or logging symbol`() {
        assertEquals(
            "The Activity owns no lifecycle observation, no save/reset, no graph construction and " +
                "no logging (FR-14, §5.1 conformance rule 9).",
            emptyList<String>(),
            forbiddenViolations(source),
        )
    }

    // ── Failure fixtures: mutated copies of the same source ──────────────────────────────────

    @Test
    fun `a removed guard is reported against the guard`() {
        val mutated = source.replace(GUARD, "")
        assertNotEquals("The fixture must actually mutate the source.", source, mutated)
        assertFalse("The fixture must remove the guard.", mutated.contains(GUARD))

        val reported = launchGuardViolations(stripComments(mutated))
        assertTrue(
            "Dropping the launch guard must be reported against `$GUARD`, got $reported",
            reported.any { it.contains(GUARD) },
        )
    }

    @Test
    fun `a moved launch forward is reported against the guard`() {
        val mutated = source.replace(
            SUPER_ON_CREATE,
            "$SUPER_ON_CREATE\n        $FORWARD_CALL$INTENT)",
        )
        assertNotEquals("The fixture must actually mutate the source.", source, mutated)

        val text = stripComments(mutated)
        val create = requireNotNull(bodyRange(text, ON_CREATE)) {
            "onCreate must survive the fixture."
        }
        assertEquals(
            "The fixture must leave two forwards in onCreate (one guarded, one not).",
            2,
            occurrences(text, FORWARD_CALL, create).size,
        )

        val reported = launchGuardViolations(text)
        assertTrue(
            "An unguarded forward in onCreate must be reported against `$GUARD`, got $reported",
            reported.any { it.contains(GUARD) },
        )
    }

    @Test
    fun `a forbidden lifecycle call is reported by name`() {
        val forbiddenCall = "container.onProcessStarted()"
        val mutated = source.replace(EDGE_TO_EDGE, "$forbiddenCall\n        $EDGE_TO_EDGE")
        assertNotEquals("The fixture must actually mutate the source.", source, mutated)
        assertTrue("The fixture must add the forbidden call.", mutated.contains(forbiddenCall))

        val reported = forbiddenViolations(mutated)
        assertTrue(
            "A lifecycle call in the Activity must be reported by name, got $reported",
            reported.any { it.contains(FORBIDDEN_LIFECYCLE_TOKEN) },
        )
    }

    // ── Rules ───────────────────────────────────────────────────────────────────────────────

    /** Every rule, so the master assertion cannot pass with one of them silently unwired. */
    private fun violations(source: String): List<String> {
        val text = stripComments(source)
        return readerViolations(text) +
            launchGuardViolations(text) +
            newIntentViolations(text) +
            seedGuardViolations(text) +
            forbiddenViolations(source)
    }

    /** Exactly one reader call site, and it lives inside the forwarder (FR-22, D5). */
    private fun readerViolations(text: String): List<String> {
        val problems = mutableListOf<String>()
        val calls = occurrences(text, READER)
        if (calls.size != 1) {
            problems += "expected exactly one `$READER` call site, found ${calls.size}"
        }

        val forward = bodyRange(text, ON_FORWARD)
        if (forward == null) {
            problems += "MainActivity declares no `$FORWARD_NAME`: the inbound payload has no home"
        } else if (calls.isNotEmpty() && calls.none { it in forward }) {
            problems += "the single `$READER` call site must live inside `$FORWARD_NAME`, " +
                "not elsewhere in the Activity"
        }
        return problems
    }

    /** The launch forward exists, and every forward in onCreate sits inside the null-state guard. */
    private fun launchGuardViolations(text: String): List<String> {
        val problems = mutableListOf<String>()
        val create = bodyRange(text, ON_CREATE)
        if (create == null) {
            problems += "MainActivity declares no `fun onCreate(`"
            return problems
        }

        val guardAt = text.indexOf(GUARD, create.first).takeIf { it >= 0 && it in create } ?: -1
        if (guardAt < 0) {
            problems += "onCreate must guard the launch forward with `$GUARD` (EC-23 — a " +
                "configuration change must not re-seed the buffer)"
        }

        val guardBlock = if (guardAt < 0) {
            null
        } else {
            val open = text.indexOf('{', guardAt).takeIf { it >= 0 && it in create } ?: -1
            val close = if (open < 0) -1 else blockEnd(text, open).takeIf { it in create } ?: -1
            if (open < 0 || close < 0) null else open..close
        }

        val forwards = occurrences(text, FORWARD_CALL, create)
        if (forwards.isEmpty()) {
            problems += "onCreate never forwards the launch intent — a cold-start share is dropped"
        }

        forwards.filter { guardBlock == null || it !in guardBlock }.forEach { _ ->
            problems += "`$FORWARD_NAME` is reached from onCreate outside the `$GUARD` guard " +
                "(EC-23 — a configuration change must not re-seed)"
        }
        return problems
    }

    /** onNewIntent body order: super, then setIntent, then the forwarder (§5.1.8). */
    private fun newIntentViolations(text: String): List<String> {
        val newIntent = bodyRange(text, ON_NEW_INTENT)
            ?: return listOf("MainActivity declares no `fun onNewIntent(` — a warm share is dropped")

        val body = text.substring(newIntent)
        val superCall = body.indexOf(SUPER_ON_NEW_INTENT)
        val setIntent = body.indexOf(SET_INTENT)
        val forward = body.indexOf(FORWARD_CALL)
        if (superCall < 0 || setIntent < 0 || forward < 0) {
            return listOf(
                "onNewIntent must call `$SUPER_ON_NEW_INTENT`, `$SET_INTENT` and `$FORWARD_CALL` " +
                    "(found super=$superCall, setIntent=$setIntent, forward=$forward)",
            )
        }
        if (!(superCall < setIntent && setIntent < forward)) {
            return listOf(
                "onNewIntent must run `$SUPER_ON_NEW_INTENT` → `$SET_INTENT` → `$FORWARD_CALL` " +
                    "in that order, got offsets $superCall/$setIntent/$forward",
            )
        }
        return emptyList()
    }

    /** The forwarder checks `raw != null` before handing anything to the graph (NFR-03). */
    private fun seedGuardViolations(text: String): List<String> {
        val forward = bodyRange(text, ON_FORWARD)
            ?: return listOf("MainActivity declares no `$FORWARD_NAME`")

        val body = text.substring(forward)
        val nullGuard = body.indexOf(RAW_NOT_NULL)
        val seed = body.indexOf(ACCEPT_INBOUND)
        val problems = mutableListOf<String>()
        if (nullGuard < 0) {
            problems += "`$FORWARD_NAME` must guard on `$RAW_NOT_NULL` before seeding the buffer"
        }
        if (seed < 0) {
            problems += "`$FORWARD_NAME` never hands the payload to the graph (`$ACCEPT_INBOUND` missing)"
        }
        if (nullGuard >= 0 && seed >= 0 && nullGuard > seed) {
            problems += "`$FORWARD_NAME` must check `$RAW_NOT_NULL` before `$ACCEPT_INBOUND`"
        }
        return problems
    }

    /** The Activity names no lifecycle, save, graph-construction or logging symbol (FR-14). */
    private fun forbiddenViolations(source: String): List<String> =
        FORBIDDEN.filter { source.contains(it) }.map { token ->
            "MainActivity must not name `$token` — the composition root owns the lifecycle, the " +
                "save/reset seams and the graph, and nothing here may log (FR-14, EC-23)"
        }

    // ── Source plumbing ─────────────────────────────────────────────────────────────────────

    /** The authored Activity source, resolved from either the module dir or the repo root. */
    private val source: String by lazy {
        val file = listOf(
            File("src/main/java/com/paolosantucci/foglietto/$ACTIVITY"),
            File("androidApp/src/main/java/com/paolosantucci/foglietto/$ACTIVITY"),
        ).firstOrNull { it.isFile }
            ?: error("No $ACTIVITY from ${File(".").absolutePath}")

        file.readText().also { text ->
            assertTrue("$ACTIVITY must not be empty.", text.isNotBlank())
            assertTrue(
                "$ACTIVITY must still host the Compose root (TASK-08 changes nothing there).",
                text.contains("setContent {"),
            )
        }
    }

    /** Source with block and line comments removed, so the rules pin code, not prose. */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, " ")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** Every index of [needle] in [haystack], optionally restricted to [range]. */
    private fun occurrences(haystack: String, needle: String, range: IntRange? = null): List<Int> {
        val bounds = range ?: 0..haystack.lastIndex
        val hits = mutableListOf<Int>()
        var at = haystack.indexOf(needle, bounds.first.coerceAtLeast(0))
        while (at >= 0 && at <= bounds.last) {
            hits += at
            at = haystack.indexOf(needle, at + 1)
        }
        return hits
    }

    /** The `open..close` range of the brace block starting at [openIndex], or `null`. */
    private fun bodyRange(text: String, marker: String): IntRange? {
        val start = text.indexOf(marker)
        if (start < 0) return null
        val open = text.indexOf('{', start)
        if (open < 0) return null
        val close = blockEnd(text, open)
        return if (close < 0) null else open..close
    }

    /** Index of the brace closing the block opened at [openIndex]; no literal in scope holds a brace. */
    private fun blockEnd(text: String, openIndex: Int): Int {
        var depth = 0
        for (index in openIndex until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private companion object {
        const val ACTIVITY = "MainActivity.kt"

        // The forwarder and the shared reader it must use (TASK-02).
        const val ON_CREATE = "fun onCreate("
        const val ON_NEW_INTENT = "fun onNewIntent("
        const val FORWARD_NAME = "forwardInbound"
        const val ON_FORWARD = "fun forwardInbound("
        const val FORWARD_CALL = "forwardInbound("
        const val READER = "ShareIntentReader.read("
        const val ACCEPT_INBOUND = "container.acceptInboundText("
        const val RAW_NOT_NULL = "raw != null"

        // The Activity API the order rule pins (§5.1.8).
        const val INTENT = "intent"
        const val SUPER_ON_CREATE = "super.onCreate(savedInstanceState)"
        const val SUPER_ON_NEW_INTENT = "super.onNewIntent"
        const val SET_INTENT = "setIntent("
        const val EDGE_TO_EDGE = "enableEdgeToEdge()"

        // The EC-23 guard.
        const val GUARD = "savedInstanceState == null"

        /** A token long enough to name the rule the fixture breaks. */
        const val FORBIDDEN_LIFECYCLE_TOKEN = "onProcessStart"

        val FORBIDDEN = listOf(
            "ProcessLifecycleOwner",
            "LifecycleSaveCoordinator",
            "onProcessStart",
            "onProcessStop",
            "onSaveInstanceState",
            "createAndroid",
            "buildAppContainer",
            "AndroidContext",
            "Log.",
            "println",
        )

        val BLOCK_COMMENT = Regex("""(?s)/\*.*?\*/""")
    }
}
