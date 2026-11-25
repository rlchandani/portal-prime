/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** One physical thing exposed as several HA entities must surface as a single alert row. */
class ControlCenterAlertsDedupeTest {
  private val min = 60_000L
  private val now = 100 * min
  private fun map(vararg e: CcEntity) = e.associateBy { it.id }
  private fun door(id: String) =
      CcEntity(id, "on", mapOf("device_class" to "door", "friendly_name" to "Rohit's Office door"), now - 60 * min)

  private fun alert(id: String, title: String, body: String, sev: CcSeverity = CcSeverity.WARNING, kind: CcKind = CcKind.DOOR) =
      CcAlert(id, sev, kind, "binary_sensor.$id", title, body, now, CcAlertAction.REMIND)

  @Test fun identicalRows_collapseToOne() {
    val rows = listOf(
        alert("a", "Rohit's Office door is open", "Open 1 h 0 min"),
        alert("b", "Rohit's Office door is open", "Open 1 h 0 min"),
        alert("c", "Rohit's Office door is open", "Open 1 h 0 min"),
    )
    val deduped = CcAlerts.dedupe(rows)
    assertEquals(1, deduped.size)
    // The first survivor stands for the group, keeping its own id so dismiss/resolve still works.
    assertEquals("a", deduped[0].id)
  }

  @Test fun differentBodyOrTitle_staysSeparate() {
    val rows = listOf(
        alert("a", "Office door is open", "Open 1 h 0 min"),
        alert("b", "Office door is open", "Open 2 h 0 min"),   // different elapsed → different row
        alert("c", "Kitchen door is open", "Open 1 h 0 min"),  // different room → different row
    )
    assertEquals(3, CcAlerts.dedupe(rows).size)
  }

  @Test fun differentSeverity_staysSeparate() {
    val rows = listOf(
        alert("a", "Smoke", "Hall", sev = CcSeverity.CRITICAL, kind = CcKind.SMOKE),
        alert("b", "Smoke", "Hall", sev = CcSeverity.WARNING, kind = CcKind.SMOKE),
    )
    assertEquals(2, CcAlerts.dedupe(rows).size)
  }

  @Test fun active_collapsesThreeIdenticalDoorSensors() {
    val alerts = CcAlerts.active(
        map(door("binary_sensor.office_a"), door("binary_sensor.office_b"), door("binary_sensor.office_c")),
        now, warnAfterMin = 10)
    assertEquals(1, alerts.size)
    assertEquals(CcSeverity.WARNING, alerts[0].severity)
  }
}
