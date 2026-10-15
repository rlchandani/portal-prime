/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.SettingsDomains
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.`when`

class ChimeVoiceSettingsTest {
  private fun prefs() = ScreensaverTestPrefs(mapOf("spoken_voice" to "old-voice", "volume" to 70)).also {
    `when`(it.context.getSharedPreferences("immortal_chime", Context.MODE_PRIVATE)).thenReturn(it)
  }

  @Test fun installedVoiceIdentityIsAcceptedAndReadBackWithoutChangingOtherControls() {
    val prefs = prefs()
    val applied = SettingsDomains.chime.apply(prefs.context, JSONObject().put("spokenVoice", "en-us-local"))
    assertEquals(setOf("spokenVoice"), applied)
    assertEquals("en-us-local", ChimeConfig.load(prefs.context).spokenVoice)
    assertEquals(70, ChimeConfig.load(prefs.context).volume)
    assertEquals(1, prefs.writeCount)
  }

  @Test fun emptyIdentityRestoresEngineDefault() {
    val prefs = prefs()
    assertEquals(setOf("spokenVoice"), SettingsDomains.chime.apply(prefs.context, JSONObject().put("spokenVoice", "")))
    assertEquals("", ChimeConfig.load(prefs.context).spokenVoice)
  }

  @Test fun nonStringIdentityIsRejectedWithoutOverwritingSelectedVoice() {
    val prefs = prefs()
    for (invalid in listOf<Any>(false, 42, JSONObject.NULL, JSONObject().put("name", "voice"))) {
      assertTrue(SettingsDomains.chime.apply(prefs.context, JSONObject().put("spokenVoice", invalid)).isEmpty())
    }
    assertEquals("old-voice", ChimeConfig.load(prefs.context).spokenVoice)
    assertEquals(0, prefs.writeCount)
  }

  @Test fun voiceIdentityUsesTheRealRegistryOnTheWriterThread() = runBlocking {
    val prefs = prefs()
    val caller = Thread.currentThread()
    var worker: Thread? = null
    val queue = ChimeSettingsWriteQueue { key, value ->
      worker = Thread.currentThread()
      key in SettingsDomains.chime.apply(prefs.context, JSONObject().put(key, value))
    }
    queue.submit("spokenVoice", "en-gb-local")
    withTimeout(3000) { queue.awaitIdle() }
    assertNotSame(caller, worker)
    assertEquals("en-gb-local", ChimeConfig.load(prefs.context).spokenVoice)
    assertEquals(0L, queue.failureCount)
  }

  @Test fun rejectedVoiceSaveReportsFailureAndNextValidVoiceSaveRecovers() = runBlocking {
    val prefs = prefs()
    val queue = ChimeSettingsWriteQueue { key, value ->
      key in SettingsDomains.chime.apply(prefs.context, JSONObject().put(key, value))
    }
    queue.submit("spokenVoice", 42)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals("old-voice", ChimeConfig.load(prefs.context).spokenVoice)
    assertEquals(1L, queue.failureCount)
    queue.submit("spokenVoice", "en-au-local")
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals("en-au-local", ChimeConfig.load(prefs.context).spokenVoice)
    assertEquals(1L, queue.failureCount)
  }

  @Test fun customChooserOwnsPresentationWhileFlatValuesIncludeTheStoredIdentity() {
    val prefs = prefs()
    val spec = SettingsDomains.chime.specs.single { it.key == "spokenVoice" }
    val snapshot = ChimeConfig.load(prefs.context).copy(spokenTimeOn = true)
    assertFalse(spec.visibleWhen(prefs.context, snapshot))
    val values = JSONObject()
    spec.putValue(values, snapshot)
    assertEquals("old-voice", values.getString("spokenVoice"))
  }
}
