package com.paolosantucci.foglietto.ui

/**
 * The one sheet the shell has open, or `null` for none (FR-21, FR-34).
 *
 * This file used to declare `OverflowItems` — the two-entry `DropdownMenu` the ⋮ rendered. The ⋮
 * is now a single bottom sheet (`MenuSheet`) whose first section *is* the recent-notes list, so
 * the only routing left is "which sheet is mounted", and it is this exhaustive enum. Nothing here
 * holds display copy: the labels live in `res/values/strings.xml` + `values-it/strings.xml`
 * (NFR-06), which `StringInventoryTest` pins.
 *
 * Tier A: no Compose, no `Context` — the contract is a plain enum, pinned by [ShellSheetTest].
 */
enum class ShellSheet {

    /** The ⋮ sheet: recent notes inline, then the About row (FR-26, FR-34). */
    Menu,

    /** The About sheet, reached from the menu's About row (FR-34). */
    About,
}
