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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Replaces only Android storage; config validation, reads and writes remain real. */
internal class HomeDashboardTestPrefs(initialUrl: String = "") {
  private var storedUrl = initialUrl
  var writeCount = 0
    private set
  val context: Context = mock(Context::class.java)

  init {
    val prefs = mock(SharedPreferences::class.java)
    `when`(context.getSharedPreferences("immortal_home_dashboard", Context.MODE_PRIVATE))
        .thenReturn(prefs)
    `when`(prefs.getString("url", "")).thenAnswer { storedUrl }
    `when`(prefs.edit()).thenAnswer {
      val editor = mock(SharedPreferences.Editor::class.java)
      var pendingUrl = storedUrl
      `when`(editor.putString(org.mockito.ArgumentMatchers.eq("url"), anyString()))
          .thenAnswer { call ->
            pendingUrl = call.getArgument<String>(1)
            editor
          }
      doAnswer { storedUrl = pendingUrl; writeCount++; null }.`when`(editor).apply()
      editor
    }
  }
}

class HomeDashboardConfigTest {
  @Test
  fun blankAddressDisablesTheDashboard() {
    listOf("", "   ", "\u2003 ").forEach { raw ->
      assertEquals("", HomeDashboardConfig.normalizeUrl(raw))
    }
  }

  @Test
  fun absoluteHttpAddressesKeepTheirMeaningfulComponents() {
    listOf(
        "http://dashboard.example",
        "https://dashboard.example/",
        "HTTP://Dashboard.Example:8123/lovelace/Overview/?room=kitchen#lights",
        "https://dashboard.example/path/../view/?next=%2Frooms%2F&name=Living%20Room",
        "http://192.0.2.10:8123/dashboard/tablet/",
        "http://[2001:db8::1]:8123/lovelace/0?panel=1",
        "http://homeassistant.local:8123/",
        "http://dashboard/view",
        "https://dashboard.example:65535/view",
    ).forEach { url ->
      assertEquals(url, HomeDashboardConfig.normalizeUrl(url))
    }
  }

  @Test
  fun normalizationTrimsOnlyOuterWhitespace() {
    assertEquals(
        "https://dashboard.example/lovelace/Overview/?kiosk=1#lights",
        HomeDashboardConfig.normalizeUrl(
            " \u2003https://dashboard.example/lovelace/Overview/?kiosk=1#lights\u2003 "))
    assertNull(HomeDashboardConfig.normalizeUrl("https://dashboard.example/living room"))
  }

  @Test
  fun schemesRelativeAddressesAndMissingHostsAreRejected() {
    listOf(
        "dashboard.example",
        "/lovelace/0",
        "//dashboard.example/view",
        "javascript:alert(1)",
        "file:///dashboard.html",
        "ftp://dashboard.example/view",
        "data:text/html,dashboard",
        "http:dashboard.example",
        "https:///lovelace/0",
        "https://",
        "https://?view=1",
        "https://#lights",
    ).forEach { raw ->
      assertNull(raw, HomeDashboardConfig.normalizeUrl(raw))
    }
  }

  @Test
  fun credentialsAndMalformedAddressesAreRejected() {
    listOf(
        "https://user:password@dashboard.example/view",
        "https://user@dashboard.example/",
        "https://@dashboard.example/",
        "https://dashboard.example\\@other.example/",
        "https://bad host/view",
        "https://dashboard.example/%",
        "https://dashboard.example/%GG",
        "http://[2001:db8::1/view",
        "http://[not-an-ip]/view",
        "http://dashboard.example:bad/view",
        "http://dashboard.example:-1/view",
        "http://dashboard.example:65536/view",
    ).forEach { raw ->
      assertNull(raw, HomeDashboardConfig.normalizeUrl(raw))
    }
  }

  @Test
  fun controlCharactersAreRejectedIncludingAtTheEdges() {
    ((0..31) + (127..159)).forEach { code ->
      val control = code.toChar()
      assertNull(HomeDashboardConfig.normalizeUrl("https://dashboard.example/view${control}extra"))
      assertNull(HomeDashboardConfig.normalizeUrl("${control}https://dashboard.example/"))
      assertNull(HomeDashboardConfig.normalizeUrl("https://dashboard.example/$control"))
    }
  }

  @Test
  fun unsetAddressIsDisabledAndUsesAnEmptyImmutableSnapshot() {
    val prefs = HomeDashboardTestPrefs()
    assertEquals(HomeDashboardConfig.Settings(), HomeDashboardConfig.load(prefs.context))
    assertFalse(HomeDashboardConfig.configured(prefs.context))
    assertEquals(0, prefs.writeCount)
  }

  @Test
  fun setterPersistsTheNormalizedAddressWithoutChangingAnEarlierSnapshot() {
    val prefs = HomeDashboardTestPrefs()
    val before = HomeDashboardConfig.load(prefs.context)
    HomeDashboardConfig.setUrl(prefs.context, "  https://dashboard.example/view/?kiosk=1  ")

    assertEquals("", before.url)
    assertEquals("https://dashboard.example/view/?kiosk=1",
        HomeDashboardConfig.load(prefs.context).url)
    assertTrue(HomeDashboardConfig.configured(prefs.context))
    assertEquals(1, prefs.writeCount)
  }

  @Test
  fun invalidDirectWritesLeaveTheSavedAddressUnchanged() {
    val saved = "https://dashboard.example/view/"
    val prefs = HomeDashboardTestPrefs(saved)
    listOf("not a URL", "file:///view", "https://user@dashboard.example/",
        "https://dashboard.example/view\nextra").forEach { raw ->
      HomeDashboardConfig.setUrl(prefs.context, raw)
    }

    assertEquals(saved, HomeDashboardConfig.load(prefs.context).url)
    assertTrue(HomeDashboardConfig.configured(prefs.context))
    assertEquals(0, prefs.writeCount)
  }

  @Test
  fun blankSetterClearsAnExistingAddress() {
    val prefs = HomeDashboardTestPrefs("https://dashboard.example/view/")
    HomeDashboardConfig.setUrl(prefs.context, "   ")

    assertEquals(HomeDashboardConfig.Settings(), HomeDashboardConfig.load(prefs.context))
    assertFalse(HomeDashboardConfig.configured(prefs.context))
    assertEquals(1, prefs.writeCount)
  }

  @Test
  fun invalidPersistedAddressesAreDisabledWithoutWritingDuringLoad() {
    listOf("not a URL", "https://user@dashboard.example/", "https://dashboard.example/\n")
        .forEach { raw ->
          val prefs = HomeDashboardTestPrefs(raw)
          assertEquals(HomeDashboardConfig.Settings(), HomeDashboardConfig.load(prefs.context))
          assertFalse(HomeDashboardConfig.configured(prefs.context))
          assertEquals(0, prefs.writeCount)
        }
  }

  @Test
  fun loadValidatesAndTrimsAnExistingAddress() {
    val prefs = HomeDashboardTestPrefs("  http://dashboard.example:8123/view/?kiosk=1  ")
    assertEquals("http://dashboard.example:8123/view/?kiosk=1",
        HomeDashboardConfig.load(prefs.context).url)
    assertTrue(HomeDashboardConfig.configured(prefs.context))
    assertEquals(0, prefs.writeCount)
  }
}
