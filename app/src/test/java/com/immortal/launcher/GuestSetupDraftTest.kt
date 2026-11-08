/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.GuestSetupStep.BASICS
import com.immortal.launcher.GuestSetupStep.FINISH
import com.immortal.launcher.GuestSetupStep.NETWORK
import com.immortal.launcher.GuestSetupStep.PASSWORD
import com.immortal.launcher.GuestSetupStep.WELCOME
import com.immortal.launcher.GuestWifi.Security
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSetupDraftTest {
  private val fresh = GuestSetupDraft.from(GuestConfig.Settings(), password = "", doorCode = "")
  private val home = GuestNetworks.Nearby("Home", Security.WPA, -50, connected = true)

  @Test fun freshSetupStartsAtTheNetworkWithTheDefaultWelcome() {
    assertEquals(NETWORK, fresh.step)
    assertEquals(GuestBasics.DEFAULT_WELCOME, fresh.settings.welcome)
    assertEquals("Choose your Wi-Fi network.", fresh.problem(NETWORK))
  }

  @Test fun pickingANetworkFillsNameAndSecurity() {
    val d = fresh.pick(home.copy(security = Security.SAE))
    assertEquals("Home", d.settings.ssid)
    assertEquals("sae", d.settings.security)
    assertFalse(d.settings.hiddenNetwork)
    assertNull(d.problem(NETWORK))
  }

  @Test fun openNetworksSkipThePasswordStep() {
    val open = fresh.pick(home.copy(security = Security.NONE))
    assertEquals(listOf(NETWORK, BASICS, WELCOME, FINISH), open.steps)
    assertEquals(BASICS, open.next().step)
    assertEquals(NETWORK, open.next().back().step)
    assertEquals(listOf(NETWORK, PASSWORD, BASICS, WELCOME, FINISH), fresh.pick(home).steps)
  }

  @Test fun nextIsBlockedUntilTheStepIsComplete() {
    assertEquals(NETWORK, fresh.next().step)
    val d = fresh.pick(home).next()
    assertEquals(PASSWORD, d.step)
    assertEquals("Add the Wi-Fi password.", d.problem(PASSWORD))
    assertEquals(PASSWORD, d.next().step)
    assertEquals("Wi-Fi passwords have at least 8 characters.", d.withPassword("short").problem(PASSWORD))
    assertEquals(BASICS, d.withPassword("long enough").next().step)
  }

  @Test fun wepAllowsShortKeys() = assertNull(fresh.pick(home.copy(security = Security.WEP)).withPassword("abcde").problem(PASSWORD))

  @Test fun manualEntryIsTheOnlyTypedNetwork() {
    val d = fresh.manual(" Attic ", Security.WEP, hidden = true)
    assertEquals("Attic", d.settings.ssid)
    assertEquals("wep", d.settings.security)
    assertTrue(d.settings.hiddenNetwork)
  }

  @Test fun presetsToggleAndNotesAddRemove() {
    val d = fresh.togglePreset("pets").togglePreset("shoes_off").togglePreset("pets")
    assertEquals("shoes_off", d.settings.goodToKnow)
    val n = d.addNote("  Bins out Tuesday ").addNote("").addNote("Bins out Tuesday")
    assertEquals(listOf("Bins out Tuesday"), n.settings.notes)
    assertEquals(emptyList<String>(), n.removeNote("Bins out Tuesday").settings.notes)
  }

  @Test fun doorCodeNeedsFourToEightDigitsWhenOn() {
    val on = fresh.withDoorCode(on = true, code = "12")
    assertEquals("Door codes are 4 to 8 digits.", on.problem(BASICS))
    assertNull(on.withDoorCode(on = true, code = "4821").problem(BASICS))
    assertNull(on.withDoorCode(on = false, code = "12").problem(BASICS))
    assertEquals("48219999", fresh.withDoorCode(true, "48-21x9999999").doorCode)
  }

  @Test fun aMigratedFreeTextDoorCodeIsKeptUntilReplaced() {
    val legacy = GuestSetupDraft.from(GuestConfig.Settings(), "", doorCode = "4821#")
    assertTrue(legacy.doorCodeOn)
    assertNull(legacy.problem(BASICS))
    assertNull(legacy.save().doorCode)
  }

  @Test fun saveOnlyWritesChangedSecrets() {
    val saved = GuestSetupDraft.from(GuestConfig.Settings(ssid = "Home"), password = "hunter22", doorCode = "4821")
    assertNull(saved.save().password)
    assertNull(saved.save().doorCode)
    assertEquals("new-pass-1", saved.withPassword("new-pass-1").save().password)
    assertEquals("", saved.withDoorCode(on = false, code = "4821").save().doorCode)
    assertEquals("1234", saved.withDoorCode(on = true, code = "1234").save().doorCode)
  }

  @Test fun switchingToAnOpenNetworkClearsTheStoredPassword() {
    val saved = GuestSetupDraft.from(GuestConfig.Settings(ssid = "Home"), password = "hunter22", doorCode = "")
    assertEquals("", saved.pick(home.copy(ssid = "Cafe", security = Security.NONE)).save().password)
  }

  @Test fun welcomeIsCappedAndSamplesReplaceIt() {
    assertEquals(GuestBasics.MAX_WELCOME, fresh.withWelcome("w".repeat(200)).settings.welcome.length)
    assertEquals(GuestBasics.WELCOME_SAMPLES[2], fresh.withWelcome(GuestBasics.WELCOME_SAMPLES[2]).settings.welcome)
  }

  @Test fun finishNeedsEveryStepComplete() {
    assertFalse(fresh.canFinish)
    assertTrue(fresh.pick(home).withPassword("long enough").canFinish)
    assertTrue(fresh.pick(home.copy(security = Security.NONE)).canFinish)
  }

  @Test fun toStringNeverShowsSecrets() {
    val s = GuestSetupDraft.from(GuestConfig.Settings(), "hunter22", "4821").toString()
    assertFalse(s.contains("hunter22"))
    assertFalse(s.contains("4821"))
  }
}
