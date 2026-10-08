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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.settings.EnumSpec
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.settings.SettingsPresentation
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject

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
    ApprovedSourcesPage("Screensaver style", onBack = { activity?.finish() }) {
        ScreensaverModeContent()
    }
}

@Composable
internal fun ScreensaverModeContent() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val domain = SettingsDomains.screensaver
    val spec = domain.specs.first { it.key == "mode" } as EnumSpec<ScreensaverConfig.Settings>
    var mode by remember { mutableStateOf(spec.get(domain.load(context))) }
    val advanced = rememberApprovedAdvanced()
    var advancedConsent by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mode = spec.get(domain.load(context))
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    ApprovedSettingsSectionLabel("Options")
    Card {
        PickerRow(spec.title, mode,
            SettingsPresentation.visibleEnumOptions(domain.id, spec.key, spec.options, mode, advanced)
                .map { (value, label) -> PickerOption(value, label) }) { selected ->
            domain.apply(context, JSONObject().put(spec.key, selected))
            mode = spec.get(domain.load(context))
        }
    }
    if (!advanced) ApprovedSettingsTextButton("More styles in Advanced settings") {
        advancedConsent = true
    }
    if (advancedConsent) ApprovedSettingsAdvancedConsent(
        onEnable = {
            SettingsRegistry.apply(context, "immortal", JSONObject().put("advancedSettings", true))
            advancedConsent = false
        },
        onCancel = { advancedConsent = false },
    )
    ApprovedSourcesPreviewFooter {
        context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
    }
}
