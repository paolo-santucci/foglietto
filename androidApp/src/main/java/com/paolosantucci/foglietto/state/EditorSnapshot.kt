package com.paolosantucci.foglietto.state

/**
 * One restorable editor state: the buffer text plus the selection anchors (FR-31, OQ-01 assumed).
 *
 * [selStart]/[selExtent] are absolute UTF-16 offsets into [text] — the same unit Kotlin's `String`
 * and Compose `TextFieldValue` use, so they map by identity. The anchors are stored **unordered**:
 * `selStart > selExtent` (a reversed selection) is legal and preserved verbatim, mirroring the
 * shared `IndentResult` contract.
 */
data class EditorSnapshot(
    val text: String,
    val selStart: Int,
    val selExtent: Int,
)
