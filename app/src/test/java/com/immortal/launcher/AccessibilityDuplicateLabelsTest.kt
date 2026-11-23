/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit F13 (docs/quality/accessibility-audit-2026-11-20.md): a control repeated on one screen must say
 * whose it is. Each test builds the spoken names one screen shows from the same pure helpers the
 * composables call, and fails on any name heard twice.
 */
class AccessibilityDuplicateLabelsTest {
  private fun assertDistinct(screen: String, labels: List<String>) {
    val repeated = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    assertTrue("$screen repeats: $repeated", repeated.isEmpty())
  }

  @Test fun homeWidgetHeaders_nameTheirCard() {
    assertEquals("Weather settings", HomeRefinements.headerActionSpoken(HomeRefinements.HeaderAction.SETTINGS, "Weather"))
    assertEquals("Calendar settings", HomeRefinements.headerActionSpoken(HomeRefinements.HeaderAction.SETTINGS, "Calendar"))
    assertEquals("Open Routines", HomeRefinements.headerActionSpoken(HomeRefinements.HeaderAction.OPEN, "Routines"))
    val home = listOf("Weather", "Calendar", "Household list", "World Clock", "Timers").flatMap { title ->
      HomeRefinements.HeaderAction.entries.map { HomeRefinements.headerActionSpoken(it, title) }
    }
    assertDistinct("Home widget headers", home)
  }

  @Test fun spokenNamesKeepTheVisibleWord_soVoiceControlStillMatches() {
    HomeRefinements.HeaderAction.entries.forEach { action ->
      assertTrue(HomeRefinements.headerActionSpoken(action, "Weather").contains(action.label, ignoreCase = true))
    }
    listOf("Move", "Size", "Settings", "Remove").forEach { verb ->
      assertTrue(HomeRefinements.widgetEditSpoken(verb, "Weather").contains(verb, ignoreCase = true))
    }
    assertTrue(CallRoomActions.spoken("Video call", "Living Area").startsWith("Video call"))
    assertTrue(CcSpeech.alertAction("Office door is open", "Close").endsWith("Close"))
  }

  @Test fun customizeLegend_namesItsCard() {
    assertEquals("Move Weather", HomeRefinements.widgetEditSpoken("Move", "Weather"))
    assertEquals("Weather settings", HomeRefinements.widgetEditSpoken("Settings", "Weather"))
    val customize = listOf("Weather", "Calendar", "Photos").flatMap { name ->
      listOf("Move", "Size", "Settings", "Remove").map { HomeRefinements.widgetEditSpoken(it, name) }
    }
    assertDistinct("Customize Home", customize)
  }

  @Test fun callsRoomAndPhoneCards_nameTheirRoom() {
    assertEquals("Video call Living Area", CallRoomActions.spoken("Video call", "Living Area"))
    assertEquals("Audio call Living Area", CallRoomActions.spoken("Audio call", "Living Area"))
    assertEquals("Show Kitchen on Home", CallRoomActions.favouriteSpoken(favourite = false, name = "Kitchen"))
    assertEquals("Remove Kitchen from Home", CallRoomActions.favouriteSpoken(favourite = true, name = "Kitchen"))
    assertEquals("Leave a message for Priya", CallRoomActions.leaveMessageSpoken("Priya"))
    val rooms = listOf("Living Area" to DropInPolicy.CHIME, "Kitchen" to DropInPolicy.ASK, "Office" to DropInPolicy.OFF)
    val calls = rooms.flatMap { (name, drop) ->
      CallRoomActions.of(acceptsCalls = true, drop = drop).map { action ->
        CallRoomActions.spoken(when (action) {
          CallRoomAction.VIDEO -> "Video call"
          CallRoomAction.AUDIO -> "Audio call"
          CallRoomAction.DROP_IN -> CallRoomActions.dropInLabel(drop)
        }, name)
      } + CallRoomActions.favouriteSpoken(name == "Kitchen", name)
    } + listOf("Priya", "Sam").flatMap { phone ->
      listOf(CallRoomActions.spoken("Video call", phone), CallRoomActions.spoken("Audio call", phone),
          CallRoomActions.leaveMessageSpoken(phone))
    }
    assertDistinct("Calls", calls)
  }

  @Test fun settingsCollectionRows_nameTheirItem() {
    assertEquals("Remove Apple", ApprovedEditorsCopy.rowActionSpoken("Remove", "Apple"))
    assertEquals("Edit Kitchen camera", ApprovedEditorsCopy.rowActionSpoken("Edit", "Kitchen camera"))
    // The visible verb stays the start of the spoken name so voice control still matches the button.
    listOf("Remove", "Edit").forEach { verb ->
      assertTrue(ApprovedEditorsCopy.rowActionSpoken(verb, "Apple").startsWith(verb))
    }
    // One list screen: every row's "Remove" (and optional "Edit") reads distinctly by its row.
    val rows = listOf("Apple", "Microsoft", "Alphabet", "Front door camera", "Back door camera")
    val settings = rows.flatMap { title ->
      listOf(ApprovedEditorsCopy.rowActionSpoken("Remove", title), ApprovedEditorsCopy.rowActionSpoken("Edit", title))
    }
    assertDistinct("Settings collection rows", settings)
  }

  @Test fun controlCenterAlertButtons_nameTheirAlert() {
    assertEquals("Office door is open · Close", CcSpeech.alertAction("Office door is open", "Close"))
    // The room sheet's visible "Close…" (confirm follows) is spoken without the ellipsis.
    assertEquals("Garage door is open · Close", CcSpeech.alertAction("Garage door is open", "Close…"))
    val alerts = listOf("Office door is open", "Garage door is open", "Back door is open").flatMap { title ->
      listOf("Close", "Remind 10 pm").map { CcSpeech.alertAction(title, it) }
    }
    assertDistinct("Control Center alerts", alerts)
  }

  // Family's Home Assistant gives one office sensor three entities that all read the same alert.
  private fun officeDoor(n: Int) = CcAlert("binary_sensor.office_$n@7", CcSeverity.WARNING, CcKind.DOOR, "binary_sensor.office_$n",
      "Rohit's Office door is open", "Open 13 days", 7L, CcAlertAction.REMIND)

  @Test fun identicalAlertsAreNumbered_onlyWhenTheyRepeat() {
    assertEquals(listOf(", 1 of 3", "", ", 2 of 3", ", 3 of 3"), CcSpeech.repeatSuffixes(listOf("a", "b", "a", "a")))
    assertEquals(listOf("", ""), CcSpeech.repeatSuffixes(listOf("a", "b")))
    assertEquals(emptyList<String>(), CcSpeech.repeatSuffixes(emptyList()))
  }

  @Test fun overviewAttentionRowsFromOneSensorAreToldApart() {
    val garage = CcAlert("cover.garage@1", CcSeverity.WARNING, CcKind.GARAGE, "cover.garage", "Garage door is open", "Open 5 min", 1L,
        CcAlertAction.CLOSE)
    val slice = CcSideLogic.attention(listOf(officeDoor(1), officeDoor(2), officeDoor(3), garage), emptyMap(), null,
        ControlCenterConfig.Settings())
    assertEquals(listOf(", 1 of 3", ", 2 of 3", ", 3 of 3", ""), slice.rows.map { it.repeat })
    assertDistinct("Control Center overview", slice.rows.map { CcSpeech.row(it.alert.title, it.alert.body) + it.repeat })
  }

  @Test fun alertsScreenButtonsFromOneSensorAreToldApart() {
    val now = 1_000_000_000L
    val items = CcAlertRules.named((1..3).map { CcAlertRules.item(officeDoor(it), true, emptyMap(), { null }, true, now, { "2:33 pm" }) })
    assertEquals("Rohit's Office door is open · ${items[0].label}, 1 of 3", items[0].spoken)
    assertDistinct("Control Center Alerts", items.map { it.spoken.orEmpty() })
    // A single alert keeps the plain name.
    val one = CcAlertRules.named(listOf(CcAlertRules.item(officeDoor(1), true, emptyMap(), { null }, true, now, { "2:33 pm" })))
    assertEquals("Rohit's Office door is open · ${one[0].label}", one[0].spoken)
  }
}
