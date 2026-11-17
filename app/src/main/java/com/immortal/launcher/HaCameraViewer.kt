/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

private const val HA_CAMERA_ENTITY = "approved_camera_ha_entity"
private const val HA_CAMERA_TITLE = "approved_camera_ha_title"
private val PICTURE_BG = Color(0xFF101C22)

/** The full camera view for a Home Assistant camera (the doorbell card's "Open camera"). */
internal fun haCameraIntent(context: Context, entityId: String, title: String): Intent =
    Intent(context, CameraViewerActivity::class.java).putExtra(HA_CAMERA_ENTITY, entityId).putExtra(HA_CAMERA_TITLE, title)

internal fun haCameraEntity(intent: Intent): String? =
    intent.getStringExtra(HA_CAMERA_ENTITY)?.takeIf(DoorbellEntities::isCamera)

internal fun haCameraTitle(intent: Intent, entityId: String): String =
    intent.getStringExtra(HA_CAMERA_TITLE)?.takeIf { it.isNotBlank() } ?: DoorbellEntities.prettyName(entityId)

/** Live Home Assistant camera, full screen, in the camera viewer's frame (MJPEG, snapshot fallback). */
@Composable
internal fun HaCameraLiveScreen(entityId: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalApprovedPalette.current
    var frame by remember(entityId) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(entityId) { mutableStateOf(false) }
    var attempt by remember(entityId) { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) started = true
            if (event == Lifecycle.Event.ON_STOP) started = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Pictures stream only while the view is on screen.
    DisposableEffect(entityId, attempt, started) {
        if (!started) return@DisposableEffect onDispose {}
        val feed = HaCameraFeed(context, entityId, onFrame = { frame = it; failed = false }, onUnavailable = { failed = true })
        feed.start()
        onDispose { feed.stop() }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(palette.background)) {
        ToolsDisplayHeader("‹ Back", title, onBack)
        Surface(color = Color.Black, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, palette.divider),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(40.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val shown = frame
                when {
                    shown != null -> Image(shown.asImageBitmap(), contentDescription = "Live $title",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    failed -> Column(Modifier.fillMaxSize().background(PICTURE_BG),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        ToolsStatus("Camera unavailable. Check the Home Assistant connection.")
                        ToolsButton("Try again", primary = true) { failed = false; attempt++ }
                    }
                    else -> Box(Modifier.fillMaxSize().background(PICTURE_BG), contentAlignment = Alignment.Center) {
                        ToolsStatus("Connecting to camera…")
                    }
                }
            }
        }
    }
}
