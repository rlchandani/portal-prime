/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.SettingsDomains

/** Keeps the native row in registry units; unchanged boundary steps never reach persistence. */
internal class WelcomeDurationAdapter(
    private val spec: IntSpec<WelcomeConfig.Settings> = SettingsDomains.welcome.specs
        .filterIsInstance<IntSpec<WelcomeConfig.Settings>>().single { it.key == "durationMs" },
) {
    val title: String get() = spec.title
    fun label(snapshot: WelcomeConfig.Settings): String = spec.format(spec.get(snapshot))
    fun adjust(snapshot: WelcomeConfig.Settings, increase: Boolean,
        apply: (String, Int) -> Unit, reload: () -> WelcomeConfig.Settings): WelcomeConfig.Settings {
        val current = spec.get(snapshot)
        val next = stepSettingNumber(current, if (increase) spec.step else -spec.step,
            spec.min, spec.max, spec.wrap)
        if (next == current) return snapshot
        apply(spec.key, next)
        return reload()
    }
}
