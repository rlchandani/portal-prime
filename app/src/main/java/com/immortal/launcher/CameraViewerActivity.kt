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
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import java.util.concurrent.atomic.AtomicBoolean

private const val APPROVED_CAMERA_STREAM_ID = "approved_camera_stream_id"

/** Existing native RTSP player and saved-camera editor, with separate Activity destinations. */
class CameraViewerActivity : ComponentActivity() {
    private var video: VideoView? = null
    private var streamStopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamId = if (intent.hasExtra(APPROVED_CAMERA_STREAM_ID))
            intent.getLongExtra(APPROVED_CAMERA_STREAM_ID, -1L) else null
        setContent {
            PortalPrimeTheme(darkTheme = false) {
                CameraViewerScreen(streamId,
                    onVideoCreated = { video = it },
                    onVideoReleased = { if (video === it) video = null })
            }
        }
    }

    override fun onStop() {
        streamStopped = video != null
        video?.stopPlayback()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (streamStopped) { video?.resume(); streamStopped = false }
    }
}

@Composable
private fun CameraViewerScreen(
    streamId: Long?, onVideoCreated: (VideoView) -> Unit, onVideoReleased: (VideoView) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    if (streamId != null) {
        val current = remember(streamId) { CameraConfig.load(context).firstOrNull { it.id == streamId } }
        if (current != null) {
            CameraLiveStream(current, onBack = { activity?.finish() },
                onVideoCreated = onVideoCreated, onVideoReleased = onVideoReleased)
        } else {
            // A removed camera must not fall through to a second settings host during viewing.
            BackHandler { activity?.finish() }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ApprovedSettingsDialog("Camera unavailable", onDismiss = { activity?.finish() },
                    help = "This camera is no longer saved. Return to your cameras to choose another.") {
                    ApprovedEditorsButton("Done") { activity?.finish() }
                }
            }
        }
        return
    }

    SettingsScaffold(title = "Cameras", onBack = { activity?.finish() }) {
        CameraControlsContent(onDismiss = { activity?.finish() })
    }
}

/** Saved camera controls shared by Tools and direct settings launches; View alone opens video. */
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
                    context.startActivity(Intent(context, CameraViewerActivity::class.java)
                        .putExtra(APPROVED_CAMERA_STREAM_ID, camera.id))
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
) {
    var failed by remember(camera.id) { mutableStateOf(false) }
    var attempt by remember(camera.id) { mutableIntStateOf(0) }
    val released = remember(camera.id) { AtomicBoolean(false) }
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setMediaController(MediaController(context).also { it.setAnchorView(this) })
                    setOnPreparedListener { player ->
                        if (!released.get()) { player.isLooping = true; start() }
                    }
                    setOnErrorListener { _, _, _ ->
                        if (!released.get()) failed = true
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
                    view.tag = attempt
                    view.setVideoPath(camera.url)
                }
            },
        )
        Box(Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 38.dp)) {
            ApprovedEditorsButton("Back", onClick = onBack)
        }
        if (failed) {
            ApprovedSettingsDialog(camera.name, onDismiss = onBack,
                help = "Camera unavailable. Check the stream address.") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ApprovedEditorsButton("Retry", primary = true) { failed = false; attempt++ }
                    ApprovedEditorsButton("Done", onClick = onBack)
                }
            }
        }
    }
}
