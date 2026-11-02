/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.ScreensaverConfig
import com.immortal.launcher.ScreensaverTestPrefs
import com.immortal.launcher.prepareSettingsVisibility
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual registered predicates/schema and native projection, with real in-memory config setters. */
class ScreensaverVisibilityParityTest {
  private val sources = listOf("default", "folder", "url", "immich", "smb", "dav", "weburl")
  private val nativeOnly = setOf("clockFace", "photoSource", "dismissTarget")
  private val domain get() = SettingsDomains.screensaver

  private fun prefs(enabled: Boolean, source: String = "default", mode: String = "smartFrame") =
      ScreensaverTestPrefs(mapOf(
          "enabled" to enabled, "source" to source, "mode" to mode,
          "folder_path" to "/photos", "album_url" to "https://photos.example/album",
          "immich_url" to "https://immich.example", "immich_key" to "test-key",
          "smb_host" to "nas.example", "smb_share" to "photos",
          "dav_url" to "https://dav.example/photos", "web_url" to "https://frame.example",
          "overnight_enabled" to true, "overnight_night_clock" to true,
          "dark_room_clock" to true, "sleep_timer_enabled" to true,
          "soundscape" to ScreensaverConfig.SOUND_RAIN,
      ))

  private fun controls(schema: JSONObject): Map<String, JSONObject> =
      schema.getJSONArray("controls").let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("key") }
      }

  @Test fun disabledRegisteredScreensaverShowsOnlyMasterForEverySourceAndStyle() {
    exportRegisteredSchemas()
    for (source in sources) for (mode in ScreensaverConfig.MODES) {
      val p = prefs(false, source, mode)
      assertEquals("$source/$mode", setOf("enabled"), controls(domain.schemaJson(p.context)).keys)
      for (advanced in listOf(false, true)) {
        val snapshot = ScreensaverConfig.load(p.context)
        val native = prepareSettingsVisibility(domain, snapshot, advanced) {
          it.visibleWhen(p.context, snapshot)
        }
        assertEquals(setOf("enabled"), native.projection.items.mapNotNull { it.value?.key }.toSet())
        assertFalse("No hidden child can supply an Advanced hint", native.showAdvancedHint)
      }
      assertEquals("Visibility must not write preferences", 0, p.writeCount)
    }
  }

  @Test fun actualNativeAndRemoteControlsAgreeAcrossMasterTierSourceAndStyle() {
    var combinations = 0
    for (enabled in listOf(false, true)) for (source in sources)
        for (mode in ScreensaverConfig.MODES) for (advanced in listOf(false, true)) {
      val p = prefs(enabled, source, mode)
      val snapshot = ScreensaverConfig.load(p.context)
      val remote = controls(domain.schemaJson(p.context))
      if (enabled) assertTrue(remote.keys.containsAll(
          setOf("enabled", "mode", "intervalSec", "shuffle", "includeVideo", "showNowPlaying")))
      val renderedRemote = remote.values.filter {
        advanced || it.getString("tier") != "advanced"
      }.map { it.getString("key") }.toSet()
      val native = prepareSettingsVisibility(domain, snapshot, advanced) {
        it.visibleWhen(p.context, snapshot)
      }.projection.items.mapNotNull { it.value?.key }.toSet() - nativeOnly
      assertEquals("$enabled/$source/$mode/advanced=$advanced", renderedRemote, native)
      combinations++
    }
    assertEquals(7 * ScreensaverConfig.MODES.size * 4, combinations)
  }

  @Test fun hiddenSavedChoicesRemainValidatedWritableAndReappearWhenEnabled() {
    val p = prefs(false)
    val normal = controls(domain.schemaJson(p.context))
    assertEquals(setOf("enabled"), normal.keys)
    val inactive = controls(domain.schemaJson(p.context, includeInactive = true))
    assertTrue(inactive.keys.containsAll(setOf("shuffle", "intervalSec", "cacheBudgetGb",
        "darkRoomLux", "nightClockBrightness")))
    assertEquals(0, p.writeCount)

    assertEquals(setOf("shuffle", "intervalSec", "nightClockBrightness"),
        domain.applyValues(p.context, JSONObject().put("shuffle", true)
            .put("intervalSec", 45).put("nightClockBrightness", 10)))
    assertFalse(ScreensaverConfig.load(p.context).enabled)
    assertEquals(setOf("enabled"), controls(domain.schemaJson(p.context)).keys)
    assertTrue(domain.applyValues(p.context, JSONObject().put("nightClockBrightness", 99)).isEmpty())
    assertEquals(10, ScreensaverConfig.load(p.context).nightClockBrightness)

    domain.applyValues(p.context, JSONObject().put("enabled", true))
    val visible = controls(domain.schemaJson(p.context))
    assertTrue(visible.getValue("shuffle").getBoolean("value"))
    assertEquals(45, visible.getValue("intervalSec").getInt("value"))
    assertEquals(10, visible.getValue("nightClockBrightness").getInt("value"))
  }

  @Test fun sharedCalendarSchemaRemainsAvailableWhenPhotoFrameIsDisabled() {
    val p = prefs(false)
    ScreensaverConfig.setCalendarUrl(p.context, "https://calendar.example/home.ics")
    val calendar = controls(SettingsDomains.calendar.schemaJson(p.context))
    assertEquals("https://calendar.example/home.ics", calendar.getValue("url").getString("value"))
    assertTrue(calendar.containsKey("widgetOn"))
    assertEquals(setOf("enabled"), controls(domain.schemaJson(p.context)).keys)
  }

  private fun exportRegisteredSchemas() {
    val output = File("build/reports/settings-visibility").apply { mkdirs() }
    for (enabled in listOf(false, true)) {
      val p = prefs(enabled)
      File(output, "screensaver-${if (enabled) "enabled" else "disabled"}.json")
          .writeText(domain.schemaJson(p.context).toString(2))
    }
  }
}
