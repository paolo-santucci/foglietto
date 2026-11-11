package com.paolosantucci.foglietto.res

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Tier A string-resource inventory — plain JVM XML parse, no Robolectric (FR-36, NFR-06, OQ-21).
 *
 * Pins the 17-key copy contract shared by the English default file and the Italian
 * `values-it` translation:
 *  - both locales expose the identical key set (a key missing from one locale is a
 *    user-visible regression on that locale);
 *  - the set is *exactly* the 17 keys enumerated in the plan (0 missing, 0 extra), so an
 *    accidentally re-added dropped key (copy/paste/hide-keyboard) or an `app_name` sneaking
 *    in is caught here rather than in review;
 *  - no value is empty or whitespace-only — an empty translation renders as a blank button;
 *  - `restore_note_label` keeps both positional placeholders in *both* locales, because the
 *    recovery row passes preview + saved-at subtitle through it.
 *
 * The working directory of this test is the `androidApp/` module, but the repo root is also
 * accepted so the test survives a different Gradle invocation layout.
 */
class StringInventoryTest {

    private val defaultLocale: Map<String, String> by lazy { readStrings("values") }
    private val italianLocale: Map<String, String> by lazy { readStrings("values-it") }

    @Test
    fun `both locales expose the identical key set`() {
        assertEquals(
            "values/strings.xml and values-it/strings.xml must declare the same keys; " +
                "missing in values-it = ${PINNED_KEYS - italianLocale.keys}, " +
                "missing in values = ${PINNED_KEYS - defaultLocale.keys}, " +
                "only in values-it = ${italianLocale.keys - defaultLocale.keys}, " +
                "only in values = ${defaultLocale.keys - italianLocale.keys}",
            defaultLocale.keys,
            italianLocale.keys,
        )
    }

    @Test
    fun `the key set is exactly the pinned 17`() {
        assertEquals(
            "The default locale must declare exactly the pinned 17 keys; " +
                "missing = ${PINNED_KEYS - defaultLocale.keys}, " +
                "extra = ${defaultLocale.keys - PINNED_KEYS}",
            PINNED_KEYS,
            defaultLocale.keys,
        )
        assertEquals(
            "The Italian locale must declare exactly the pinned 17 keys; " +
                "missing = ${PINNED_KEYS - italianLocale.keys}, " +
                "extra = ${italianLocale.keys - PINNED_KEYS}",
            PINNED_KEYS,
            italianLocale.keys,
        )
    }

    @Test
    fun `no value is empty and the restore label keeps both placeholders`() {
        for ((locale, values) in mapOf("values" to defaultLocale, "values-it" to italianLocale)) {
            values.forEach { (key, value) ->
                assertTrue(
                    "`$key` in $locale/strings.xml is empty or whitespace-only.",
                    value.trim().isNotEmpty(),
                )
            }

            val label = values.getValue("restore_note_label")
            assertTrue(
                "`restore_note_label` in $locale/strings.xml must keep the %1\$s placeholder: \"$label\"",
                label.contains("%1\$s"),
            )
            assertTrue(
                "`restore_note_label` in $locale/strings.xml must keep the %2\$s placeholder: \"$label\"",
                label.contains("%2\$s"),
            )
        }
    }

    private fun readStrings(directory: String): Map<String, String> {
        val file = listOf(
            File("src/main/res/$directory/strings.xml"),
            File("androidApp/src/main/res/$directory/strings.xml"),
        ).firstOrNull { it.isFile }
            ?: error("$directory/strings.xml not found from ${File(".").absolutePath}")

        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)

        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }

    private companion object {
        /** OQ-21, pinned literally: 15 retained iOS keys + the two Android-only keys. */
        val PINNED_KEYS: Set<String> = setOf(
            "clear_editor",
            "about",
            "undo",
            "redo",
            "indent",
            "outdent",
            "menu",
            "open_menu",
            "recent_notes",
            "no_recent_notes",
            "restore_note_label",
            "restore_this_note",
            "share",
            "share_text",
            "report_an_issue",
            "website",
            "editor_cleared",
        )
    }
}
