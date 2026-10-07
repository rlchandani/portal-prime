/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.util.concurrent.atomic.AtomicBoolean

private const val APPROVED_CAMERA_STREAM_ID = "approved_camera_stream_id"

internal fun cameraStreamIntent(context: Context, id: Long): Intent =
    Intent(context, CameraViewerActivity::class.java).putExtra(APPROVED_CAMERA_STREAM_ID, id)

/** Existing native RTSP player and saved-camera editor, with separate Activity destinations. */
class CameraViewerActivity : ComponentActivity() {
    private var video: VideoView? = null
    private var streamStopped = false
    private var foreground = false
    private var viewingStream = false
    private var originallyKeptAwake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamId = if (intent.hasExtra(APPROVED_CAMERA_STREAM_ID))
            intent.getLongExtra(APPROVED_CAMERA_STREAM_ID, -1L) else null
        viewingStream = streamId != null
        originallyKeptAwake = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (viewingStream) applyToolsDisplayBars(this)
        setContent {
            PortalPrimeTheme(darkTheme = viewingStream) {
                CompositionLocalProvider(LocalApprovedPalette provides
                    if (viewingStream) ApprovedToolsPalette else LocalApprovedPalette.current) {
                    CameraViewerScreen(streamId,
                        onVideoCreated = { video = it },
                        onVideoReleased = { if (video === it) video = null },
                        canPlay = { foreground && !isFinishing })
                }
            }
        }
    }

    override fun onStop() {
        foreground = false
        streamStopped = video != null
        video?.stopPlayback()
        restoreWindow()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (viewingStream) {
            applyToolsDisplayBars(this)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (streamStopped) { video?.resume(); streamStopped = false }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && viewingStream) applyToolsDisplayBars(this)
    }

    override fun onDestroy() {
        foreground = false
        video?.stopPlayback()
        restoreWindow()
        super.onDestroy()
    }

    private fun restoreWindow() {
        if (viewingStream && !originallyKeptAwake) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun CameraViewerScreen(
    streamId: Long?, onVideoCreated: (VideoView) -> Unit, onVideoReleased: (VideoView) -> Unit,
    canPlay: () -> Boolean,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    if (streamId != null) {
        val current = remember(streamId) { CameraConfig.load(context).firstOrNull { it.id == streamId } }
        if (current != null) {
            CameraLiveStream(current, onBack = { activity?.finish() },
                onVideoCreated = onVideoCreated, onVideoReleased = onVideoReleased, canPlay = canPlay)
        } else {
            // A removed camera must not fall through to a second settings host during viewing.
            BackHandler { activity?.finish() }
            Column(Modifier.fillMaxSize().background(LocalApprovedPalette.current.background)) {
                ToolsDisplayHeader("‹ Cameras", "Camera unavailable", onBack = { activity?.finish() })
                Column(Modifier.fillMaxWidth().weight(1f).padding(40.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ToolsText("This camera is no longer saved. Return to your cameras to choose another.", secondary = true)
                    ToolsButton("Back to cameras", onClick = { activity?.finish() })
                }
            }
        }
        return
    }

    SettingsScaffold(title = "Cameras", onBack = { activity?.finish() }) {
        CameraControlsContent(onDismiss = { activity?.finish() })
    }
}

/** Legacy saved-camera controls for existing callers and direct Settings launches. */
@Composable
internal fun CameraControlsContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberApprovedResume()
    var cameras by remember(context, resume) { mutableStateOf(CameraConfig.load(context)) }
    // Unsaved URLs can include credentials; keep form drafts out of saved instance state.
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("rtsp://") }
    var error by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<CameraConfig.Camera?>(null) }
    Column {
        ApprovedEditorsCollection("Your cameras", cameras, key = { it.id }) { camera ->
            ApprovedEditorsCollectionRow(camera.name, camera.url,
                onRemove = { removal = camera }, action = "View", onAction = {
                    context.startActivity(cameraStreamIntent(context, camera.id))
                })
        }
        ApprovedEditorsForm(actions = {
            ApprovedEditorsActions(error = error, onCancel = {
                name = ""; url = "rtsp://"; error = null; onDismiss()
            }) {
                // Retain the original literal RTSP predicate; no stricter URL parser or
                // required name is introduced. CameraConfig supplies the Camera fallback.
                if (!url.startsWith("rtsp://") || url.length <= 8) {
                    error = "Enter an rtsp:// stream address longer than eight characters."
                } else {
                    CameraConfig.add(context, name, url)
                    cameras = CameraConfig.load(context)
                    name = ""; url = "rtsp://"; error = null
                }
            }
        }) {
            ApprovedEditorsField("Name (optional)", name, { name = it; error = null })
            Divider()
            ApprovedEditorsField("RTSP URL", url, { url = it; error = null })
        }
    }
    removal?.let { camera ->
        ApprovedEditorsRemoveDialog(camera.name, onCancel = { removal = null }) {
            CameraConfig.remove(context, camera.id)
            cameras = CameraConfig.load(context); removal = null
        }
    }
}

@Composable
private fun CameraLiveStream(
    camera: CameraConfig.Camera, onBack: () -> Unit,
    onVideoCreated: (VideoView) -> Unit, onVideoReleased: (VideoView) -> Unit,
    canPlay: () -> Boolean,
) {
    val palette = LocalApprovedPalette.current
    var failed by remember(camera.id) { mutableStateOf(false) }
    var loading by remember(camera.id) { mutableStateOf(true) }
    var attempt by remember(camera.id) { mutableIntStateOf(0) }
    val released = remember(camera.id) { AtomicBoolean(false) }
    val canPlayNow by rememberUpdatedState(canPlay)
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(palette.background)) {
        ToolsDisplayHeader("‹ Cameras", camera.name, onBack)
        Surface(color = Color.Black, shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, palette.divider),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(40.dp)) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setMediaController(MediaController(context).also { it.setAnchorView(this) })
                            setOnPreparedListener { player ->
                                if (!released.get() && canPlayNow()) {
                                    loading = false
                                    failed = false
                                    player.isLooping = true
                                    start()
                                }
                            }
                            setOnErrorListener { _, _, _ ->
                                if (!released.get() && canPlayNow()) {
                                    loading = false
                                    failed = true
                                    stopPlayback()
                                }
                                true
                            }
                            onVideoCreated(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onReset = null,
                    onRelease = { view ->
                        released.set(true)
                        view.setOnPreparedListener(null)
                        view.setOnErrorListener(null)
                        view.stopPlayback()
                        view.setMediaController(null)
                        onVideoReleased(view)
                    },
                    update = { view ->
                        if (view.tag != attempt) {
                            view.stopPlayback()
                            view.tag = attempt
                            loading = true
                            view.setVideoPath(camera.url)
                        }
                    },
                )
                if (failed) {
                    Column(Modifier.fillMaxSize().background(Color(0xFF101C22)).padding(40.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        ToolsGlyph("cameraLens", palette.accent, Modifier.size(64.dp))
                        Text("Camera unavailable", color = palette.text, fontSize = 32.sp, lineHeight = 42.sp)
                        Text("Check the saved stream address and your local network.", color = palette.secondary,
                            fontSize = 22.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
                        ToolsButton("Try again", primary = true) { failed = false; loading = true; attempt++ }
                        ToolsButton("Back to cameras", onClick = onBack)
                    }
                } else if (loading) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101C22)), contentAlignment = Alignment.Center) {
                        ToolsStatus("Connecting to camera…")
                    }
                }
            }
        }
    }
}
