/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leftovers of the second Settings pass (2026-11-22): one Remove style, one locked-value label,
 * standard buttons instead of bare text links for saves, human values on Automatically managed,
 * a Wallpaper glyph of its own and a header card on every top-level page.
 */
class SettingsSharedStyleTest {
    private val root: File =
        listOf(File("src/main/java/com/immortal/launcher"), File("app/src/main/java/com/immortal/launcher")).first { it.exists() }

    /** Settings sources, plus the shared editor, URL entry and paired-phone screens Settings hosts. */
    private val sources: List<Pair<String, String>> by lazy {
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && ("Settings" in it.name || it.name in HOSTED) }
            .map { it.name to it.readText() }.sortedBy { it.first }.toList()
    }

    private fun offenders(pattern: Regex, except: String? = null) = sources.filter { it.first != except }
        .flatMap { (name, text) ->
            text.lines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line)) "$name:${i + 1}" else null }
        }

    @Test fun removeActionsUseTheSharedRemoveButton() {
        val bad = offenders(AD_HOC_REMOVE)
        assertTrue("Use SettingsRemoveButton for Remove:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun lockedValuesUseTheSharedLockedLabel() {
        val bad = offenders(AD_HOC_LOCK, except = "SettingsSharedActions.kt")
        assertTrue("Use SettingsLockedValue for locked rows:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun saveActionsAreButtonsNotBareTextLinks() {
        val bad = offenders(TEXT_LINK_SAVE)
        assertTrue("Save actions are standard buttons:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun managedSwitchesReadOnOrOffNotTrueOrFalse() {
        assertEquals("On", managedValueLabel(JSONObject().put("type", "bool").put("value", true)))
        assertEquals("Off", managedValueLabel(JSONObject().put("type", "bool").put("value", false)))
    }

    @Test fun managedChoicesShowTheirOptionLabel() {
        val options = JSONArray().put(JSONObject().put("value", "classic").put("label", "Classic"))
            .put(JSONObject().put("value", "neon").put("label", "Neon"))
        assertEquals("Neon", managedValueLabel(JSONObject().put("value", "neon").put("options", options)))
    }

    @Test fun managedValuesPreferTheSpecDisplayAndFallBackToTheValue() {
        assertEquals("4 GB", managedValueLabel(JSONObject().put("value", 4).put("display", "4 GB")))
        assertEquals("7", managedValueLabel(JSONObject().put("value", 7).put("display", "")))
        assertEquals("custom", managedValueLabel(JSONObject().put("value", "custom").put("options", JSONArray())))
        assertEquals("", managedValueLabel(null))
        assertEquals("", managedValueLabel(JSONObject().put("type", "string")))
    }

    @Test fun wallpaperHasItsOwnGlyphNotDisplaysSun() {
        val wallpaper = approvedIconResource(SettingsTree.page(SettingsPageIds.WALLPAPER)!!.icon)
        assertEquals(R.drawable.approved_icon_wallpaper, wallpaper)
        listOf(SettingsPageIds.DISPLAY, SettingsPageIds.FRAME).forEach { other ->
            assertNotEquals(other, approvedIconResource(SettingsTree.page(other)!!.icon), wallpaper)
        }
        listOf("display", "sun", "photo", "screensaver").forEach { assertNotEquals(it, approvedIconResource(it), wallpaper) }
    }

    @Test fun everyTopLevelPageOpensWithAHeaderCard() {
        SettingsTree.topLevel.forEach { page ->
            assertTrue("${page.id} has no header card", !page.lede.isNullOrBlank())
            assertEquals("${page.id} header", SettingsEntryKind.LEDE,
                settingsPageLayout(page.id, advanced = true, screensaverOn = true).entries.first().kind)
        }
    }

    private companion object {
        val HOSTED = setOf("ApprovedEditorsUi.kt", "UrlEntryScreen.kt", "RemotePairedPhones.kt")
        val AD_HOC_REMOVE = Regex("""(ApprovedSettingsTextButton|ActionPill|ApprovedEditorsButton|PrimaryPillButton)\((removeLabel|"Remove)""")
        val AD_HOC_LOCK = Regex("""CallIcon\("lock"|WtGlyph\.LOCK|fun LockedValue""")
        val TEXT_LINK_SAVE = Regex("""ApprovedSettingsTextButton\("Save""")
    }
}
