/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SettingsLastPageTest {
    @Test fun firstRunOpensDisplayAndBrightness() {
        assertEquals("display", SettingsLastPage.resolve(null, advanced = false))
        assertEquals("display", SettingsPageIds.DEFAULT)
    }

    @Test fun storedPageReopens() {
        assertEquals("night", SettingsLastPage.resolve("night", advanced = false))
        assertEquals("w-calendar", SettingsLastPage.resolve("w-calendar", advanced = false))
    }

    @Test fun unknownOrRemovedIdsFallBack() {
        assertEquals("display", SettingsLastPage.resolve("", advanced = true))
        assertEquals("display", SettingsLastPage.resolve("appearance", advanced = true))
    }

    @Test fun advancedOnlyPagesNeedAdvancedOn() {
        assertEquals("display", SettingsLastPage.resolve("mqtt", advanced = false))
        assertEquals("mqtt", SettingsLastPage.resolve("mqtt", advanced = true))
        // The Advanced page itself holds the switch, so it always reopens.
        assertEquals("advanced", SettingsLastPage.resolve("advanced", advanced = false))
    }

    @Test fun onlyChangedRealPagesAreWritten() {
        assertTrue(SettingsLastPage.shouldStore(null, "sounds"))
        assertTrue(SettingsLastPage.shouldStore("display", "sounds"))
        assertFalse(SettingsLastPage.shouldStore("sounds", "sounds"))
        assertFalse(SettingsLastPage.shouldStore("sounds", "not-a-page"))
    }

    @Test fun writeAndReadUseOnePrivatePreference() {
        val editor = mock(SharedPreferences.Editor::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(prefs.getString(anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn("display")
        SettingsLastPage.write(context, "sounds")
        verify(editor).putString("last_page", "sounds")
        verify(editor).apply()
        val unchanged = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(unchanged)
        SettingsLastPage.write(context, "display")
        verify(unchanged, never()).apply()
        assertEquals("display", SettingsLastPage.read(context))
    }
}
