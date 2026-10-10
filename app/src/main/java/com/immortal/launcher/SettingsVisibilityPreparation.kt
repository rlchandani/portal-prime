/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.SettingSpec
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsPresentation

internal data class SettingsVisibilityPreparation<S>(
    val projection: ApprovedSettingsLazyProjection<SettingSpec<S>>,
    val showAdvancedHint: Boolean,
)

/**
 * Pure preparation over a supplied snapshot and visibility predicate; no Context or persistence.
 * Cheap eligibility gates precede visibility, which is evaluated at most once per spec in this
 * preparation. The hint intentionally includes derived and screensaver-disabled Advanced entries,
 * matching the renderer's original hint scan. Exclusions apply to both rows and the hint.
 */
internal fun <S> prepareSettingsVisibility(
    domain: SettingsDomain<S>,
    snapshot: S,
    advanced: Boolean,
    exclude: Set<String> = emptySet(),
    visibleWhen: (SettingSpec<S>) -> Boolean,
): SettingsVisibilityPreparation<S> {
    val groups = LinkedHashMap<String?, MutableList<SettingSpec<S>>>()
    val screensaverDisabled = domain.id == "screensaver" &&
        snapshot is ScreensaverConfig.Settings && !snapshot.enabled
    var hint = false
    for (spec in domain.specs) {
        if (spec.key in exclude) continue
        val advancedSpec = SettingsPresentation.isAdvanced(domain.id, spec.key)
        val rowEligible = spec !is DerivedSpec<*> && (advanced || !advancedSpec) &&
            (!screensaverDisabled || spec.key == "enabled")
        val hintEligible = !advanced && !hint && advancedSpec
        if ((!rowEligible && !hintEligible) || !visibleWhen(spec)) continue
        if (hintEligible) hint = true
        if (rowEligible) {
            val section = if (advancedSpec) "Advanced options" else domain.sections[spec.key]
            groups.getOrPut(section) { mutableListOf() }.add(spec)
        }
    }
    return SettingsVisibilityPreparation(approvedSettingsLazyProjection(domain.id, groups) { it.key }, hint)
}
