/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChoicePolicyTest {
  private val P = SettingsChoicePolicy

  @Test fun appearanceUsesPreviewTiles() {
    assertEquals(SettingsChoiceStyle.APPEARANCE, P.enumStyle("immortal", "settingsTheme", listOf("Light", "Dark")))
  }

  @Test fun shortSetsUseTilesAndLongOnesKeepTheList() {
    assertEquals(SettingsChoiceStyle.TILES, P.enumStyle("immortal", "clockFormat", listOf("Auto", "12-hour", "24-hour")))
    assertEquals(SettingsChoiceStyle.TILES, P.enumStyle("screensaver", "fit", listOf("Fill", "Fit")))
    val soundscapes = listOf("Off", "Rain", "Ocean waves", "Fireplace", "White noise", "Pink noise", "Brown noise")
    assertEquals(SettingsChoiceStyle.LIST, P.enumStyle("screensaver", "soundscape", soundscapes))
    assertEquals(SettingsChoiceStyle.LIST, P.enumStyle("screensaver", "presenceMode", listOf("Always on", "Follow presence")))
    assertEquals(SettingsChoiceStyle.LIST, P.enumStyle("x", "single", listOf("Only")))
  }

  @Test fun shortSetsSitInlineAndLongerOnesStack() {
    assertFalse(P.stacked(listOf("Fill", "Fit")))
    assertFalse(P.stacked(listOf("Auto", "12-hour", "24-hour")))
    assertTrue(P.stacked(listOf("Day", "3 days", "Week", "Agenda")))
    assertTrue(P.stacked(listOf("Ask first", "Automatic", "Off")))
  }

  @Test fun displayTitlesFollowTheMockAndOthersKeepTheRegistryTitle() {
    assertEquals("Time format", P.displayTitle("immortal", "clockFormat", "Clock"))
    assertEquals("Chime & voice volume", P.displayTitle("chime", "volume", "Volume"))
    assertEquals("Shuffle", P.displayTitle("screensaver", "shuffle", "Shuffle"))
  }

  @Test fun temperatureTilesUseDegreeSymbols() {
    assertEquals("°C", P.tileLabel("immortal", "weatherUnit", "c", "Celsius"))
    assertEquals("Auto", P.tileLabel("immortal", "weatherUnit", "auto", "Auto"))
    assertEquals("Fill", P.tileLabel("screensaver", "fit", "fill", "Fill"))
  }

  @Test fun intervalPresetsKeepACustomTileForOtherValues() {
    val tiles = P.presetTiles("screensaver", "intervalSec", 5, 600, 5, 30) { "${it}s" }
    assertEquals(listOf("10 s", "30 s", "1 min", "5 min", "Custom"), tiles.map { it.label })
    assertEquals(1, P.selectedTile(tiles, 30))
    assertTrue(tiles.last().custom)
  }

  @Test fun aStoredNonPresetShowsOnTheCustomTile() {
    val tiles = P.presetTiles("screensaver", "intervalSec", 5, 600, 5, 45) { "${it}s" }
    assertEquals("45 s", tiles.last().label)
    assertEquals(tiles.lastIndex, P.selectedTile(tiles, 45))
  }

  @Test fun presetsThatCoverTheRangeNeedNoCustomTile() {
    val sizes = P.presetTiles("calendar", "size", 0, 2, 1, 1) { listOf("Small", "Medium", "Large")[it] }
    assertEquals(listOf("Small", "Medium", "Large"), sizes.map { it.label })
    val sounds = P.presetTiles("chime", "sunriseVariant", 0, 1, 1, 0) { if (it == 0) "Morning" else "Rooster" }
    assertEquals(listOf("Morning", "Rooster"), sounds.map { it.label })
  }

  @Test fun sleepTimerPresetsUseMinutesAndHours() {
    val tiles = P.presetTiles("screensaver", "sleepTimerMin", 1, 240, 5, 30) { "$it min" }
    assertEquals(listOf("15 min", "30 min", "1 hour", "2 hours", "Custom"), tiles.map { it.label })
  }

  @Test fun presetsOutsideTheEditorBoundsAreDropped() {
    val tiles = P.presetTiles("sunrise", "rampMinutes", 1, 15, 5, 11) { "$it min" }
    assertEquals(listOf("10 min", "11 min"), tiles.map { it.label })
  }

  @Test fun onlyListedNumbersGetSlidersOrPresets() {
    assertTrue(P.isSlider("chime", "volume"))
    assertFalse(P.isSlider("screensaver", "intervalSec"))
    assertTrue(P.hasPresets("screensaver", "intervalSec"))
    assertFalse(P.hasPresets("screensaver", "idleSleepMin"))
  }

  @Test fun slidersSnapToTheEditorStepAndBounds() {
    assertEquals(30, P.snap(32.4f, 0, 100, 5))
    assertEquals(35, P.snap(33f, 0, 100, 5))
    assertEquals(100, P.snap(140f, 0, 100, 10))
    assertEquals(1, P.snap(-3f, 1, 15, 1))
    assertEquals(4, P.snap(4f, 4, 4, 1))
  }

  @Test fun durationLabels() {
    assertEquals("10 s", P.secondsLabel(10))
    assertEquals("90 s", P.secondsLabel(90))
    assertEquals("5 min", P.secondsLabel(300))
    assertEquals("45 min", P.minutesLabel(45))
    assertEquals("1 hour", P.minutesLabel(60))
    assertEquals("90 min", P.minutesLabel(90))
    assertEquals("2 hours", P.minutesLabel(120))
  }

  @Test fun shortSetsStackOnlyWhenTheTitleWouldWrapBesideTheTrack() {
    val labels = listOf("Light", "Dark")
    // Portal+ cards (1008 dp): a long title still sits beside the track.
    assertFalse(SettingsChoicePolicy.stacked(labels, "Temperature unit", 1008))
    // Mini cards (744 dp): a short title stays inline, a long one moves the track below it.
    assertFalse(SettingsChoicePolicy.stacked(labels, "Appearance", 744))
    assertTrue(SettingsChoicePolicy.stacked(labels, "Keep people in frame", 744))
    // Unknown width keeps the label-only rule; long sets always stack.
    assertFalse(SettingsChoicePolicy.stacked(labels, "Keep people in frame", 0))
    assertTrue(SettingsChoicePolicy.stacked(listOf("One", "Two", "Three", "Four"), "A", 1008))
  }

  @Test fun aLongHelpTextMovesTheTrackBelowInsteadOfANarrowColumn() {
    val labels = listOf("Off", "Someone", "Names")
    val help = "Someone: a soft pulse where a Portal, motion or occupancy sensor notices someone. Names: also " +
        "the people Who's there? recognised in the last 5 minutes, if they turned on Show my room on the home map."
    assertTrue(SettingsChoicePolicy.stacked(labels, "Show who's where on the map", 1008, help))
    assertFalse(SettingsChoicePolicy.stacked(labels, "Show who's where on the map", 1008, "Shown on the map."))
  }
}
