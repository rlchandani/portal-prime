/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageLayoutTest {
    private fun kinds(page: String, advanced: Boolean = true, screensaverOn: Boolean = true) =
        settingsPageLayout(page, advanced, screensaverOn).entries.map { it.kind }

    @Test fun topLevelPagesOpenWithTheirLedeAndSubPagesDoNot() {
        assertEquals(SettingsEntryKind.LEDE, kinds("display").first())
        assertFalse(SettingsEntryKind.LEDE in kinds("w-weather"))
    }

    @Test fun advancedSectionsAndRowsHideBehindOneHint() {
        val off = settingsPageLayout("frame", advanced = false, screensaverOn = true)
        assertFalse("screensaver.ambientDashboard" in off.controlIndex)
        assertEquals(1, off.entries.count { it.kind == SettingsEntryKind.ADVANCED_HINT })
        val on = settingsPageLayout("frame", advanced = true, screensaverOn = true)
        assertTrue("screensaver.ambientDashboard" in on.controlIndex)
        assertFalse(SettingsEntryKind.ADVANCED_HINT in on.entries.map { it.kind })
        val header = on.entries.single { it.kind == SettingsEntryKind.HEADER && it.text == "Advanced" }
        assertTrue(header.advanced)
    }

    @Test fun pagesWithoutAdvancedContentShowNoHint() {
        assertFalse(SettingsEntryKind.ADVANCED_HINT in kinds("wallpaper", advanced = false))
    }

    @Test fun screensaverRowsHideWhileItIsOffWithOneNotice() {
        val frame = settingsPageLayout("frame", advanced = true, screensaverOn = false)
        assertTrue("screensaver.enabled" in frame.controlIndex)
        assertFalse("screensaver.mode" in frame.controlIndex)
        assertFalse("frame.preview" in frame.controlIndex)
        val notice = frame.entries.single { it.kind == SettingsEntryKind.NOTICE }
        assertEquals(FRAME_OFF_NOTICE, notice.text)
        // The notice sits right after the master switch.
        assertEquals(frame.controlIndex.getValue("screensaver.enabled") + 2, frame.entries.indexOf(notice))
        val night = settingsPageLayout("night", advanced = true, screensaverOn = false)
        assertEquals(SCREENSAVER_OFF_NOTICE, night.entries.single { it.kind == SettingsEntryKind.NOTICE }.text)
        // The sleep timer and the lamp do not depend on the screensaver.
        assertTrue("screensaver.sleepTimerEnabled" in night.controlIndex)
        assertTrue("ext.lamp" in night.controlIndex)
    }

    @Test fun controlIndexPointsAtTheEntryThatDrawsTheRow() {
        val layout = settingsPageLayout("night", advanced = true, screensaverOn = true)
        val rows = layout.entries[layout.controlIndex.getValue("screensaver.overnightEndMin")]
        assertEquals(SettingsEntryKind.ROWS, rows.kind)
        assertTrue((rows.item as SettingsDomainRows).keys.contains("overnightEndMin"))
        val block = layout.entries[layout.controlIndex.getValue("screensaver.sleepTimerMin")]
        assertEquals(SettingsBlockItem(SettingsBlock.SLEEP_TIMER), block.item)
        val lamp = layout.entries[layout.controlIndex.getValue("ext.lamp")]
        assertEquals(SettingsEntryKind.EXTERNAL, lamp.kind)
    }

    @Test fun entryKeysAreUniquePerPageSoLazyItemsAreStable() {
        SettingsTree.pages.forEach { page ->
            listOf(true, false).forEach { advanced ->
                val keys = settingsPageLayout(page.id, advanced, screensaverOn = advanced).entries.map { it.key }
                assertEquals("${page.id} has duplicate keys", keys.size, keys.toSet().size)
            }
        }
    }

    @Test fun cardEdgesWrapEachSectionsItems() {
        val widgets = settingsPageLayout("widgets", advanced = false, screensaverOn = true).entries
            .filter { it.kind == SettingsEntryKind.LINK }
        val section = widgets.drop(1)
        assertTrue(section.first().first)
        assertTrue(section.last().last)
        assertTrue(section.drop(1).dropLast(1).none { it.first || it.last })
    }

    @Test fun unknownPageIsEmpty() {
        val layout = settingsPageLayout("gone", advanced = true, screensaverOn = true)
        assertTrue(layout.entries.isEmpty())
        assertNull(layout.controlIndex["display.brightness"])
    }

    @Test fun pageDomainsListOnlyRegistryRowDomains() {
        assertEquals(listOf("screensaver"), settingsPageDomains("frame"))
        assertEquals(listOf("immortal"), settingsPageDomains("display"))
        assertTrue(settingsPageDomains("greeting").isEmpty())
    }

    @Test fun rowsNextToABlockCloseTheirCardAndTheBlockKeepsACardGap() {
        // Calls › Messages / Drop-ins / Phones mix registry rows, a bespoke block and a link.
        val calls = settingsPageLayout("calls", advanced = true, screensaverOn = true).entries
        val messagesRow = calls[settingsPageLayout("calls", true, true).controlIndex.getValue("calling.messagesOn")]
        assertTrue(messagesRow.first && messagesRow.last)
        val phones = calls.dropWhile { it.key != "block:CALL_PHONES" }
        val block = phones.first()
        assertTrue(block.gapBefore && block.gapAfter)
        val pair = phones[1]
        assertEquals(SettingsEntryKind.LINK, pair.kind)
        assertTrue(pair.first && pair.last)
        val dropIns = calls.single { it.key == "block:CALL_DROP_INS" }
        assertTrue(dropIns.gapBefore)
        assertFalse(dropIns.gapAfter)
    }

    @Test fun everyCardOnEveryPageIsClosedAndOnlyBlocksBetweenCardsCarryGaps() {
        SettingsTree.pages.forEach { page ->
            val entries = settingsPageLayout(page.id, advanced = true, screensaverOn = true).entries
            var open = false
            entries.forEachIndexed { i, entry ->
                val card = entry.kind in setOf(SettingsEntryKind.ROWS, SettingsEntryKind.LINK, SettingsEntryKind.EXTERNAL)
                if (card) {
                    assertEquals("${page.id}: ${entry.key} opens a card only when none is open", !open, entry.first)
                    open = !entry.last
                } else {
                    assertFalse("${page.id}: ${entry.key} sits inside an unclosed card", open)
                }
                if (entry.kind != SettingsEntryKind.BLOCK) assertFalse(entry.gapBefore || entry.gapAfter)
                if (entry.gapBefore) assertTrue("${page.id}: ${entry.key} gap follows a card",
                    entries[i - 1].kind in setOf(SettingsEntryKind.ROWS, SettingsEntryKind.LINK, SettingsEntryKind.EXTERNAL, SettingsEntryKind.BLOCK))
            }
            assertFalse("${page.id} ends inside a card", open)
        }
    }

    @Test fun cardGroupsSplitRunsAtEveryBlock() {
        val rows = SettingsDomainRows("calling", listOf("phonesOn"))
        val block = SettingsBlockItem(SettingsBlock.CALL_PHONES)
        val link = SettingsLinkItem("Pair a phone", "phone")
        assertEquals(listOf(listOf(rows), listOf(block), listOf(link)), settingsCardGroups(listOf(rows, block, link)))
        assertEquals(listOf(listOf(rows, link)), settingsCardGroups(listOf(rows, link)))
        assertEquals(listOf(listOf(block), listOf(block)), settingsCardGroups(listOf(block, block)))
        assertTrue(settingsCardGroups(emptyList()).isEmpty())
    }

    @Test fun pageRhythmTokensStayOnTheScale() {
        // The vertical rhythm every page and block shares; change it here, deliberately.
        assertEquals(34, SettingsSpacing.SECTION_GAP_DP)
        assertEquals(20, SettingsSpacing.CARD_GAP_DP)
        assertEquals(28, SettingsSpacing.TEXT_INSET_DP)
        assertTrue(SettingsSpacing.CARD_GAP_DP < SettingsSpacing.SECTION_GAP_DP)
        val gapped = settingsPageLayout("calls", advanced = true, screensaverOn = true).entries.single { it.key == "block:CALL_PHONES" }
        assertEquals(SettingsRevealPlan.placeholderDp(gapped.copy(gapBefore = false, gapAfter = false)) +
            2 * SettingsSpacing.CARD_GAP_DP, SettingsRevealPlan.placeholderDp(gapped))
    }

    @Test fun aSectionWhoseRowsAreAllGatedOffDrawsNoEmptyHeaderOrFooter() {
        // Calendar › Photo frame rows wait for a feed; Photo Sources › Self-hosted for a self-hosted source.
        val full = settingsPageLayout("w-calendar", advanced = true, screensaverOn = true)
        val rowsKey = full.entries.single { it.kind == SettingsEntryKind.ROWS && "calendar.widgetOn" in (it.item as SettingsDomainRows).ids() }.key
        val gated = settingsPageLayout("w-calendar", advanced = true, screensaverOn = true, emptyRows = setOf(rowsKey))
        assertFalse(gated.entries.any { it.kind == SettingsEntryKind.HEADER && it.text == "Photo frame" })
        assertTrue(gated.entries.any { it.kind == SettingsEntryKind.HEADER && it.text == "Feed" })
        val sources = settingsPageLayout("sources", advanced = true, screensaverOn = true,
            emptyRows = setOf("rows:screensaver:cacheEnabled"))
        assertFalse(sources.entries.any { it.kind == SettingsEntryKind.FOOTER && it.text == "Keeps a copy so photos show when offline." })
    }
}
