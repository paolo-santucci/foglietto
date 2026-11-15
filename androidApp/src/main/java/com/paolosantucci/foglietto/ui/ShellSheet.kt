package com.paolosantucci.foglietto.ui

/**
 * The one modal overlay the shell can have open, or `null` for none (FR-21, FR-34).
 *
 * This file has now shed both of its earlier shapes. It used to declare `OverflowItems` — the
 * two-entry `DropdownMenu` the ⋮ rendered — and then a two-value enum for "menu sheet vs About
 * sheet". The ⋮ is no longer a sheet at all: it opens an anchored popover owned by the top bar
 * ([com.paolosantucci.foglietto.ui.chrome.TopActionBar]), so the only overlay left is the About
 * sheet. What remains here is the overlay's *identity*, not a routing table: the shell mounts the
 * body named by the enum while it is non-null, which keeps "which overlay is open" one explicit
 * value instead of a pile of booleans.
 *
 * Nothing here holds display copy: the labels live in `res/values/strings.xml` +
 * `values-it/strings.xml` (NFR-06), which `StringInventoryTest` pins.
 *
 * Tier A: no Compose, no `Context` — the contract is a plain enum, pinned by [ShellSheetTest].
 */
enum class ShellSheet {

    /** The About sheet, reached from the ⋮ popover's About row (FR-34). */
    About,
}
