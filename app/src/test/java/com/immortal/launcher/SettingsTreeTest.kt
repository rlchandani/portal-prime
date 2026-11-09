/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.NavSpec
import com.immortal.launcher.settings.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The split-view tree matches the approved mock and gives every setting exactly one home. */
class SettingsTreeTest {
    private val allItems = SettingsTree.pages.flatMap { page ->
        SettingsPageSections.of(page.id).flatMap { it.items }.map { page.id to it }
    }

    @Test fun sidebarHasTheMocksFourGroupsAndEighteenPagesInOrder() {
        assertEquals(listOf("display", "sounds", "frame", "alarms", "night"),
            SettingsTree.groupPages(SettingsGroup.MOST_USED).map { it.id })
        assertEquals(listOf("wallpaper", "home", "widgets", "clock", "sources", "greeting"),
            SettingsTree.groupPages(SettingsGroup.PERSONALIZE).map { it.id })
        assertEquals(listOf("ha", "phone", "intercom", "music"),
            SettingsTree.groupPages(SettingsGroup.CONNECTED_HOME).map { it.id })
        assertEquals(listOf("about", "system", "advanced"),
            SettingsTree.groupPages(SettingsGroup.GENERAL).map { it.id })
        assertEquals(18, SettingsTree.topLevel.size)
        assertEquals(listOf(null, "Personalize", "Connected home", "General"), SettingsGroup.entries.map { it.title })
    }

    @Test fun titlesFollowTheMock() {
        assertEquals(listOf("Display & Brightness", "Sounds", "Photo Frame", "Alarms & Wake", "Night & Sleep",
            "Wallpaper", "Home Screen", "Widgets & Calendar", "Clock Faces", "Photo Sources", "Welcome Greeting",
            "Home Assistant", "Phone Remote", "Intercom", "Music", "About & Health", "Portal & Android", "Advanced"),
            SettingsTree.topLevel.map { it.title })
    }

    @Test fun pageIdsAreUniqueAndEveryPageHasContent() {
        assertEquals(SettingsTree.pages.size, SettingsTree.pages.map { it.id }.toSet().size)
        assertEquals(SettingsTree.pages.map { it.id }.toSet(), SettingsPageSections.pageIds)
        SettingsTree.pages.forEach { assertTrue("${it.id} is empty", SettingsPageSections.of(it.id).isNotEmpty()) }
    }

    @Test fun treeIsAtMostThreeDeepAndSubPagesHaveValidParents() {
        SettingsTree.pages.forEach { page ->
            val chain = SettingsTree.chainOf(page.id)
            assertTrue("${page.id} is ${chain.size} deep", chain.size in 1..SettingsBackStack.MAX_DEPTH)
            assertEquals(page.id, chain.last())
            assertTrue("${page.id} root must be in the sidebar", SettingsTree.page(chain.first())?.group != null)
            assertEquals("${page.id} has either a group or a parent", page.group == null, page.parent != null)
        }
    }

    @Test fun everySubPageIsReachableFromALinkOrARegistryNavigationRow() {
        val linked = allItems.mapNotNull { (_, item) -> (item as? SettingsLinkItem)?.target }.toSet() +
            SettingsBlock.entries.flatMap { it.opens }
        val navSpecPages = SettingsRegistry.domains.flatMap { it.specs }.filterIsInstance<NavSpec<*>>()
            .mapNotNull { settingsPageForActivity(it.activity) }.toSet()
        SettingsTree.pages.filter { it.parent != null }.forEach { page ->
            assertTrue("${page.id} is unreachable", page.id in linked || page.id in navSpecPages)
        }
    }

    @Test fun everyRegisteredDomainRowIsReachableFromExactlyOnePage() {
        val homes = HashMap<String, MutableList<String>>()
        allItems.forEach { (page, item) ->
            val ids = when (item) {
                is SettingsDomainRows -> item.ids()
                is SettingsBlockItem -> settingsClaimedSpecs(item.block).map { it.first }
                else -> emptyList()
            }
            ids.forEach { homes.getOrPut(it) { mutableListOf() } += page }
        }
        val registered = SettingsRegistry.domains.flatMap { domain ->
            domain.specs.filter { it !is DerivedSpec<*> }.map { "${domain.id}.${it.key}" }
        }
        val missing = registered.filter { it !in homes }
        assertTrue("Settings without a page: $missing", missing.isEmpty())
        val duplicated = homes.filterValues { it.size > 1 }
        assertTrue("Settings on more than one page: $duplicated", duplicated.isEmpty())
        val unknown = homes.keys - registered.toSet()
        assertTrue("Rows that are not registered specs: $unknown", unknown.isEmpty())
    }

    @Test fun everyBlockAppearsOnExactlyOnePageAndNativeRowIdsAreUnique() {
        val blocks = allItems.mapNotNull { (_, item) -> (item as? SettingsBlockItem)?.block }
        assertEquals(SettingsBlock.entries.toSet(), blocks.toSet())
        assertEquals(blocks.size, blocks.toSet().size)
        val nativeIds = SettingsBlock.entries.flatMap { block -> block.rows.map { it.id } }
        assertEquals(nativeIds.size, nativeIds.toSet().size)
    }

    @Test fun everyPageIconResolvesToItsOwnGlyph() {
        val fallback = approvedIconResource("info")
        SettingsTree.pages.filter { it.icon != "info" }.forEach {
            assertNotEquals("${it.id} icon ${it.icon} falls back", fallback, approvedIconResource(it.icon))
        }
    }

    @Test fun mockMappingHighlightsLandWhereTheMockSays() {
        // Spot checks of the mock's "Where did X go?" table.
        assertEquals("sounds", settingsControlHome("display.mediaVolume"))
        assertEquals("display", settingsControlHome("immortal.clockFormat"))
        assertEquals("home", settingsControlHome("immortal.tileSize"))
        assertEquals("night", settingsControlHome("screensaver.presenceMode"))
        assertEquals("night", settingsControlHome("screensaver.sleepTimerEnabled"))
        assertEquals("sounds", settingsControlHome("screensaver.soundscape"))
        assertEquals("frame", settingsControlHome("screensaver.gestureWave"))
        assertEquals("sources", settingsControlHome("screensaver.cacheEnabled"))
        assertEquals("w-calendar", settingsControlHome("calendar.widgetOn"))
        assertEquals("w-calendar", settingsControlHome("calendarpacks.prayer"))
        assertEquals("music", settingsControlHome("immortal.multiRoomEnabled"))
        assertEquals("mqtt", settingsControlHome("mqtt.host"))
        assertEquals("about", settingsControlHome("fleet.name"))
        assertEquals("alarms", settingsControlHome("sunrise.enabled"))
        assertEquals("adv-boot", settingsControlHome("immortal.stayInStartupApp"))
        assertEquals("adv-managed", settingsControlHome("digitalclock.glow"))
        assertEquals("system", settingsControlHome("ext.wifi"))
        assertEquals("night", settingsControlHome("ext.lamp"))
    }
}
