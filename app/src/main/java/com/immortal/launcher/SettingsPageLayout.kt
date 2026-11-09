/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.SettingsPresentation

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
)

internal data class SettingsPageLayout(
    val entries: List<SettingsPageEntry>,
    /** Row id (`domain.key`, native id, `link.x`, `ext.x`) → index of the entry that draws it. */
    val controlIndex: Map<String, Int>,
)

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
internal fun settingsPageLayout(page: String, advanced: Boolean, screensaverOn: Boolean): SettingsPageLayout {
    val entries = ArrayList<SettingsPageEntry>()
    val index = LinkedHashMap<String, Int>()
    var hiddenAdvanced = false
    var hiddenScreensaver = false
    val def = SettingsTree.page(page)
    if (def?.lede != null && def.parent == null) {
        entries += SettingsPageEntry("lede", SettingsEntryKind.LEDE, text = def.lede)
    }
    SettingsPageSections.of(page).forEachIndexed { sectionIndex, section ->
        if (section.advanced && !advanced) { hiddenAdvanced = true; return@forEachIndexed }
        val items = section.items.filter { item ->
            if (!advanced && item.hasAdvancedRows()) hiddenAdvanced = true
            !(item.needsScreensaver() && !screensaverOn)
        }
        if (items.size < section.items.size && !hiddenScreensaver) {
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
        items.forEachIndexed { itemIndex, item ->
            item.controlIds().forEach { id -> index.putIfAbsent(id, entries.size) }
            entries += SettingsPageEntry(item.key, item.kind(), item, advanced = section.advanced,
                first = itemIndex == 0, last = itemIndex == items.lastIndex)
        }
        section.footer?.let { entries += SettingsPageEntry("footer:$sectionIndex", SettingsEntryKind.FOOTER, text = it) }
        entries += SettingsPageEntry("gap:$sectionIndex", SettingsEntryKind.GAP)
    }
    if (hiddenAdvanced) entries += SettingsPageEntry("hint:advanced", SettingsEntryKind.ADVANCED_HINT)
    return SettingsPageLayout(entries, index)
}

/** The page that draws [control] (any row id), ignoring Advanced and screensaver visibility. */
internal fun settingsControlHome(control: String): String? = SettingsTree.pages.firstOrNull { page ->
    settingsPageLayout(page.id, advanced = true, screensaverOn = true).controlIndex.containsKey(control)
}?.id

/** Domains whose registry rows a page draws through the shared renderer. */
internal fun settingsPageDomains(page: String): List<String> =
    SettingsPageSections.of(page).flatMap { it.items }.filterIsInstance<SettingsDomainRows>()
        .map { it.domain }.distinct()
