/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsDomains
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class BootForegroundTest {
  private fun prefs() = ScreensaverTestPrefs().also {
    `when`(it.context.getSharedPreferences("immortal_settings", Context.MODE_PRIVATE)).thenReturn(it)
  }

  private fun domain(): SettingsDomain<ImmortalSettings.Settings> {
    val spec = SettingsDomains.immortal.specs.firstOrNull { it.key == "stayInStartupApp" }
    assertTrue("Startup focus must use the shared boolean registry", spec is BoolSpec)
    return SettingsDomain("immortal", "Immortal", ImmortalSettings::load, listOf(spec!!),
        defaults = { ImmortalSettings.Settings() })
  }

  @Test fun startupFocus_defaultsOffAndSchemaAdvertisesFalse() {
    val storage = prefs()
    val control = domain().schemaJson(storage.context).getJSONArray("controls").getJSONObject(0)
    assertFalse(control.getBoolean("value"))
    assertFalse(control.getBoolean("default"))
    assertEquals(0, storage.writeCount)
  }

  @Test fun startupFocus_roundTripsThroughRegistry() {
    val storage = prefs()
    val domain = domain()
    assertEquals(setOf("stayInStartupApp"),
        domain.apply(storage.context, JSONObject().put("stayInStartupApp", true)))
    assertTrue(domain.flatJson(storage.context).getBoolean("stayInStartupApp"))
    assertEquals(setOf("stayInStartupApp"),
        domain.apply(storage.context, JSONObject().put("stayInStartupApp", false)))
    assertFalse(domain.flatJson(storage.context).getBoolean("stayInStartupApp"))
  }

  @Test fun startupFocus_rejectsWrongTypeWithoutWriting() {
    val storage = prefs()
    assertTrue(domain().apply(storage.context,
        JSONObject().put("stayInStartupApp", 1)).isEmpty())
    assertEquals(0, storage.writeCount)
  }

  @Test fun launchCount_countsOnlySuccessfulDispatchesAndContinuesAfterFailure() {
    val folder = Files.createTempDirectory("boot-foreground").toFile()
    try {
      val context = mock(Context::class.java)
      val pm = mock(PackageManager::class.java)
      `when`(context.getExternalFilesDir(null)).thenReturn(folder)
      `when`(context.packageManager).thenReturn(pm)
      folder.resolve("boot_apps.txt").writeText("example.missing\nexample.bad\nexample.good")
      val bad = mock(Intent::class.java, RETURNS_SELF)
      val good = mock(Intent::class.java, RETURNS_SELF)
      `when`(pm.getLaunchIntentForPackage("example.bad")).thenReturn(bad)
      `when`(pm.getLaunchIntentForPackage("example.good")).thenReturn(good)
      doThrow(IllegalStateException("Cannot launch")).`when`(context).startActivity(bad)
      assertEquals(1, BootLaunch.launchAll(context))
      verify(context).startActivity(good)
    } finally {
      folder.deleteRecursively()
    }
  }

  private fun complete(stay: Boolean, launched: Int, home: () -> Unit, finish: () -> Unit) {
    val method = BootLaunch::class.java.declaredMethods.firstOrNull {
      it.name.startsWith("finishStartup")
    }
    assertNotNull("Boot must decide foreground and finish pending work together", method)
    method!!.invoke(BootLaunch, stay, launched, home, finish)
  }

  private fun expect(stay: Boolean, launched: Int, expected: List<String>) {
    val calls = mutableListOf<String>()
    complete(stay, launched, { calls += "home" }, { calls += "finish" })
    assertEquals(expected, calls)
  }

  @Test fun default_returnsHomeAfterSuccessfulLaunch() =
      expect(false, 1, listOf("home", "finish"))

  @Test fun optIn_keepsSuccessfullyLaunchedAppVisible() =
      expect(true, 1, listOf("finish"))

  @Test fun optIn_withNoLaunchFallsBackHome() =
      expect(true, 0, listOf("home", "finish"))

  @Test fun default_withNoLaunchStillReturnsHome() =
      expect(false, 0, listOf("home", "finish"))

  @Test fun homeFailure_stillFinishesPendingWork() {
    var finished = false
    assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
      complete(false, 1, { throw IllegalStateException("Home unavailable") }, { finished = true })
    }
    assertTrue(finished)
  }
}
