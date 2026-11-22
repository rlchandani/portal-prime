/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.SettingsPresentation
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.settings.effectiveScope

/** Lazy-list item kinds; each is its own `contentType` so Compose can reuse row compositions. */
internal enum class SettingsEntryKind { LEDE, HEADER, ROWS, BLOCK, LINK, EXTERNAL, FOOTER, NOTICE, ADVANCED_HINT, GAP }

internal data class SettingsPageEntry(
    val key: String,
    val kind: SettingsEntryKind,
    val item: SettingsItem? = null,
    val text: String? = null,
    val advanced: Boolean = false,
    /** Rounded top / bottom of the section's card, for rows that share one card. */
    val first: Boolean = false,
    val last: Boolean = false,
    /** A block sharing its section with other cards: the [SettingsSpacing.CARD_GAP_DP] it keeps above / below. */
    val gapBefore: Boolean = false,
    val gapAfter: Boolean = false,
)

internal data class SettingsPageLayout(
    val entries: List<SettingsPageEntry>,
    /** Row id (`domain.key`, native id, `link.x`, `ext.x`) → index of the entry that draws it. */
    val controlIndex: Map<String, Int>,
)

/** The page-wide sync note under the lede; drawn as its own line, not a section footer. */
internal const val SETTINGS_SYNC_NOTE_KEY = "note:sync"
private const val SCREENSAVER_DOMAIN = "screensaver"
private const val SCREENSAVER_MASTER = "enabled"
internal const val SCREENSAVER_OFF_NOTICE =
    "Turn on the photo frame screensaver in Photo Frame to use the settings that belong to it here."
internal const val FRAME_OFF_NOTICE = "Turn it on to choose the style, photos and clock of your photo frame."

/** Registry rows of the screensaver domain only show while the screensaver is on (as before). */
private fun SettingsItem.needsScreensaver(): Boolean =
    (this is SettingsDomainRows && domain == SCREENSAVER_DOMAIN && keys.any { it != SCREENSAVER_MASTER }) ||
        (this is SettingsBlockItem && block == SettingsBlock.SCREENSAVER_PREVIEW)

private fun SettingsItem.hasAdvancedRows(): Boolean = when (this) {
    is SettingsDomainRows -> keys.any { SettingsPresentation.isAdvanced(domain, it) }
    is SettingsLinkItem -> SettingsTree.needsAdvanced(target)
    else -> false
}

private fun SettingsItem.controlIds(): List<String> = when (this) {
    is SettingsDomainRows -> ids()
    is SettingsBlockItem -> block.rows.map { it.id } + settingsClaimedSpecs(block).map { it.first }
    is SettingsLinkItem, is SettingsExternalItem -> listOf(key)
}

private fun SettingsItem.kind(): SettingsEntryKind = when (this) {
    is SettingsDomainRows -> SettingsEntryKind.ROWS
    is SettingsBlockItem -> SettingsEntryKind.BLOCK
    is SettingsLinkItem -> SettingsEntryKind.LINK
    is SettingsExternalItem -> SettingsEntryKind.EXTERNAL
}

/**
 * Flattens a page into lazy-list entries. Pure: no Context, no persistence. Hidden content
 * (Advanced while Advanced is off, screensaver rows while the screensaver is off) is left out,
 * and a single hint or notice explains it instead.
 */
internal fun settingsPageLayout(page: String, advanced: Boolean, screensaverOn: Boolean,
                                syncNote: String? = null,
                                /** Keys of row items with no row to draw right now (every spec hidden by its own gate). */
                                emptyRows: Set<String> = emptySet()): SettingsPageLayout {
    val entries = ArrayList<SettingsPageEntry>()
    val index = LinkedHashMap<String, Int>()
    var hiddenAdvanced = false
    var hiddenScreensaver = false
    val def = SettingsTree.page(page)
    if (def?.lede != null && def.parent == null) {
        entries += SettingsPageEntry("lede", SettingsEntryKind.LEDE, text = def.lede)
    }
    if (syncNote != null && settingsPageHasHouseholdRows(page)) {
        entries += SettingsPageEntry(SETTINGS_SYNC_NOTE_KEY, SettingsEntryKind.FOOTER, text = syncNote)
    }
    SettingsPageSections.of(page).forEachIndexed { sectionIndex, section ->
        if (section.advanced && !advanced) { hiddenAdvanced = true; return@forEachIndexed }
        val shown = section.items.filter { item ->
            if (!advanced && item.hasAdvancedRows()) hiddenAdvanced = true
            !(item.needsScreensaver() && !screensaverOn)
        }
        // A section whose rows are all gated off shows nothing, not an empty header and footer.
        val items = shown.filter { it !is SettingsDomainRows || it.key !in emptyRows }
        if (shown.size < section.items.size && !hiddenScreensaver) {
            // One notice where the first hidden screensaver content would have been.
            hiddenScreensaver = true
            entries += SettingsPageEntry("notice:screensaver", SettingsEntryKind.NOTICE,
                text = if (page == SettingsPageIds.FRAME) FRAME_OFF_NOTICE else SCREENSAVER_OFF_NOTICE)
        }
        if (items.isEmpty()) return@forEachIndexed
        section.header?.let {
            entries += SettingsPageEntry("header:$sectionIndex", SettingsEntryKind.HEADER, text = it,
                advanced = section.advanced)
        }
        val groups = settingsCardGroups(items)
        groups.forEachIndexed { groupIndex, group ->
            val block = group.first() is SettingsBlockItem
            // Blocks own the gaps around them, so a block that draws nothing leaves no stray space.
            val gapBefore = block && groupIndex > 0
            val gapAfter = block && groupIndex < groups.lastIndex && groups[groupIndex + 1].first() !is SettingsBlockItem
            group.forEachIndexed { itemIndex, item ->
                item.controlIds().forEach { id -> index.putIfAbsent(id, entries.size) }
                entries += SettingsPageEntry(item.key, item.kind(), item, advanced = section.advanced,
                    first = itemIndex == 0, last = itemIndex == group.lastIndex,
                    gapBefore = gapBefore, gapAfter = gapAfter)
            }
        }
        section.footer?.let { entries += SettingsPageEntry("footer:$sectionIndex", SettingsEntryKind.FOOTER, text = it) }
        entries += SettingsPageEntry("gap:$sectionIndex", SettingsEntryKind.GAP)
    }
    if (hiddenAdvanced) entries += SettingsPageEntry("hint:advanced", SettingsEntryKind.ADVANCED_HINT)
    return SettingsPageLayout(entries, index)
}

/**
 * A section's items split into cards: consecutive rows and links share one card, and every bespoke
 * block draws its own card(s). Without the split, rows above a block lost their rounded bottom and
 * touched the block's card with no gap (Calls › Messages, Drop-ins, Phones).
 */
internal fun settingsCardGroups(items: List<SettingsItem>): List<List<SettingsItem>> {
    val groups = ArrayList<List<SettingsItem>>()
    var run = ArrayList<SettingsItem>()
    for (item in items) {
        if (item is SettingsBlockItem) {
            if (run.isNotEmpty()) { groups += run; run = ArrayList() }
            groups += listOf(item)
        } else run += item
    }
    if (run.isNotEmpty()) groups += run
    return groups
}

/** The page that draws [control] (any row id), ignoring Advanced and screensaver visibility. */
internal fun settingsControlHome(control: String): String? = SettingsTree.pages.firstOrNull { page ->
    settingsPageLayout(page.id, advanced = true, screensaverOn = true).controlIndex.containsKey(control)
}?.id

/** Whether [page] draws any registry row that syncs across the household's Portals. */
internal fun settingsPageHasHouseholdRows(page: String): Boolean =
    SettingsPageSections.of(page).flatMap { it.items }.any { item ->
        when (item) {
            is SettingsDomainRows -> {
                val specs = SettingsRegistry.domain(item.domain)?.specs.orEmpty()
                item.keys.any { key -> specs.firstOrNull { it.key == key }?.effectiveScope?.synced == true }
            }
            is SettingsBlockItem -> settingsClaimedSpecs(item.block).any { it.second.effectiveScope.synced }
            else -> false
        }
    }

/** Domains whose registry rows a page draws through the shared renderer. */
internal fun settingsPageDomains(page: String): List<String> =
    SettingsPageSections.of(page).flatMap { it.items }.filterIsInstance<SettingsDomainRows>()
        .map { it.domain }.distinct()
