/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.SettingSpec
import com.immortal.launcher.settings.SettingsDomain
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class SettingsVisibilityPreparationTest {
    // Passed through to predicates only; no Android calls or settings persistence.
    private val context = mock(Context::class.java)

    private fun <S> row(key: String, visible: (S) -> Boolean = { true }): SettingSpec<S> =
        BoolSpec(key, key, get = { true }, set = { _, _ -> }, visible = { _, s -> visible(s) })

    private fun <S> domain(
        specs: List<SettingSpec<S>>,
        id: String = "screensaver",
        sections: Map<String, String> = emptyMap(),
    ) = SettingsDomain(id, id, load = { error("Preparation must use the supplied snapshot") },
        specs = specs, sections = sections)

    private fun <S> prepare(
        domain: SettingsDomain<S>,
        snapshot: S,
        advanced: Boolean = false,
        exclude: Set<String> = emptySet(),
        calls: MutableMap<String, Int> = linkedMapOf(),
    ): SettingsVisibilityPreparation<S> = prepareSettingsVisibility(domain, snapshot, advanced, exclude) {
        calls[it.key] = calls.getOrDefault(it.key, 0) + 1
        it.visibleWhen(context, snapshot)
    }

    private fun <S> controls(result: SettingsVisibilityPreparation<S>): List<String> =
        result.projection.items.mapNotNull { it.value?.key }

    @Test fun hintSearchDoesNotRepeatEverydayOrAdvancedVisibilityChecks() {
        val specs = listOf<SettingSpec<Unit>>(row("mode"), row("fit") { false },
            row("cacheEnabled") { false }, row("batterySaver"))
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), Unit, calls = calls)

        assertEquals(listOf("mode"), controls(result))
        assertTrue(result.showAdvancedHint)
        // The original row filter plus hint scan calls all four predicates twice.
        assertEquals(mapOf("mode" to 1, "fit" to 1, "cacheEnabled" to 1, "batterySaver" to 1), calls)
    }

    @Test fun foundHintSkipsLaterAdvancedPredicatesButStillPreparesEverydayRows() {
        val specs = listOf<SettingSpec<Unit>>(row("cacheEnabled"),
            row("batterySaver") { error("Hint already found; hardware is irrelevant") }, row("mode"),
            DerivedSpec("info", get = { true }, render = true), row("fit"))
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), Unit, calls = calls)

        assertEquals(listOf("mode", "fit"), controls(result))
        assertTrue(result.showAdvancedHint)
        assertEquals(mapOf("cacheEnabled" to 1, "mode" to 1, "fit" to 1), calls)
    }

    @Test fun advancedOnChecksEligibleRowsOnceAndNeverSearchesForHint() {
        val specs = listOf<SettingSpec<Unit>>(row("mode"), row("batterySaver") { false }, row("presenceMode"),
            row("closeAppOnSleep") { error("Excluded hardware must not be checked") },
            DerivedSpec("cacheEnabled", get = { true }, render = true),
            DerivedSpec("info", get = { true }, render = true))
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), Unit, advanced = true,
            exclude = setOf("closeAppOnSleep"), calls = calls)

        assertEquals(listOf("mode", "presenceMode"), controls(result))
        assertFalse(result.showAdvancedHint)
        assertEquals(mapOf("mode" to 1, "batterySaver" to 1, "presenceMode" to 1), calls)
        assertEquals(listOf("Advanced options"),
            result.projection.items.filter { it.kind == ApprovedSettingsLazyKind.HEADER }.map { it.section })
    }

    @Test fun disabledScreensaverSkipsIrrelevantPredicatesWhenAdvancedIsOn() {
        val specs = listOf<SettingSpec<ScreensaverConfig.Settings>>(
            row("enabled"),
            row("mode") { error("Disabled screensaver row is ineligible") },
            row("batterySaver") { error("Disabled screensaver hardware is irrelevant") },
            DerivedSpec("cacheEnabled", get = { true }, render = true))
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), ScreensaverConfig.Settings(enabled = false),
            advanced = true, calls = calls)

        assertEquals(listOf("enabled"), controls(result))
        assertFalse(result.showAdvancedHint)
        assertEquals(mapOf("enabled" to 1), calls)
    }

    @Test fun disabledScreensaverStillUsesAdvancedVisibilityForHint() {
        val specs = listOf<SettingSpec<ScreensaverConfig.Settings>>(
            row("enabled"),
            row("mode") { error("Disabled everyday row cannot contribute to hint") },
            row("batterySaver"), row("cacheEnabled") { error("Hint already found") })
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), ScreensaverConfig.Settings(enabled = false), calls = calls)

        assertEquals(listOf("enabled"), controls(result))
        assertTrue(result.showAdvancedHint)
        assertEquals(mapOf("enabled" to 1, "batterySaver" to 1), calls)
    }

    @Test fun absentHintChecksEveryAdvancedCandidateOnceIncludingNonrenderedDerived() {
        val specs = listOf<SettingSpec<ScreensaverConfig.Settings>>(
            row("enabled"),
            row("mode") { error("Disabled everyday row cannot contribute to hint") },
            row("cacheEnabled") { false }, DerivedSpec("presenceMode", get = { true }),
            row("batterySaver") { false })
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), ScreensaverConfig.Settings(enabled = false), calls = calls)

        assertEquals(listOf("enabled"), controls(result))
        assertFalse(result.showAdvancedHint)
        assertEquals(mapOf("enabled" to 1, "cacheEnabled" to 1, "presenceMode" to 1,
            "batterySaver" to 1), calls)
    }

    @Test fun renderedAdvancedDerivedCanProduceHintEvenWithScreensaverDisabled() {
        val specs = listOf<SettingSpec<ScreensaverConfig.Settings>>(
            DerivedSpec("info", get = { true }, render = true),
            DerivedSpec("cacheEnabled", get = { true }, render = true),
            row("batterySaver") { error("Derived entry already established the hint") })
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), ScreensaverConfig.Settings(enabled = false), calls = calls)

        assertTrue(controls(result).isEmpty())
        assertTrue(result.showAdvancedHint)
        assertEquals(mapOf("cacheEnabled" to 1), calls)
    }

    @Test fun exclusionsApplyToBothRowsAndHintIncludingDerivedEntries() {
        val specs = listOf<SettingSpec<Unit>>(row("mode"),
            row("batterySaver") { error("Excluded hardware must not be checked") },
            DerivedSpec("cacheEnabled", get = { true }, render = true))
        for (advanced in listOf(false, true)) {
            val calls = linkedMapOf<String, Int>()
            val result = prepare(domain(specs), Unit, advanced = advanced,
                exclude = setOf("batterySaver", "cacheEnabled"), calls = calls)
            assertEquals(listOf("mode"), controls(result))
            assertFalse(result.showAdvancedHint)
            assertEquals(mapOf("mode" to 1), calls)
        }
    }

    @Test fun noAdvancedCandidatesSkipsDerivedVisibilityAndProducesNoHint() {
        val specs = listOf<SettingSpec<Unit>>(row("mode"), row("fit") { false },
            DerivedSpec("info", get = { true }, render = true))
        val calls = linkedMapOf<String, Int>()
        val result = prepare(domain(specs), Unit, calls = calls)

        assertEquals(listOf("mode"), controls(result))
        assertFalse(result.showAdvancedHint)
        assertEquals(mapOf("mode" to 1, "fit" to 1), calls)
        assertFalse(prepare(domain<Unit>(emptyList()), Unit).showAdvancedHint)
    }

    @Test fun hardwareVisibilityIsFreshOnEveryPreparation() {
        var batteryPresent = false
        val specs = listOf<SettingSpec<Unit>>(row("mode"), row("batterySaver") { batteryPresent })
        val domain = domain(specs)
        val calls = linkedMapOf<String, Int>()
        val before = prepare(domain, Unit, calls = calls)
        batteryPresent = true
        val after = prepare(domain, Unit, calls = calls)

        assertEquals(before.projection, after.projection)
        assertFalse(before.showAdvancedHint)
        assertTrue(after.showAdvancedHint)
        assertEquals(mapOf("mode" to 2, "batterySaver" to 2), calls)
    }

    @Test fun parentSnapshotPredicatesRemainAuthoritativeAndAreReevaluated() {
        val specs = listOf<SettingSpec<ScreensaverConfig.Settings>>(
            row("enabled") { it.enabled },
            row("mode") { it.source == ScreensaverConfig.SOURCE_FOLDER },
            row("presenceMode") { it.enabled && it.source == ScreensaverConfig.SOURCE_FOLDER })
        val domain = domain(specs)
        val calls = linkedMapOf<String, Int>()
        val initial = ScreensaverConfig.Settings()
        val before = prepare(domain, initial, advanced = true, calls = calls)
        val folder = prepare(domain, initial.copy(source = ScreensaverConfig.SOURCE_FOLDER),
            advanced = true, calls = calls)
        val disabled = prepare(domain, initial.copy(enabled = false), advanced = true, calls = calls)

        assertEquals(listOf("enabled"), controls(before))
        assertEquals(listOf("enabled", "mode", "presenceMode"), controls(folder))
        // Even the master row must still pass its own predicate.
        assertTrue(controls(disabled).isEmpty())
        assertEquals(mapOf("enabled" to 3, "mode" to 2, "presenceMode" to 2), calls)
    }

    @Test fun advancedHintStillHonorsEachCandidatesParentPredicate() {
        val specs = listOf<SettingSpec<ScreensaverConfig.Settings>>(
            row("enabled"), row("presenceMode") { it.enabled })
        val domain = domain(specs)
        val calls = linkedMapOf<String, Int>()
        val disabled = prepare(domain, ScreensaverConfig.Settings(enabled = false), calls = calls)
        val enabled = prepare(domain, ScreensaverConfig.Settings(enabled = true), calls = calls)

        assertEquals(listOf("enabled"), controls(disabled))
        assertEquals(listOf("enabled"), controls(enabled))
        assertFalse(disabled.showAdvancedHint)
        assertTrue(enabled.showAdvancedHint)
        assertEquals(mapOf("enabled" to 2, "presenceMode" to 2), calls)
    }

    @Test fun screensaverMasterGateRequiresBothMatchingDomainAndSnapshotType() {
        assertEquals(listOf("mode"), controls(prepare(domain(listOf(row<Unit>("mode"))), Unit)))
        val otherDomain = domain(listOf(row<ScreensaverConfig.Settings>("settingsTheme")), id = "immortal")
        assertEquals(listOf("settingsTheme"),
            controls(prepare(otherDomain, ScreensaverConfig.Settings(enabled = false))))
    }

    @Test fun projectionPreservesFirstSeenGroupsRowOrderCardEdgesAndControlIndices() {
        val specs = listOf<SettingSpec<Unit>>(row("enabled"), row("cacheEnabled"), row("mode"),
            row("showNowPlaying"), row("batterySaver") { false }, row("fit"))
        val domain = domain(specs, sections = mapOf("cacheEnabled" to "Power", "mode" to "Photos",
            "showNowPlaying" to "Playback", "fit" to "Photos"))
        val expanded = prepare(domain, Unit, advanced = true)
        assertEquals(listOf("screensaver.enabled", "screensaver:gap:\u0000",
            "screensaver:header:Advanced options", "screensaver.cacheEnabled", "screensaver:gap:Advanced options",
            "screensaver:header:Photos", "screensaver.mode", "screensaver.fit", "screensaver:gap:Photos",
            "screensaver:header:Playback", "screensaver.showNowPlaying", "screensaver:gap:Playback"),
            expanded.projection.items.map { it.id })
        assertEquals(mapOf("screensaver.enabled" to 0, "screensaver.cacheEnabled" to 3,
            "screensaver.mode" to 6, "screensaver.fit" to 7, "screensaver.showNowPlaying" to 10),
            expanded.projection.controlIndices)
        val rows = expanded.projection.items.filter { it.kind == ApprovedSettingsLazyKind.CONTROL }
        assertEquals(listOf(true, true, true, false, true), rows.map { it.firstRow })
        assertEquals(listOf(true, true, false, true, true), rows.map { it.lastRow })
        assertFalse(expanded.showAdvancedHint)
        assertTrue(expanded.projection.items.filter { it.kind == ApprovedSettingsLazyKind.HEADER }
            .none { it.firstGroup }) // The unlabelled master group is first.

        val simple = prepare(domain, Unit)
        assertEquals(listOf("enabled", "mode", "fit", "showNowPlaying"), controls(simple))
        assertEquals(listOf("Photos", "Playback"),
            simple.projection.items.filter { it.kind == ApprovedSettingsLazyKind.HEADER }.map { it.section })
        assertEquals(mapOf("screensaver.enabled" to 0, "screensaver.mode" to 3,
            "screensaver.fit" to 4, "screensaver.showNowPlaying" to 7), simple.projection.controlIndices)
        assertTrue(simple.showAdvancedHint)
    }
}
