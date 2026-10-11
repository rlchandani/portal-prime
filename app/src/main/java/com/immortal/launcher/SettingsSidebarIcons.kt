/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.vectorResource

// Only the eight literal, unqualified sidebar vectors are admitted. Resource tests guard
// that contract. Neither the cache nor its immutable values retain a Context or Theme.
private val settingsSidebarVectors = SettingsIconCache<Configuration, ImageVector>(
    ApprovedSettingsCategories.map { it.icon }.toSet(),
) { Configuration(it) }

/** Each composition owns its painter; only parsed vector data outlives a Settings window. */
@Composable
internal fun approvedSettingsSidebarVector(@DrawableRes id: Int): ImageVector {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return settingsSidebarVectors.get(configuration, id) {
        ImageVector.vectorResource(theme = null, res = resources, resId = it)
    }
}
