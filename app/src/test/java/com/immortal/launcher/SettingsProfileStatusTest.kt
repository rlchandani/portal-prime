/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsProfileStatusTest {
    private val manifest = OtaManifest(packageName = "com.immortal.launcher", versionCode = 241, versionName = "1.241",
        sha256 = "0".repeat(64), size = 1, apkUrl = "https://example.invalid/a.apk", minSdk = 24, notes = "")

    @Test fun upToDateShowsTheInstalledVersion() {
        val status = settingsProfileStatus(OtaState(installedVersionName = "1.240", phase = OtaPhase.UP_TO_DATE), "x")
        assertEquals("Hearthpane 1.240 · Up to date", status.text)
        assertEquals(SettingsUpdateBadge.UP_TO_DATE, status.badge)
    }

    @Test fun availableUpdateIsCalledOut() {
        val status = settingsProfileStatus(OtaState(installedVersionName = "1.240", phase = OtaPhase.AVAILABLE,
            available = manifest), "x")
        assertEquals("Hearthpane 1.240 · Update available", status.text)
        assertEquals(SettingsUpdateBadge.AVAILABLE, status.badge)
    }

    @Test fun aKnownUpdateStaysVisibleAfterAFailedInstall() {
        val status = settingsProfileStatus(OtaState(phase = OtaPhase.FAILED, available = manifest), "1.240")
        assertEquals(SettingsUpdateBadge.AVAILABLE, status.badge)
    }

    @Test fun idleBeforeTheFirstCheckShowsOnlyTheBuildVersion() {
        val status = settingsProfileStatus(OtaState(), "1.240")
        assertEquals("Hearthpane 1.240", status.text)
        assertEquals(SettingsUpdateBadge.NONE, status.badge)
    }

    @Test fun busyPhasesSayWhatIsHappening() {
        assertEquals("Hearthpane 1.240 · Checking for updates",
            settingsProfileStatus(OtaState(phase = OtaPhase.CHECKING), "1.240").text)
        assertEquals("Hearthpane 1.240 · Updating",
            settingsProfileStatus(OtaState(phase = OtaPhase.DOWNLOADING, available = manifest), "1.240").text)
    }
}
