/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Update state shown on the sidebar profile card. */
internal enum class SettingsUpdateBadge { NONE, UP_TO_DATE, AVAILABLE, BUSY }

internal data class SettingsProfileStatus(val text: String, val badge: SettingsUpdateBadge)

/**
 * "Hearthpane 1.240 · Up to date" / "· Update available", from the OTA updater's snapshot.
 * Pure; [fallbackVersion] is the installed build when the updater has not reported yet.
 */
internal fun settingsProfileStatus(state: OtaState, fallbackVersion: String): SettingsProfileStatus {
    val version = state.installedVersionName.ifBlank { fallbackVersion }
    val badge = when {
        state.phase == OtaPhase.DOWNLOADING || state.phase == OtaPhase.INSTALLING -> SettingsUpdateBadge.BUSY
        state.phase == OtaPhase.AVAILABLE || state.available != null -> SettingsUpdateBadge.AVAILABLE
        state.phase == OtaPhase.UP_TO_DATE -> SettingsUpdateBadge.UP_TO_DATE
        state.phase == OtaPhase.CHECKING -> SettingsUpdateBadge.BUSY
        else -> SettingsUpdateBadge.NONE
    }
    val label = when (badge) {
        SettingsUpdateBadge.UP_TO_DATE -> "Up to date"
        SettingsUpdateBadge.AVAILABLE -> "Update available"
        SettingsUpdateBadge.BUSY -> if (state.phase == OtaPhase.CHECKING) "Checking for updates" else "Updating"
        SettingsUpdateBadge.NONE -> null
    }
    val name = listOf(Brand.NAME, version).filter { it.isNotBlank() }.joinToString(" ")
    return SettingsProfileStatus(listOfNotNull(name, label).joinToString(" · "), badge)
}
