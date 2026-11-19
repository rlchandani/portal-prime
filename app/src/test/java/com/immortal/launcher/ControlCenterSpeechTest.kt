/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a screen reader hears for the overview's single nodes ([CcSpeech]). */
class ControlCenterSpeechTest {
  @Test fun plan_listsFloorLensAndEachRoomsCaption() {
    assertEquals("Ground floor plan, live view. Kitchen, 71°, 1 light on. Pantry. Tap a room to open it",
        CcSpeech.plan("Ground", CcLens.LIVE, listOf("Kitchen" to "71° · 1 light on", "Pantry" to "")))
    assertEquals("Upper floor plan, climate view. Office, 70°, 45%. Tap a room to open it",
        CcSpeech.plan("Upper", CcLens.CLIMATE, listOf("Office" to "70° · 45%")))
  }

  @Test fun legend_saysWhatTheColoursMean() {
    assertEquals("Legend: 1 light on, amber open, blue motion, green locked", CcSpeech.legend(CcLegend(CcLens.LIVE, lightsOn = 1)))
    assertEquals("Legend: 3 lights on, amber open, blue motion, green locked", CcSpeech.legend(CcLegend(CcLens.LIVE, lightsOn = 3)))
    assertEquals("Legend: blue 16° to orange 24°, Heating to 21°",
        CcSpeech.legend(CcLegend(CcLens.CLIMATE, cold = "16°", warm = "24°", climate = "Heating to 21°")))
  }

  @Test fun headerPills() {
    assertEquals("Nobody home", CcSpeech.people(emptyList(), everyone = 2))
    assertEquals("2 home: Ana, Ben", CcSpeech.people(listOf("Ana", "Ben"), everyone = 3))
    assertEquals("Alarm Armed · Away", CcSpeech.alarm(alarmLabel("armed_away")))
  }

  @Test fun tilesAndRows() {
    assertEquals("Good night. Walks the house", CcSpeech.scene("Good night", "Walks the house"))
    assertEquals("Movie", CcSpeech.scene("Movie", ""))
    assertEquals("Garage door open. Open for 12 min", CcSpeech.row("Garage door open", "Open for 12 min"))
    assertEquals("Everything's fine", CcSpeech.row("Everything's fine", ""))
  }
}
