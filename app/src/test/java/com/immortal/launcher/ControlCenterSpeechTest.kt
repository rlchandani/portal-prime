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
    assertEquals("Upper floor plan, energy view. Office, 0.4 kW. Tap a room to open it",
        CcSpeech.plan("Upper", CcLens.ENERGY, listOf("Office" to "0.4 kW")))
  }

  @Test fun legend_saysWhatTheColoursMean() {
    assertEquals("Legend: 1 light on, amber open, blue motion, green locked", CcSpeech.legend(CcLegend(CcLens.LIVE, lightsOn = 1)))
    assertEquals("Legend: 3 lights on, amber open, blue motion, green locked", CcSpeech.legend(CcLegend(CcLens.LIVE, lightsOn = 3)))
    assertEquals("Legend: blue 16° to orange 24°, Heating to 21°",
        CcSpeech.legend(CcLegend(CcLens.CLIMATE, cold = "16°", warm = "24°", climate = "Heating to 21°")))
    assertEquals("Legend: solar, battery, grid and rooms by colour", CcSpeech.legend(CcLegend(CcLens.ENERGY)))
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

  @Test fun energyNow_readsUnitsAndUnknowns() {
    assertEquals("Energy now: using 1.2 kilowatts, solar 0.5 kilowatts, battery 80%, grid unknown. Opens Energy",
        CcSpeech.energyNow("1.2", "0.5", "80%", "—"))
    assertEquals("Energy now: using unknown, solar unknown, battery unknown, grid 0.3 kilowatts. Opens Energy",
        CcSpeech.energyNow("—", "—", "—", "0.3"))
  }
}
