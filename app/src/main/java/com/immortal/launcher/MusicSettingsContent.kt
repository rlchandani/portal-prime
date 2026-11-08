/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.settings.FollowMeSettings
import org.json.JSONObject

/** Settings › Music: this Portal's music choices, rendered from the settings registry. */
@Composable
internal fun MusicSettingsContent() {
  val context = LocalContext.current.applicationContext
  var followMe by remember(context) { mutableStateOf(FollowMeConfig.load(context)) }
  SettingsList(FollowMeSettings.domain, followMe) { key, value ->
    FollowMeSettings.domain.apply(context, JSONObject().put(key, value))
    followMe = FollowMeConfig.load(context)
  }
}
