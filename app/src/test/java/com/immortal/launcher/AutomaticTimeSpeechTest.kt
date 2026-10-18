/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class AutomaticTimeSpeechTest {
  private val on = ChimeConfig.Settings(spokenTimeOn = true, quietHoursOn = false, volume = 70,
      spokenVoice = "initial")

  @Test fun disabledTimeDoesNotCreateAnEngine() {
    assertNull(AutomaticTimeSpeech({ on.copy(spokenTimeOn = false) }, { 720 }).playbackSettings())
  }
  @Test fun quietTimeDoesNotCreateAnEngine() {
    assertNull(AutomaticTimeSpeech({ on.copy(quietHoursOn = true) }, { 23 * 60 }).playbackSettings())
  }
  @Test fun disablingWhileEngineStartsCancelsInsteadOfSpeaking() {
    var settings = on
    val gate = AutomaticTimeSpeech({ settings }, { 720 })
    assertEquals(on, gate.playbackSettings())
    settings = on.copy(spokenTimeOn = false)
    var cancelled = 0; var delivered = 0
    gate.deliverOrCancel({ cancelled++ }, { delivered++ })
    assertEquals(1, cancelled); assertEquals(0, delivered)
  }
  @Test fun enteringQuietHoursWhileEngineStartsCancels() {
    var minute = 21 * 60 + 59
    val gate = AutomaticTimeSpeech({ on.copy(quietHoursOn = true) }, { minute })
    assertNotNull(gate.playbackSettings())
    minute = 22 * 60
    var cancelled = 0; var delivered = 0
    gate.deliverOrCancel({ cancelled++ }, { delivered++ })
    assertEquals(1, cancelled); assertEquals(0, delivered)
  }
  @Test fun addingQuietHoursWhileEngineStartsCancels() {
    var settings = on
    val gate = AutomaticTimeSpeech({ settings }, { 23 * 60 })
    assertNotNull(gate.playbackSettings())
    settings = on.copy(quietHoursOn = true)
    var cancelled = 0
    gate.deliverOrCancel({ cancelled++ }, { fail("Must not deliver") })
    assertEquals(1, cancelled)
  }
  @Test fun mutingWhileEngineStartsCancels() {
    var settings = on
    val gate = AutomaticTimeSpeech({ settings }, { 720 })
    assertNotNull(gate.playbackSettings())
    settings = on.copy(volume = 0)
    var cancelled = 0
    gate.deliverOrCancel({ cancelled++ }, { fail("Must not deliver") })
    assertEquals(1, cancelled)
  }
  @Test fun currentVoiceAndLowerVolumeAreUsedWhenReady() {
    var settings = on
    val gate = AutomaticTimeSpeech({ settings }, { 720 })
    assertNotNull(gate.playbackSettings())
    settings = on.copy(volume = 12, spokenVoice = "new")
    var received: ChimeConfig.Settings? = null
    gate.deliverOrCancel({ fail("Must deliver") }, { received = it })
    assertEquals(settings, received)
    assertEquals(70, on.volume); assertEquals("initial", on.spokenVoice)
  }
  @Test fun quietWindowBoundariesAndMatchingEndpointsRetainExistingMeaning() {
    val overnight = on.copy(quietHoursOn = true)
    assertNull(AutomaticTimeSpeech({ overnight }, { 22 * 60 }).playbackSettings())
    assertNull(AutomaticTimeSpeech({ overnight }, { 8 * 60 - 1 }).playbackSettings())
    assertNotNull(AutomaticTimeSpeech({ overnight }, { 8 * 60 }).playbackSettings())
    val noWindow = overnight.copy(quietStartMin = 0, quietEndMin = 0)
    assertNotNull(AutomaticTimeSpeech({ noWindow }, { 0 }).playbackSettings())
  }
}
