/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterSettingsScreenModelTest {
  private fun type(s: CcPinSet, digits: String) = digits.fold(s) { acc, d -> CcPinSetter.digit(acc, d) }

  @Test fun pin_enterTwice_agrees() {
    val first = CcPinSetter.submit(type(CcPinSet(), "2468"))
    assertTrue(first.confirming)
    assertEquals("Enter it again", first.title)
    assertEquals("", first.entry)
    val done = CcPinSetter.submit(type(first, "2468"))
    assertEquals("2468", done.done)
  }

  @Test fun pin_tooShort_and_mismatch() {
    val short = CcPinSetter.submit(type(CcPinSet(), "12"))
    assertFalse(short.confirming)
    assertNotNull(short.error)
    assertEquals(short.error, short.subtitle)
    val first = CcPinSetter.submit(type(CcPinSet(), "1234"))
    val wrong = CcPinSetter.submit(type(first, "4321"))
    assertFalse(wrong.confirming)
    assertNull(wrong.done)
    assertNotNull(wrong.error)
    assertNull(CcPinSetter.digit(wrong, '5').error)
  }

  @Test fun pin_limitsDigitsAndBackspace() {
    val full = type(CcPinSet(), "1234567899")
    assertEquals(ControlCenterConfig.MAX_PIN, full.entry.length)
    assertEquals("123", CcPinSetter.back(type(CcPinSet(), "1234")).entry)
    assertEquals("", CcPinSetter.back(CcPinSet()).entry)
    assertEquals("", CcPinSetter.digit(CcPinSet(), 'x').entry)
    assertFalse(type(CcPinSet(), "123").canSubmit)
    val done = CcPinSet(done = "1234")
    assertEquals(done, CcPinSetter.digit(done, '1'))
  }

  @Test fun modeStep_pinFirstConfirmOrApply() {
    val full = ControlCenterConfig.MODE_FULL
    val kids = ControlCenterConfig.MODE_KIDS
    val banners = ControlCenterConfig.MODE_BANNERS
    assertEquals(CcModeStep.NOTHING, CcSetLogic.modeStep(full, full, false))
    assertEquals(CcModeStep.SET_PIN, CcSetLogic.modeStep(full, kids, false))
    assertEquals(CcModeStep.CONFIRM, CcSetLogic.modeStep(full, kids, true))
    assertEquals(CcModeStep.CONFIRM, CcSetLogic.modeStep(full, banners, false))
    assertEquals(CcModeStep.NOTHING, CcSetLogic.modeStep(kids, full, true))
    assertTrue(CcSetLogic.modeConfirm(kids).isNotEmpty())
    assertTrue(CcSetLogic.modeConfirm(banners).isNotEmpty())
    assertEquals("", CcSetLogic.modeConfirm(full))
  }

  @Test fun steppers() {
    assertEquals(15, CcSetLogic.warnStep(10, true))
    assertEquals(5, CcSetLogic.warnStep(10, false))
    assertEquals(120, CcSetLogic.warnStep(120, true))
    assertEquals(1, CcSetLogic.warnStep(1, false))
    assertEquals(10, CcSetLogic.warnStep(7, true))
    assertEquals(8, CcSetLogic.stepped(8, 1, 4, 8))
    assertEquals(4, CcSetLogic.stepped(4, -1, 4, 8))
    assertEquals(35, CcSetLogic.stepped(30, 5, 0, 180))
  }

  @Test fun quietHours_label() {
    assertEquals("10 pm – 8 am", CcSetLogic.quietLabel(true, 22 * 60, 8 * 60))
    assertEquals("Off", CcSetLogic.quietLabel(false, 0, 0))
    assertEquals("12 am", CcSetLogic.clock(0))
    assertEquals("12:30 pm", CcSetLogic.clock(12 * 60 + 30))
    assertEquals("11:05 pm", CcSetLogic.clock(-55))
  }

  @Test fun labels_phasePlanSourceRegistry() {
    CcPhase.entries.forEach { assertTrue(CcSetLogic.phaseTag(it).first.isNotEmpty()) }
    assertEquals(CcTone.GREEN, CcSetLogic.phaseTag(CcPhase.LIVE).second)
    val plan = CcPlan(listOf(CcFloor("g", "G", listOf(CcRoom("k", "K", CcRect(0, 0, 2, 2))))))
    assertEquals("In Home Assistant", CcSetLogic.planSource(CcPlanSource.HOME_ASSISTANT, plan).first)
    assertEquals("On this Portal only", CcSetLogic.planSource(CcPlanSource.THIS_PORTAL, plan).first)
    assertEquals("No floor plan yet", CcSetLogic.planSource(CcPlanSource.HOME_ASSISTANT, CcPlan.EMPTY).first)
    assertEquals("Not loaded yet", CcSetLogic.planSource(CcPlanSource.NONE, plan).first)
    val big = CcPlan(listOf(CcFloor("g", "G", (0 until 1_400).map {
      CcRoom("r$it", "Room $it " + "n".repeat(180), CcRect(it % 40, it / 40, 1, 1))
    })))
    val kb = (CcPlanJson.encodedBytes(big) + 1023) / 1024
    assertEquals("On this Portal only" to "Too large to save in Home Assistant ($kb KB, limit 256 KB) · simplify the plan to share it",
        CcSetLogic.planSource(CcPlanSource.THIS_PORTAL, big))
    assertTrue(CcSetLogic.registryLine(null).contains("administrator"))
    assertEquals("2 floors · 3 areas · 7 entities", CcSetLogic.registryLine(EdFixture.reg))
    assertEquals("0 floors · 0 areas · 1 entity", CcSetLogic.registryLine(CcRegistry(entities = setOf("a.b"))))
    assertEquals(6, CcSetSection.entries.size)
  }
}
