/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercise the production launch boundary without opening or changing Android settings. */
class AndroidSettingsNavigationTest {
  @Test fun preferredSettingsOpenOnceWithoutFallbackOrNotice() = launcher { f ->
    AndroidSettingsNavigation.open(f.context, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
    assertEquals(listOf(Settings.ACTION_WIFI_SETTINGS), f.attempts)
    assertTrue(f.notices.isEmpty())
  }

  @Test fun missingPreferredPageFallsBackOnceWithAnExplanation() = launcher(
      failure = { _, count -> if (count == 1) ActivityNotFoundException("fixture") else null }) { f ->
    AndroidSettingsNavigation.open(f.context, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
    assertEquals(listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(wifiFallback), f.notices)
    Mockito.verify(f.toast).show()
  }

  @Test fun deniedPreferredPageAlsoHasTheExplainedFallback() = launcher(
      failure = { _, count -> if (count == 1) SecurityException("fixture") else null }) { f ->
    AndroidSettingsNavigation.open(f.context, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
    assertEquals(listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(wifiFallback), f.notices)
  }

  @Test fun failedPreferredAndFallbackStayInTheHostWithARecoveryNotice() = launcher(
      failure = { _, _ -> ActivityNotFoundException("fixture") }) { f ->
    AndroidSettingsNavigation.open(f.context, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
    assertEquals(listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(wifiUnavailable), f.notices)
    Mockito.verify(f.toast).show()
  }

  @Test fun failedGeneralSettingsAreNotLaunchedTwice() = launcher(
      failure = { _, _ -> SecurityException("fixture") }) { f ->
    AndroidSettingsNavigation.open(f.context, "All Android Settings", Settings.ACTION_SETTINGS)
    assertEquals(listOf(Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(generalUnavailable), f.notices)
  }

  @Test fun healthyGeneralSettingsHaveNoFallbackNotice() = launcher { f ->
    AndroidSettingsNavigation.open(f.context, "All Android Settings", Settings.ACTION_SETTINGS)
    assertEquals(listOf(Settings.ACTION_SETTINGS), f.attempts)
    assertTrue(f.notices.isEmpty())
  }

  @Test fun installedPortalSettingsUseTheirExistingPackageIntent() = launcher(portalInstalled = true) { f ->
    AndroidSettingsNavigation.openPortal(f.context)
    assertEquals(listOf("portal"), f.attempts)
    assertTrue(f.notices.isEmpty())
    Mockito.verify(f.packages).getLaunchIntentForPackage("com.facebook.alohaapps.settings")
  }

  @Test fun absentPortalSettingsExplainTheGeneralFallback() = launcher { f ->
    AndroidSettingsNavigation.openPortal(f.context)
    assertEquals(listOf(Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(portalFallback), f.notices)
  }

  @Test fun deniedPortalSettingsUseOneExplainedFallback() = launcher(portalInstalled = true,
      failure = { action, _ -> if (action == "portal") SecurityException("fixture") else null }) { f ->
    AndroidSettingsNavigation.openPortal(f.context)
    assertEquals(listOf("portal", Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(portalFallback), f.notices)
  }

  @Test fun failedPortalPackageLookupStillAllowsGeneralSettings() = launcher(
      lookupFailure = IllegalStateException("fixture")) { f ->
    AndroidSettingsNavigation.openPortal(f.context)
    assertEquals(listOf(Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(portalFallback), f.notices)
  }

  @Test fun unavailablePortalAndGeneralPagesShowOneFinalRecoveryNotice() = launcher(
      portalInstalled = true, failure = { _, _ -> ActivityNotFoundException("fixture") }) { f ->
    AndroidSettingsNavigation.openPortal(f.context)
    assertEquals(listOf("portal", Settings.ACTION_SETTINGS), f.attempts)
    assertEquals(listOf(portalUnavailable), f.notices)
    Mockito.verify(f.toast).show()
  }

  @Test fun fatalErrorsAreNotDisguisedAsMissingPages() = launcher(
      failure = { _, count -> if (count == 1) AssertionError("fixture") else null }) { f ->
    assertThrows(AssertionError::class.java) {
      AndroidSettingsNavigation.open(f.context, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS)
    }
    assertEquals(listOf(Settings.ACTION_WIFI_SETTINGS), f.attempts)
    assertTrue(f.notices.isEmpty())
  }

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val packages = Mockito.mock(PackageManager::class.java)
    val portal = Mockito.mock(Intent::class.java)
    val toast = Mockito.mock(Toast::class.java)
    val attempts = mutableListOf<String>()
    val notices = mutableListOf<String>()
  }

  private fun launcher(
      portalInstalled: Boolean = false,
      lookupFailure: Exception? = null,
      failure: (String, Int) -> Throwable? = { _, _ -> null },
      operation: (Fixture) -> Unit,
  ) {
    val f = Fixture()
    Mockito.`when`(f.context.packageManager).thenReturn(f.packages)
    val lookup = Mockito.`when`(f.packages.getLaunchIntentForPackage("com.facebook.alohaapps.settings"))
    if (lookupFailure != null) lookup.thenThrow(lookupFailure)
    else lookup.thenReturn(if (portalInstalled) f.portal else null)
    val actions = linkedMapOf<Intent, String>()
    Mockito.mockConstruction(Intent::class.java) { intent, construction ->
      actions[intent] = construction.arguments().first() as String
    }.use {
      Mockito.doAnswer { invocation ->
        val intent = invocation.getArgument<Intent>(0)
        val action = if (intent === f.portal) "portal" else checkNotNull(actions[intent])
        f.attempts.add(action)
        failure(action, f.attempts.size)?.let { throw it }
        null
      }.`when`(f.context).startActivity(Mockito.any(Intent::class.java))
      Mockito.mockStatic(Toast::class.java).use { toasts ->
        listOf(wifiFallback, wifiUnavailable, generalUnavailable, portalFallback, portalUnavailable).forEach { message ->
          toasts.`when`<Toast> { Toast.makeText(f.context, message, Toast.LENGTH_LONG) }
              .thenAnswer { f.notices.add(message); f.toast }
        }
        operation(f)
      }
    }
  }

  private val wifiFallback = "Wi-Fi isn’t available here. Opening Android settings instead."
  private val wifiUnavailable = "Wi-Fi couldn’t be opened. Please try again."
  private val generalUnavailable = "All Android Settings couldn’t be opened. Please try again."
  private val portalFallback = "Portal Settings isn’t available here. Opening Android settings instead."
  private val portalUnavailable = "Portal Settings couldn’t be opened. Please try again."
}
