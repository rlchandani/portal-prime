/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The single send gate ([CcPolicy.refusal] via [ccSendRefusal], what [ControlCenter.send] checks):
 * sliders and dials send unconfirmed, so nothing that asks first can slip through them.
 */
class ControlCenterSendGateTest {
  private val s = ControlCenterConfig.Settings()
  private val kids = s.copy(portalMode = ControlCenterConfig.MODE_KIDS)
  private val lock = CcEntity("lock.front", "locked", mapOf("friendly_name" to "Front door"))
  private val garage = CcEntity("cover.garage", "closed", mapOf("device_class" to "garage"))
  private val valve = CcEntity("valve.main", "open")
  private val light = CcEntity("light.kitchen", "on", mapOf("brightness" to 128))
  private val thermostat = CcEntity("climate.hall", "heat", mapOf("temperature" to 20.0))
  private val speaker = CcEntity("media_player.den", "playing")
  private val house = CcEntity("scene.good_night", "x", mapOf("entity_id" to (1..8).map { "light.l$it" }))
  private val noScenesSeen: () -> Set<String> = { emptySet() }

  private fun state(settings: ControlCenterConfig.Settings = s, vararg es: CcEntity) =
      CcUiState(settings = settings, entities = es.associateBy { it.id })

  @Test fun sliderAndDialCalls_goStraightThrough() {
    val st = state(s, light, thermostat, speaker)
    assertNull(ccSendRefusal(st, CcCalls.brightness(light, 40), confirmed = false, noScenesSeen))
    assertNull(ccSendRefusal(st, CcCalls.temperature(thermostat, 21.5), confirmed = false, noScenesSeen))
    assertNull(ccSendRefusal(st, CcCalls.mediaVolume(speaker, 0.3f), confirmed = false, noScenesSeen))
  }

  @Test fun confirmRequiredCall_cannotBeSentUnconfirmed() {
    val st = state(s, lock, garage, valve, house)
    // Exactly what a slider/dial path would do: ControlCenter.send(call) with the default confirmed = false.
    assertNotNull(ccSendRefusal(st, CcCalls.unlock(lock), confirmed = false, noScenesSeen))
    assertNotNull(ccSendRefusal(st, CcCalls.coverOpen(garage), confirmed = false, noScenesSeen))
    assertNotNull(ccSendRefusal(st, CcCalls.valveClose(valve), confirmed = false, noScenesSeen))
    assertNotNull(ccSendRefusal(st, CcCalls.run(house), confirmed = false, noScenesSeen))
    // The modal / PIN answer is the confirmation.
    assertNull(ccSendRefusal(st, CcCalls.unlock(lock), confirmed = true, noScenesSeen))
    assertNull(ccSendRefusal(st, CcCalls.coverOpen(garage), confirmed = true, noScenesSeen))
  }

  @Test fun policySwitches_areHonoured() {
    val st = state(s.copy(askUnlock = false), lock, house)
    assertNull(ccSendRefusal(st, CcCalls.unlock(lock), confirmed = false, noScenesSeen))
    // A whole-house scene already run once on this Portal runs with one tap.
    assertNull(ccSendRefusal(st, CcCalls.run(house), confirmed = false) { setOf(house.id) })
  }

  @Test fun hiddenAndReadOnly_neverSend_evenConfirmed() {
    val st = state(kids.copy(kidsLockThermostat = true), lock, thermostat)
    assertEquals("hidden on this Portal", ccSendRefusal(st, CcCalls.lock(lock), confirmed = true, noScenesSeen))
    assertEquals("read-only on this Portal", ccSendRefusal(st, CcCalls.temperature(thermostat, 25.0), confirmed = false, noScenesSeen))
  }

  @Test fun unknownEntity_onlyBlocksDomainsThatAsk() {
    val st = state(s)
    assertNull(ccSendRefusal(st, CcCall("light", "turn_on", "light.gone"), confirmed = false, noScenesSeen))
    assertNotNull(ccSendRefusal(st, CcCall("lock", "unlock", "lock.gone"), confirmed = false, noScenesSeen))
    assertNull(ccSendRefusal(st, CcCall("lock", "unlock", "lock.gone"), confirmed = true, noScenesSeen))
  }

  @Test fun scenesSeen_isReadOnlyForSceneCalls() {
    var reads = 0
    val seen: () -> Set<String> = { reads++; emptySet() }
    val st = state(s, light, house)
    ccSendRefusal(st, CcCalls.brightness(light, 10), confirmed = false, seen)
    assertEquals(0, reads)
    ccSendRefusal(st, CcCalls.run(house), confirmed = false, seen)
    assertEquals(1, reads)
  }
}
