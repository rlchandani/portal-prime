/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.SettingsDomains
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.`when`

class WelcomeDurationAdapterTest {
    private class Fixture(initialMs: Int) {
        val prefs = ScreensaverTestPrefs(mapOf("duration_ms" to initialMs))
        init {
            `when`(prefs.context.getSharedPreferences("immortal_welcome", Context.MODE_PRIVATE))
                .thenReturn(prefs)
        }
        val adapter = WelcomeDurationAdapter()
        var reloads = 0
        fun load() = SettingsDomains.welcome.load(prefs.context)
        fun adjust(snapshot: WelcomeConfig.Settings, increase: Boolean) = adapter.adjust(
            snapshot, increase,
            apply = { key, value -> SettingsDomains.welcome.apply(prefs.context, JSONObject().put(key, value)) },
            reload = { reloads++; load() })
    }

    @Test fun rightFrom3200DisplaysAndPersists3400WithoutWholeSecondTruncation() {
        val f = Fixture(3200)
        val initial = f.load()
        assertEquals("3.2s", f.adapter.label(initial))
        val result = f.adjust(initial, increase = true)
        assertEquals(3400, result.durationMs)
        assertEquals("3.4s", f.adapter.label(result))
        assertEquals(3400, f.load().durationMs)
        assertEquals(1, f.prefs.writeCount)
        assertEquals(1, f.reloads)
    }

    @Test fun firstControlDecreases3200To3000Then2800() {
        val f = Fixture(3200)
        val first = f.adjust(f.load(), increase = false)
        assertEquals(3000, first.durationMs)
        assertEquals("3.0s", f.adapter.label(first))
        val second = f.adjust(first, increase = false)
        assertEquals(2800, second.durationMs)
        assertEquals("2.8s", f.adapter.label(second))
        assertEquals(2, f.prefs.writeCount)
    }

    @Test fun outwardStepsAtBothBoundsDoNotWriteOrReload() {
        listOf(1000 to false, 10000 to true).forEach { (ms, increase) ->
            val f = Fixture(ms)
            val snapshot = f.load()
            assertSame(snapshot, f.adjust(snapshot, increase))
            assertEquals(ms, f.load().durationMs)
            assertEquals(0, f.prefs.writeCount)
            assertEquals(0, f.reloads)
        }
    }

    @Test fun stepsReachBothBoundariesWithoutRoundingTheCurrentMilliseconds() {
        val lower = Fixture(1100)
        assertEquals(1000, lower.adjust(lower.load(), false).durationMs)
        val upper = Fixture(9900)
        assertEquals(10000, upper.adjust(upper.load(), true).durationMs)
        val fractional = Fixture(3250)
        assertEquals(3450, fractional.adjust(fractional.load(), true).durationMs)
    }

    @Test fun adapterUsesSuppliedSpecGetterFormatStepAndBounds() {
        val spec = IntSpec<WelcomeConfig.Settings>("custom", "Custom duration",
            get = { it.durationMs + 50 }, set = { _, _ -> },
            min = 2500, max = 3500, step = 75, format = { "$it exact" })
        val adapter = WelcomeDurationAdapter(spec)
        val snapshot = WelcomeConfig.Settings(durationMs = 3200)
        assertEquals("3250 exact", adapter.label(snapshot))
        var applied: Pair<String, Int>? = null
        var readBack = snapshot
        val result = adapter.adjust(snapshot, true,
            apply = { key, value ->
                applied = key to value
                readBack = snapshot.copy(durationMs = value - 50, showDate = false)
            }, reload = { readBack })
        assertEquals("custom" to 3325, applied)
        assertSame(readBack, result)
        assertFalse(result.showDate)
        assertEquals("3325 exact", adapter.label(result))
        adapter.adjust(WelcomeConfig.Settings(durationMs = 3425), true,
            apply = { key, value -> applied = key to value }, reload = { readBack })
        assertEquals("custom" to 3500, applied)
        adapter.adjust(WelcomeConfig.Settings(durationMs = 2455), false,
            apply = { key, value -> applied = key to value }, reload = { readBack })
        assertEquals("custom" to 2500, applied)
    }
}
