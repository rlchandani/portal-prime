/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings tripwire from the 2026-11-22 pass 2: World clock and Start on boot drew their rows in a
 * LazyColumn bounded by heightIn(max = …) inside a card, so the list scrolled inside the page and a
 * row was cut at the card's bottom edge. Page content is plain card rows in the page's own scroll;
 * only the scroll owners below (the page list, the standalone scaffold, the sidebar, dialogs and the
 * Control Center pane) may create a vertical scroll.
 */
class SettingsNestedScrollSourceScanTest {
    private val root: File =
        listOf(File("src/main/java/com/immortal/launcher"), File("app/src/main/java/com/immortal/launcher")).first { it.exists() }
    private val settingsSources: List<Pair<String, String>> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && ("Settings" in it.name || it.name in PAGE_CONTENT) }
            .map { it.name to it.readText() }.sortedBy { it.first }.toList()
    }

    private fun hits(pattern: Regex, allowed: Set<String>): List<String> = settingsSources
        .filter { (name, _) -> name !in allowed }
        .flatMap { (name, text) ->
            text.lines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "$name:${i + 1}" else null }
        }

    @Test fun settingsContentNeverScrollsInsideTheSettingsPage() {
        val bad = hits(VERTICAL_SCROLL, SCROLL_OWNERS.keys)
        assertTrue("Render these rows as normal card rows in the page's own scroll " +
            "(SettingsDeferredSlot per row keeps them in the staged reveal):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun noSettingsCardBoundsAScrollingList() {
        val bad = settingsSources.flatMap { (name, text) ->
            text.lines().mapIndexedNotNull { i, line ->
                if (BOUNDED_LIST.containsMatchIn(line) && name !in DIALOG_LISTS) "$name:${i + 1}" else null
            }
        }
        assertTrue("A list bounded by heightIn(max) clips its last row; let the page scroll:\n" +
            bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theScanStillSeesThePagesThatHadTheDefect() {
        val names = settingsSources.map { it.first }.toSet()
        assertTrue(listOf("WorldClockSettingsContent.kt", "BootAppsSettingsContent.kt", "SettingsPageView.kt",
            "DoorbellPickerActivity.kt", "HaSettingsActivity.kt").all { it in names })
        assertTrue(SCROLL_OWNERS.keys.all { it in names })
    }

    private companion object {
        val VERTICAL_SCROLL = Regex("""\b(LazyColumn|LazyVerticalGrid|LazyVerticalStaggeredGrid)\s*\(|\.verticalScroll\(""")
        val BOUNDED_LIST = Regex("""(LazyColumn|verticalScroll).*heightIn\(max""")

        /** Settings sub-screens whose file names don't say Settings. */
        val PAGE_CONTENT = setOf("DoorbellPickerActivity.kt", "SettingsPageView.kt")

        /** Files that legitimately own a vertical scroll, and why. */
        val SCROLL_OWNERS = mapOf(
            "SettingsPageView.kt" to "the split-view page's one lazy list",
            "SettingsScaffold.kt" to "the standalone Activity page scroll",
            "SettingsSidebar.kt" to "the sidebar's own list",
            "SettingsSplitShell.kt" to "the split view's sidebar list",
            "ApprovedSettingsRows.kt" to "the dialog body scroll",
            "SettingsComponents.kt" to "the choice dialog's option list",
            "WhosThereSettingsBlocks.kt" to "the model licences dialog text",
            "ControlCenterSettingsScreen.kt" to "Control Center's full-height settings pane, not a Settings page",
        )

        /** Dialog option lists, bounded to the dialog window (not a card in a page). */
        val DIALOG_LISTS = setOf("SettingsComponents.kt")
    }
}
