/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Download compatibility belongs to the catalog APK, not the installed APK. */
internal enum class StorePrimaryAction {
    OPEN, UPDATE, GET, WAIT, CHECKING, INCOMPATIBLE;
    val enabled: Boolean get() = this == OPEN || this == UPDATE || this == GET
}

internal fun storePrimaryAction(
    installed: Boolean?, compatible: Boolean, updateAvailable: Boolean, busy: Boolean,
): StorePrimaryAction = when {
    busy -> StorePrimaryAction.WAIT
    installed == null -> StorePrimaryAction.CHECKING
    installed && (!compatible || !updateAvailable) -> StorePrimaryAction.OPEN
    !compatible -> StorePrimaryAction.INCOMPATIBLE
    installed && updateAvailable -> StorePrimaryAction.UPDATE
    else -> StorePrimaryAction.GET
}
