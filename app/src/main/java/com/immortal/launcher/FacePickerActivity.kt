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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.PortalPrimeTheme

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
    ApprovedSourcesPage("Photo clock faces", onBack = { activity?.finish() }) {
        FacePickerContent()
    }
}

@Composable
internal fun FacePickerContent() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = ScreensaverConfig.load(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    ApprovedSettingsSectionLabel("Photo frame clock")
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
                    FaceCatalog.SIZE_LABELS.mapIndexed { index, label -> PickerOption(index, label) },
                    applyCurrentSelection = settings.faceSizeIndex !=
                        settings.faceSizeIndex.coerceIn(0, entry.sizes.lastIndex)) { size ->
                    ScreensaverConfig.setFaceSizeIndex(context, size)
                    settings = ScreensaverConfig.load(context)
                }
            }
        }
    }
    // Time format (Display & Brightness) and Show now playing (Photo Frame) each have one home.
    ApprovedSourcesPreviewFooter(topGap = 12.dp) {
        context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
    }
}
