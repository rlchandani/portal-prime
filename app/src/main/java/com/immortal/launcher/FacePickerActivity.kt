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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject

class FacePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PortalPrimeTheme(darkTheme = false) { FacePickerScreen() } }
    }
}

@Composable
private fun FacePickerScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
    var immortal by remember { mutableStateOf(ImmortalSettings.load(context)) }
    ApprovedSourcesPage("Photo clock faces", onBack = { activity?.finish() }) {
        ApprovedSettingsSectionLabel("Options")
        Card {
            ToggleRow("Show clock face", settings.facesEnabled) { enabled ->
                // These three legacy face fields are owned by this rich picker (no scalar spec).
                ScreensaverConfig.setFacesEnabled(context, enabled)
                settings = ScreensaverConfig.load(context)
            }
            if (settings.facesEnabled) {
                Divider()
                PickerRow("Photo clock face", settings.faceId,
                    FaceCatalog.entries.map { PickerOption(it.id, it.name) }) { id ->
                    ScreensaverConfig.setFaceId(context, id)
                    settings = ScreensaverConfig.load(context)
                }
                val entry = FaceCatalog.entryFor(settings.faceId)
                if (entry.sizes.isNotEmpty()) {
                    Divider()
                    PickerRow("Size", settings.faceSizeIndex.coerceIn(0, entry.sizes.lastIndex),
                        FaceCatalog.SIZE_LABELS.mapIndexed { index, label -> PickerOption(index, label) }) { size ->
                        ScreensaverConfig.setFaceSizeIndex(context, size)
                        settings = ScreensaverConfig.load(context)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        SettingsList(SettingsDomains.immortal, immortal,
            exclude = SettingsDomains.immortal.specs.map { it.key }.toSet() - "clockFormat") { key, value ->
            SettingsRegistry.apply(context, "immortal", JSONObject().put(key, value))
            immortal = ImmortalSettings.load(context)
        }
        // Registry groups include a 22 dp trailing gap; the approved inter-group gap is 32 dp.
        if (settings.enabled) Spacer(Modifier.height(10.dp))
        SettingsList(SettingsDomains.screensaver, settings,
            exclude = SettingsDomains.screensaver.specs.map { it.key }.toSet() - "showNowPlaying") { key, value ->
            SettingsRegistry.apply(context, "screensaver", JSONObject().put(key, value))
            settings = ScreensaverConfig.load(context)
        }
        ApprovedSourcesPreviewFooter(topGap = 12.dp) {
            context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
        }
    }
}
