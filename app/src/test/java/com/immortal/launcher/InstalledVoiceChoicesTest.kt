/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class InstalledVoiceChoicesTest {
  private val australian = InstalledVoiceChoice("engine-au-1", "Voice 1 · AUS · HQ", "English (Australia)")
  private val american = InstalledVoiceChoice("engine-us-2", "Voice 2 · USA · HQ", "English (United States)")
  private val romanian = InstalledVoiceChoice("piper-ro-mihai", "Romanian Mihai", "Romanian (Romania)")
  private val voices = listOf(australian, american, romanian)

  @Test fun blankSearchPreservesInstalledOrderAndKeepsDefaultOutOfTheScrollableList() {
    assertEquals(voices, filterInstalledVoices(listOf(InstalledVoiceChoice("", "Default voice")) + voices, "  "))
  }
  @Test fun searchFindsFriendlyNamesWithoutChangingTheirIdentity() {
    assertEquals(listOf(romanian), filterInstalledVoices(voices, "mIhAi"))
  }
  @Test fun searchFindsHumanReadableLanguageAndCountry() {
    assertEquals(listOf(american), filterInstalledVoices(voices, "English United"))
  }
  @Test fun allWordsMustMatchButTheirOrderDoesNotMatter() {
    assertEquals(listOf(american), filterInstalledVoices(voices, "  USA   voice  2 "))
    assertTrue(filterInstalledVoices(voices, "USA Romanian").isEmpty())
  }
  @Test fun engineIdentityCanFindASavedCustomVoice() {
    assertEquals(listOf(australian), filterInstalledVoices(voices, "engine-au-1"))
  }
  @Test fun noMatchDoesNotMutateTheInstalledListOrStoredChoice() {
    val before = voices.toList()
    assertTrue(filterInstalledVoices(voices, "not-installed-anywhere").isEmpty())
    assertEquals(before, voices)
    assertEquals(american.label, installedVoiceLabel(american.name, voices, loading = false))
  }
  @Test fun defaultLabelIsStableEvenWhileVoicesLoadOrTheEngineIsEmpty() {
    assertEquals("Default voice", installedVoiceLabel("", emptyList(), loading = true))
    assertEquals("Default voice", installedVoiceLabel("", emptyList(), loading = false))
  }
  @Test fun missingSavedVoiceIsNeverMislabeledAsDefault() {
    assertEquals("Loading voice…", installedVoiceLabel("saved-missing", voices, loading = true))
    assertEquals("Saved voice unavailable", installedVoiceLabel("saved-missing", voices, loading = false))
    assertEquals(romanian.label, installedVoiceLabel(romanian.name, voices, loading = false))
  }
}
