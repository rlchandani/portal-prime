/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.DoorbellConfig
import com.immortal.launcher.DoorbellEntities
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** Doorbell settings: registered on Settings › Home Assistant, validated, every field covered. */
class DoorbellSettingsTest {
  private val domain = DoorbellSettings.domain
  private val ctx: Context = mock(Context::class.java)

  @Test fun settingsCoverEveryPersistedField() {
    val fields = DoorbellConfig.Settings::class.java.declaredFields.map { it.name }.filterNot { it.contains('$') }.toSet()
    assertEquals(fields, domain.specs.map { it.key }.toSet())
  }

  @Test fun registeredOnTheHomeAssistantPage() {
    assertTrue(SettingsDomains.all.any { it.id == "doorbell" })
    listOf("trigger", "camera", "motion", "chime", "cooldownSec", "listen").forEach {
      assertEquals("ha", com.immortal.launcher.settingsControlHome("doorbell.$it"))
    }
  }

  @Test fun defaults() {
    val flat = domain.flatJson(requireNotNull(domain.defaults)())
    assertEquals(true, flat.getBoolean("listen"))
    assertEquals(true, flat.getBoolean("chime"))
    assertEquals(30, flat.getInt("cooldownSec"))
    assertEquals("", flat.getString("trigger"))
  }

  @Test fun entityGuards() {
    assertTrue(DoorbellEntities.validTriggerSetting("event.front_doorbell"))
    assertTrue(DoorbellEntities.validTriggerSetting("binary_sensor.doorbell"))
    assertTrue(DoorbellEntities.validTriggerSetting(""))
    assertFalse(DoorbellEntities.validTriggerSetting("camera.front_door"))
    assertFalse(DoorbellEntities.validTriggerSetting("event.Front Door"))
    assertTrue(DoorbellEntities.validCameraSetting("camera.front_door"))
    assertFalse(DoorbellEntities.validCameraSetting("event.front_doorbell"))
    assertTrue(DoorbellEntities.validMotionSetting("binary_sensor.a, binary_sensor.b"))
    assertTrue(DoorbellEntities.validMotionSetting(""))
    assertFalse(DoorbellEntities.validMotionSetting("binary_sensor.a, sensor.b"))
    assertFalse(DoorbellEntities.validMotionSetting((1..7).joinToString(",") { "binary_sensor.m$it" }))
  }

  @Test fun motionListIsCleaned() {
    assertEquals(listOf("binary_sensor.a", "binary_sensor.b"),
        DoorbellEntities.motionList(" binary_sensor.a,binary_sensor.b  binary_sensor.a, nope "))
  }

  @Test fun invalidValuesAreRejectedBeforeAnyWrite() {
    val body = JSONObject().put("trigger", "light.porch").put("camera", "event.x").put("motion", "sensor.power")
        .put("cooldownSec", 5).put("chime", "yes")
    assertTrue(domain.applyValues(ctx, body).isEmpty())
    assertTrue(domain.applyValues(ctx, JSONObject().put("cooldownSec", DoorbellConfig.MAX_COOLDOWN_SEC + 10)).isEmpty())
  }

  @Test fun prettyNames() {
    assertEquals("Front door", DoorbellEntities.prettyName("camera.front_door"))
    assertEquals("Camera", DoorbellEntities.prettyName("camera."))
  }
}
