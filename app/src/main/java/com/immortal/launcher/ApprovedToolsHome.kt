/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Memory-only drafts and one serialized local-storage worker, independent of network work. */
private class ToolsCameraDraft : ViewModel() {
    var name by mutableStateOf("")
    var url by mutableStateOf("rtsp://")
    var error by mutableStateOf<String?>(null)
    var cameras by mutableStateOf<List<CameraConfig.Camera>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var operationError by mutableStateOf<String?>(null)
        private set
    var adding by mutableStateOf(false)
        private set
    var removal by mutableStateOf<CameraConfig.Camera?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private var refreshJob: Job? = null
    private var addSession = 0
    private var closed = false

    fun clear() { name = ""; url = "rtsp://"; error = null }

    fun openAdd() { addSession++; adding = true; operationError = null }
    fun dismissAdd() { addSession++; adding = false }

    fun refresh(context: Context) {
        // At most one read and one explicitly accepted mutation can be pending.
        if (closed || refreshJob?.isActive == true) return
        loading = true
        refreshJob = scope.launch {
            try {
                operations.withLock {
                    val snapshot = withContext(Dispatchers.IO) { CameraConfig.load(context) }
                    cameras = snapshot
                    loadError = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loadError = "Couldn’t load your saved cameras."
            } finally {
                if (!closed) loading = false
            }
        }
    }

    fun add(context: Context) {
        if (busy || closed) return
        error = toolsCameraAddressError(url)
        if (error != null) return
        val submittedName = name
        val submittedUrl = url
        val session = addSession
        mutate("Couldn’t add your camera. Try again.", {
            CameraConfig.addAndLoad(context, submittedName.trim(), submittedUrl.trim())
        }) {
            // A completed save must not erase a draft edited while the write was running.
            if (name == submittedName && url == submittedUrl) clear()
            if (addSession == session) adding = false
        }
    }

    fun remove(context: Context) {
        if (busy || closed) return
        val id = removal?.id ?: return
        removal = null
        mutate("Couldn’t remove your camera. Try again.", { CameraConfig.removeAndLoad(context, id) })
    }

    private fun mutate(
        message: String, write: () -> List<CameraConfig.Camera>, onSaved: () -> Unit = {},
    ) {
        busy = true
        operationError = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Once Add/Remove is accepted, finish that local transaction even if the
                // host closes. Only live VM state receives its result; no UI callbacks leak.
                withContext(NonCancellable) {
                    operations.withLock {
                        val snapshot = withContext(Dispatchers.IO) { write() }
                        if (!closed) {
                            cameras = snapshot
                            loadError = null
                            onSaved()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed) operationError = message
            } finally {
                if (!closed) busy = false
            }
        }
    }

    fun close() {
        closed = true
        scope.cancel()
        clear()
        cameras = null
        removal = null
        adding = false
    }

    override fun onCleared() { close() }
}

private val ToolsCameraDraftFactory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ToolsCameraDraft() as T
}

private tailrec fun Context.toolsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.toolsActivity()
    else -> null
}

/** Parse only; this never discovers or contacts a camera, nor includes credentials in errors. */
internal fun toolsCameraAddressError(address: String): String? {
    val uri = runCatching { URI(address.trim()) }.getOrNull()
    return if (uri == null || !uri.scheme.equals("rtsp", ignoreCase = true) ||
        uri.isOpaque || uri.host.isNullOrBlank() || uri.rawFragment != null ||
        (uri.port != -1 && uri.port !in 1..65535)) {
        "Use a camera stream address beginning with rtsp:// and a valid camera address."
    } else null
}

/** Selected only by the Tools host: no preview player, scan, network work or idle polling. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolsCamerasContent() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val owner = context.toolsActivity() as? ViewModelStoreOwner
    val draft = remember(owner) {
        if (owner == null) ToolsCameraDraft()
        else ViewModelProvider(owner, ToolsCameraDraftFactory)[ToolsCameraDraft::class.java]
    }
    DisposableEffect(owner, draft) {
        onDispose { if (owner == null) draft.close() }
    }
    val resume = rememberApprovedResume()
    val active = LocalApprovedLauncherPageActive.current
    LaunchedEffect(draft, resume, active) {
        if (active) draft.refresh(appContext)
    }
    val cameras = draft.cameras
    val listState = rememberLazyListState()
    // ToolsTwoPane's outer vertical scroll measures panels with infinite height.
    // This finite dp viewport bounds the inner lazy list even on that measurement path.
    val listHeight = (LocalConfiguration.current.screenHeightDp / 2).dp.coerceIn(320.dp, 720.dp)
    BackHandler(enabled = draft.adding || draft.removal != null) {
        if (draft.removal != null) draft.removal = null else draft.dismissAdd()
    }

    ToolsTwoPane(left = {
        ToolsPanel("Your cameras") {
            draft.loadError?.let { message ->
                ToolsHomeError(message)
                ToolsButton("Try again", enabled = !draft.loading) { draft.refresh(appContext) }
            }
            if (cameras == null) {
                if (draft.loadError == null) ToolsStatus("Loading cameras…")
            } else if (cameras.isEmpty()) {
                ToolsCameraPlaceholder(empty = true)
            } else if (cameras.size == 1) {
                // Keep the approved one-camera geometry, without an extra scroll viewport.
                ToolsCameraEntry(cameras.single(), enabled = !draft.busy,
                    onView = { context.startActivity(cameraStreamIntent(context, it.id)) },
                    onRemove = { draft.removal = it })
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(listHeight), state = listState,
                    verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    // Legacy cameras could share a millisecond ID; retain them without
                    // giving the lazy layout duplicate keys or changing stored data.
                    itemsIndexed(cameras, key = { index, camera -> "${camera.id}:$index" }) { _, camera ->
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ToolsCameraEntry(camera, enabled = !draft.busy,
                                onView = { context.startActivity(cameraStreamIntent(context, it.id)) },
                                onRemove = { draft.removal = it })
                        }
                    }
                }
            }
        }
    }, right = {
        ToolsPanel("Camera setup") {
            ToolsText("Saved cameras open here first. View camera expands the stream; Back returns to this list.",
                secondary = true)
            ToolsButton("Add camera", primary = true, enabled = !draft.busy, onClick = draft::openAdd)
            ToolsStatus(if (draft.busy) "Saving cameras…" else "External camera streams")
            draft.operationError?.let { ToolsHomeError(it) }
        }
    })

    if (draft.adding) ToolsSheet("Add camera", onDismiss = draft::dismissAdd, actions = {
        ToolsButton("Cancel", onClick = draft::dismissAdd)
        ToolsButton(if (draft.busy) "Adding camera…" else "Add camera",
            primary = true, enabled = !draft.busy) { draft.add(appContext) }
    }) {
        ToolsField("Camera name (optional)", draft.name, { draft.name = it; draft.error = null })
        ToolsField("Stream address", draft.url, { draft.url = it; draft.error = null })
        ToolsText("Use a compatible RTSP stream from your camera. The stream connects only when you choose View camera.",
            secondary = true)
        draft.error?.let { ToolsHomeError(it) }
        draft.operationError?.let { ToolsHomeError(it) }
    }
    draft.removal?.let { camera ->
        ToolsConfirm("Remove camera?", "Remove ${camera.name} from your saved cameras?",
            confirmLabel = "Remove", onDismiss = { draft.removal = null },
            onConfirm = { draft.remove(appContext) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsCameraEntry(
    camera: CameraConfig.Camera, enabled: Boolean,
    onView: (CameraConfig.Camera) -> Unit, onRemove: (CameraConfig.Camera) -> Unit,
) {
    ToolsCameraPlaceholder()
    ToolsText(camera.name)
    ToolsText("Saved network camera", secondary = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolsButton("View camera", primary = true, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "View ${camera.name} camera" }) { onView(camera) }
        ToolsButton("Remove", destructive = true, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Remove ${camera.name}" }) { onRemove(camera) }
    }
}

@Composable
private fun ToolsCameraPlaceholder(empty: Boolean = false) {
    val palette = LocalApprovedPalette.current
    Surface(color = if (palette.dark) Color(0xFF101C22) else palette.surface2, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, palette.divider),
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(min = 300.dp)) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            ToolsGlyph("cameraLens", palette.accent, Modifier.size(64.dp))
            Text(if (empty) "A view of home" else "Ready when you are", color = palette.text,
                fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center)
            Text(if (empty) "Add the stream address of a camera you already own."
                else "Choose View camera to open the live stream.", color = palette.secondary,
                fontSize = 22.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Only the explicit display Activity owns window brightness; workspace controls are ephemeral. */
@Composable
internal fun ToolsLampContent() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by rememberSaveable(stateSaver = ApprovedLampStateSaver) {
        mutableStateOf(context.toolsActivity()?.intent?.lampState() ?: ApprovedLampState())
    }
    var editor by rememberSaveable { mutableStateOf<ApprovedLampControl?>(null) }
    val display = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { state = it.lampState() }
    }
    BackHandler(enabled = editor != null) { editor = null }
    ToolsTwoPane(left = {
        ToolsPanel("A softer light") {
            val palette = LocalApprovedPalette.current
            Surface(color = toolsLampColor(state).copy(alpha = (.4f + state.brightness / 1.66f).coerceIn(0f, 1f)),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, palette.divider),
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
                    .semantics { contentDescription = if (state.red) "Red lamp color" else "Warm white lamp color" }) {
                Box(contentAlignment = Alignment.Center) {
                    ApprovedIcon("bulb", tint = Color(0xFF182C39), modifier = Modifier.size(64.dp))
                }
            }
            ToolsButton("Turn on lamp", primary = true) {
                display.launch(Intent(context, LampActivity::class.java)
                    .putExtra(LAMP_PREVIEW, true).withLampState(state))
            }
            ToolsText("The lamp fills the display. Tap Back to return to these controls.", secondary = true)
        }
    }, right = {
        ToolsPanel("Light") {
            ToolsLampSliders(state, onChange = { state = it }, onEdit = { editor = it })
            ToolsText("Red night light")
            ToolsText("A dimmer color for after dark.", secondary = true)
            ToolsButton(if (state.red) "On" else "Off", primary = state.red,
                modifier = Modifier.semantics {
                    contentDescription = "Red night light"
                    stateDescription = if (state.red) "On" else "Off"
                }) { state = state.copy(red = !state.red) }
        }
    })
    editor?.let { control ->
        ToolsLampNumberSheet(control, state, onDismiss = { editor = null }) { value ->
            state = if (control == ApprovedLampControl.BRIGHTNESS) state.copy(brightness = value)
                else state.copy(warmth = value)
            editor = null
        }
    }
}

/** Shared with the native display, so slider ranges, numeric editing and red gating cannot drift. */
@Composable
internal fun ToolsLampSliders(
    state: ApprovedLampState, onChange: (ApprovedLampState) -> Unit, onEdit: (ApprovedLampControl) -> Unit,
) {
    ToolsLampSlider(ApprovedLampControl.BRIGHTNESS, state.brightness,
        onEdit = { onEdit(ApprovedLampControl.BRIGHTNESS) },
        onChange = { onChange(state.copy(brightness = it)) })
    if (!state.red) ToolsLampSlider(ApprovedLampControl.WARMTH, state.warmth,
        onEdit = { onEdit(ApprovedLampControl.WARMTH) },
        onChange = { onChange(state.copy(warmth = it)) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsLampSlider(
    control: ApprovedLampControl, value: Float, onEdit: () -> Unit, onChange: (Float) -> Unit,
) {
    val palette = LocalApprovedPalette.current
    val range = control.min / 100f..1f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolsText(control.title, secondary = true)
        ToolsButton("${(value * 100).roundToInt()}%", modifier = Modifier.fillMaxWidth()
            .semantics {
                contentDescription = "Set ${control.title.lowercase()}, ${(value * 100).roundToInt()} percent"
            }, onClick = onEdit)
        Slider(value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().height(68.dp).semantics { contentDescription = control.title },
            thumb = { Box(Modifier.size(16.dp).background(palette.accent, CircleShape)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    drawRoundRect(palette.divider, cornerRadius = CornerRadius(3.dp.toPx()))
                    drawRoundRect(palette.accent, size = Size(size.width * fraction, size.height),
                        cornerRadius = CornerRadius(3.dp.toPx()))
                }
            })
    }
}

@Composable
internal fun ToolsLampNumberSheet(
    control: ApprovedLampControl, state: ApprovedLampState, onDismiss: () -> Unit, onSave: (Float) -> Unit,
) {
    var draft by rememberSaveable(control) { mutableStateOf(
        ((if (control == ApprovedLampControl.BRIGHTNESS) state.brightness else state.warmth) * 100)
            .roundToInt().toString()) }
    var error by rememberSaveable(control) { mutableStateOf<String?>(null) }
    ToolsSheet(control.title, onDismiss = onDismiss, actions = {
        ToolsButton("Cancel", onClick = onDismiss)
        ToolsButton("Save", primary = true) {
            val value = draft.toIntOrNull()
            if (value == null || value !in control.min..100) error = "Enter a value from ${control.min} to 100."
            else onSave(value / 100f)
        }
    }) {
        ToolsField("Percent", draft, { draft = it; error = null }, numeric = true)
        ToolsText("Choose a value from ${control.min} to 100%.", secondary = true)
        error?.let { ToolsHomeError(it) }
    }
}

@Composable
private fun ToolsHomeError(message: String) {
    Text(message, color = LocalApprovedPalette.current.error, fontSize = 22.sp, lineHeight = 32.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Match the shell's bar preference on this display's window, without changing system policy. */
internal fun applyToolsDisplayBars(activity: Activity) {
    val hidden = ImmortalSettings.hideStatusBar(activity)
    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, !hidden)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        if (hidden) hide(WindowInsetsCompat.Type.systemBars())
        else show(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** Fixed chrome for explicit native displays; body workspaces get their chrome from the host. */
@Composable
internal fun ToolsDisplayHeader(
    backLabel: String, title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalApprovedPalette.current
    Column(Modifier.fillMaxWidth().background(palette.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(horizontal = 40.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolsButton(backLabel, modifier = Modifier.semantics {
                contentDescription = "Back to ${backLabel.removePrefix("‹ ")} controls"
            }, onClick = onBack)
            Text(title, color = palette.secondary, fontSize = 22.sp, lineHeight = 32.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
    }
}
