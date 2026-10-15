/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class StorePrimaryActionTest {
    private fun action(installed: Boolean?, compatible: Boolean = true,
        update: Boolean = false, busy: Boolean = false) =
        storePrimaryAction(installed, compatible, update, busy)

    @Test fun installedOlderAppRemainsOpenableWhenTheCatalogApkNeedsNewerAndroid() {
        assertEquals(StorePrimaryAction.OPEN, action(true, compatible = false))
    }
    @Test fun incompatibleUpdateDoesNotBlockOpeningTheInstalledVersion() {
        assertEquals(StorePrimaryAction.OPEN, action(true, compatible = false, update = true))
    }
    @Test fun busyInstallKeepsItsProgressActionEvenIfCatalogMetadataChanges() {
        assertEquals(StorePrimaryAction.WAIT, action(true, compatible = false, update = true, busy = true))
    }
    @Test fun unknownInstalledStateIsResolvedBeforeDeclaringAnAppUnavailable() {
        assertEquals(StorePrimaryAction.CHECKING, action(null, compatible = false))
    }
    @Test fun absentIncompatibleAppCannotBeDownloaded() {
        assertEquals(StorePrimaryAction.INCOMPATIBLE, action(false, compatible = false))
    }
    @Test fun compatibleInstalledUpdateKeepsUpdateAction() {
        assertEquals(StorePrimaryAction.UPDATE, action(true, update = true))
    }
    @Test fun compatibleInstalledAppKeepsOpenAction() {
        assertEquals(StorePrimaryAction.OPEN, action(true))
    }
    @Test fun compatibleAbsentAppKeepsGetAction() {
        assertEquals(StorePrimaryAction.GET, action(false))
    }
    @Test fun unknownInstalledStateCannotInstallOrLaunch() {
        assertFalse(action(null).enabled)
    }
    @Test fun busyActionsCannotDispatchDownloadsOrLaunches() {
        for (installed in listOf(null, false, true))
            assertFalse(action(installed, busy = true).enabled)
    }
    @Test fun onlyActionableDecisionsAreEnabled() {
        assertEquals(setOf(StorePrimaryAction.OPEN, StorePrimaryAction.UPDATE, StorePrimaryAction.GET),
            StorePrimaryAction.values().filter { it.enabled }.toSet())
    }
    @Test fun incompatibleAbsentAppRemainsBlockedDespiteUpdateMetadata() {
        assertFalse(action(false, compatible = false, update = true).enabled)
    }
}
