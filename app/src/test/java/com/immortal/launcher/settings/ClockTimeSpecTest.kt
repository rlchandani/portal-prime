/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher.settings

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class ClockTimeSpecTest {
    private val context = mock(Context::class.java)

    @Test fun explicitTimeFlagIsExposedToSchemaWithoutChangingIntegerConstraints() {
        var applied: Int? = null
        val spec = IntSpec<Int>("start", "Start", get = { it }, set = { _, v -> applied = v },
            min = 0, max = 1439, step = 15, wrap = true, timeOfDay = true)
        assertTrue(spec.timeOfDay)
        val schema = spec.metaJson(context, 487)
        assertTrue(schema.optBoolean("timeOfDay"))
        assertEquals("int", schema.getString("type"))
        assertEquals(487, schema.getInt("value"))
        assertEquals(0, schema.getInt("min"))
        assertEquals(1439, schema.getInt("max"))
        assertEquals(15, schema.getInt("step"))
        assertTrue(schema.getBoolean("wrap"))
        assertTrue(spec.applyFrom(context, JSONObject().put("start", 487)))
        assertEquals(487, applied)
        assertTrue(spec.applyFrom(context, JSONObject().put("start", -15)))
        assertEquals(-15, applied)
    }

    @Test fun titlesAndWrappingDoNotOptOrdinaryIntegersIntoTimeEditing() {
        val spec = IntSpec<Int>("overnightStartMin", "Clock time", get = { it }, set = { _, _ -> },
            min = 0, max = 1439, wrap = true)
        assertFalse(spec.timeOfDay)
        assertFalse(spec.metaJson(context, 0).optBoolean("timeOfDay"))
        assertFalse(spec.metaJson(context, 0).has("timeOfDay"))
    }
}
