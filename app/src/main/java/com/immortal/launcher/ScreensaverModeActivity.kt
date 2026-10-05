/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.ui.theme.PortalPrimeTheme

class ScreensaverModeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { ScreensaverModeScreen() } }
    }
}

@Composable
private fun ScreensaverModeScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    var mode by remember { mutableStateOf(ScreensaverConfig.load(context).mode) }
    ApprovedSourcesPage("Screensaver style", onBack = { activity?.finish() }) {
        ApprovedSettingsSectionLabel("Options")
        Card {
            PickerRow("Screensaver style", mode,
                ScreensaverConfig.MODES.map { PickerOption(it, ScreensaverConfig.modeLabel(it)) }) { selected ->
                // The existing rich style picker owns this field; renderer IDs stay untouched.
                ScreensaverConfig.setMode(context, selected)
                mode = ScreensaverConfig.load(context).mode
            }
        }
        ApprovedSourcesPreviewFooter {
            context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
        }
    }
}
