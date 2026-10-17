/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.ChimeConfig
import com.immortal.launcher.ScreensaverConfig
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class ClockTimeDomainsTest {
    private val context = mock(Context::class.java)

    @Test fun onlyTheFourSelectedFieldsOptIntoClockEditing() {
        val flagged = SettingsDomains.all.flatMap { domain ->
            domain.specs.filterIsInstance<IntSpec<*>>().filter { it.timeOfDay }
                .map { "${domain.id}.${it.key}" }
        }.toSet()
        assertEquals(setOf("screensaver.overnightStartMin", "screensaver.overnightEndMin",
            "chime.quietStartMin", "chime.quietEndMin"), flagged)
    }

    @Test fun schemaKeepsTimeLabelsConstraintsAndVisibilityGates() {
        val screensaver = ScreensaverConfig.Settings(overnightEnabled = true,
            overnightStartMin = 487, overnightEndMin = 1439)
        val chime = ChimeConfig.Settings(quietHoursOn = true, quietStartMin = 0, quietEndMin = 720)
        checkDomain(SettingsDomains.screensaver, screensaver, screensaver.copy(overnightEnabled = false),
            mapOf("overnightStartMin" to "08:07", "overnightEndMin" to "23:59"), 15)
        checkDomain(SettingsDomains.chime, chime, chime.copy(quietHoursOn = false),
            mapOf("quietStartMin" to "00:00", "quietEndMin" to "12:00"), 30)
    }

    private fun <S> checkDomain(domain: SettingsDomain<S>, shown: S, hidden: S,
        displays: Map<String, String>, step: Int) {
        val specs = domain.specs.filter { it.key in displays }
        val exposed = SettingsDomain(domain.id, domain.title, load = { shown }, specs = specs)
            .schemaJson(context).getJSONArray("controls")
        assertEquals(2, exposed.length())
        for (i in 0 until exposed.length()) {
            val meta = exposed.getJSONObject(i)
            assertTrue(meta.getBoolean("timeOfDay"))
            assertEquals(displays.getValue(meta.getString("key")), meta.getString("display"))
            assertEquals(0, meta.getInt("min"))
            assertEquals(1439, meta.getInt("max"))
            assertEquals(step, meta.getInt("step"))
            assertTrue(meta.getBoolean("wrap"))
        }
        assertEquals(0, SettingsDomain(domain.id, domain.title, load = { hidden }, specs = specs)
            .schemaJson(context).getJSONArray("controls").length())
    }
}
