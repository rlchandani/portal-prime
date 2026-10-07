/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlin.math.roundToInt

internal const val LAMP_PREVIEW = "approved_lamp_preview"
private const val LAMP_BRIGHTNESS = "approved_lamp_brightness"
private const val LAMP_WARMTH = "approved_lamp_warmth"
private const val LAMP_RED = "approved_lamp_red"

internal data class ApprovedLampState(
    val brightness: Float = .9f, val warmth: Float = .6f, val red: Boolean = false,
)

/** Ephemeral lamp controls and real panel preview; the window override is released on exit. */
class LampActivity : ComponentActivity() {
    private var state by mutableStateOf(ApprovedLampState())
    private var preview = false
    private var panelActive = false
    private var originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var originallyKeptAwake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = intent.getBooleanExtra(LAMP_PREVIEW, false)
        originalBrightness = window.attributes.screenBrightness
        originallyKeptAwake = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        state = if (savedInstanceState != null) ApprovedLampState(
            savedInstanceState.getFloat(LAMP_BRIGHTNESS, .9f),
            savedInstanceState.getFloat(LAMP_WARMTH, .6f), savedInstanceState.getBoolean(LAMP_RED))
            else intent.lampState()
        if (preview) applyToolsDisplayBars(this)
        setContent {
            PortalPrimeTheme(darkTheme = preview) {
                if (preview) LampPreview(state, onChange = { state = it }, onBack = ::finishPreview)
                else SettingsScaffold(title = "Lamp", onBack = { finish() }) {
                    LampControlsContent()
                }
                if (preview) LaunchedEffect(state.brightness) { applyBrightness(state.brightness) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (preview) {
            panelActive = true
            applyToolsDisplayBars(this)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyBrightness(state.brightness)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && preview) applyToolsDisplayBars(this)
    }

    override fun onStop() { panelActive = false; restoreWindow(); super.onStop() }
    override fun onDestroy() { panelActive = false; restoreWindow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(LAMP_BRIGHTNESS, state.brightness)
        outState.putFloat(LAMP_WARMTH, state.warmth)
        outState.putBoolean(LAMP_RED, state.red)
        super.onSaveInstanceState(outState)
    }

    /** Retain the original direct window brightness behavior and 5% lower bound. */
    private fun applyBrightness(level: Float) {
        if (!panelActive) return
        window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(.05f, 1f) }
    }
    private fun restoreWindow() {
        if (!preview) return
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        if (!originallyKeptAwake) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun finishPreview() {
        setResult(RESULT_OK, Intent().withLampState(state))
        panelActive = false
        restoreWindow()
        finish()
    }
}

internal fun Intent.lampState() = ApprovedLampState(
    getFloatExtra(LAMP_BRIGHTNESS, .9f).coerceIn(.05f, 1f),
    getFloatExtra(LAMP_WARMTH, .6f).coerceIn(0f, 1f), getBooleanExtra(LAMP_RED, false))
internal fun Intent.withLampState(state: ApprovedLampState): Intent =
    putExtra(LAMP_BRIGHTNESS, state.brightness).putExtra(LAMP_WARMTH, state.warmth).putExtra(LAMP_RED, state.red)

internal val ApprovedLampStateSaver = listSaver<ApprovedLampState, Any>(
    save = { listOf(it.brightness, it.warmth, it.red) },
    restore = { ApprovedLampState(it[0] as Float, it[1] as Float, it[2] as Boolean) },
)

/** Ephemeral controls stay in their host; only Preview takes ownership of panel brightness. */
@Composable
internal fun LampControlsContent() {
    val context = LocalContext.current
    var state by rememberSaveable(stateSaver = ApprovedLampStateSaver) {
        mutableStateOf((context as? android.app.Activity)?.intent?.lampState() ?: ApprovedLampState())
    }
    val previewLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.let { state = it.lampState() }
    }
    LampSettings(state, onChange = { state = it }, onPreview = {
        previewLauncher.launch(Intent(context, LampActivity::class.java)
            .putExtra(LAMP_PREVIEW, true).withLampState(state))
    })
}

internal enum class ApprovedLampControl(val title: String, val min: Int) {
    BRIGHTNESS("Brightness", 5), WARMTH("Warmth", 0),
}

@Composable
private fun LampSettings(state: ApprovedLampState, onChange: (ApprovedLampState) -> Unit, onPreview: () -> Unit) {
    var editor by remember { mutableStateOf<ApprovedLampControl?>(null) }
    Column {
        SectionLabel("Options")
        Card { LampRows(state, onChange, preview = false, onEdit = { editor = it }) }
        Spacer(Modifier.height(34.dp))
        Card {
            Row(Modifier.fillMaxWidth().padding(28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Preview before choosing.", color = IosSecondary, fontSize = 24.sp, lineHeight = 32.sp)
                ApprovedEditorsButton("Preview", primary = true, onClick = onPreview)
            }
        }
    }
    editor?.let { control ->
        LampControlDialog(control, state, onCancel = { editor = null }) { value ->
            onChange(if (control == ApprovedLampControl.BRIGHTNESS) state.copy(brightness = value)
                else state.copy(warmth = value))
            editor = null
        }
    }
}

internal fun toolsLampColor(state: ApprovedLampState): Color {
    // Preserve the native warmth computation and solid red night-light output.
    val green = (255 - state.warmth * 60).toInt().coerceIn(0, 255)
    val blue = (255 - state.warmth * 170).toInt().coerceIn(0, 255)
    return if (state.red) Color(0xFFFF0000) else Color(255, green, blue)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LampPreview(state: ApprovedLampState, onChange: (ApprovedLampState) -> Unit, onBack: () -> Unit) {
    // Preview controls stay dark against every lamp color, regardless of settings appearance.
    CompositionLocalProvider(LocalApprovedPalette provides ApprovedToolsPalette) {
        val context = LocalContext.current
        val palette = LocalApprovedPalette.current
        var controls by rememberSaveable { mutableStateOf(true) }
        var editor by rememberSaveable { mutableStateOf<ApprovedLampControl?>(null) }
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize()) {
            ToolsDisplayHeader("‹ Lamp", "Lamp display", onBack) {
                ToolsButton(if (controls) "Hide controls" else "Show controls") { controls = !controls }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(toolsLampColor(state))) {
                if (controls) {
                    Surface(color = palette.surface, shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, palette.divider),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 40.dp, vertical = 32.dp)
                            .widthIn(max = 560.dp).fillMaxWidth()
                            .heightIn(max = (maxHeight - 64.dp).coerceAtLeast(68.dp))) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            ToolsLampSliders(state, onChange, onEdit = { editor = it })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ToolsButton(if (state.red) "Red light: On" else "Red light: Off",
                                    modifier = Modifier.semantics {
                                        contentDescription = "Red night light"
                                        stateDescription = if (state.red) "On" else "Off"
                                    }) { onChange(state.copy(red = !state.red)) }
                                ToolsButton("Sleep") { ScreenControl.sleep(context) }
                            }
                        }
                    }
                }
            }
        }
        editor?.let { control ->
            ToolsLampNumberSheet(control, state, onDismiss = { editor = null }) { value ->
                onChange(if (control == ApprovedLampControl.BRIGHTNESS) state.copy(brightness = value)
                    else state.copy(warmth = value))
                editor = null
            }
        }
    }
}

@Composable
private fun LampRows(
    state: ApprovedLampState, onChange: (ApprovedLampState) -> Unit,
    preview: Boolean, onEdit: (ApprovedLampControl) -> Unit,
) {
    LampSliderRow("Brightness", state.brightness, .05f..1f, preview,
        onEdit = { onEdit(ApprovedLampControl.BRIGHTNESS) }, onChange = { onChange(state.copy(brightness = it)) })
    Divider()
    if (!state.red) {
        LampSliderRow("Warmth", state.warmth, 0f..1f, preview,
            onEdit = { onEdit(ApprovedLampControl.WARMTH) }, onChange = { onChange(state.copy(warmth = it)) })
        Divider()
    }
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp)
        .tvFocusableRow(focusFill = IosFocusTint) { onChange(state.copy(red = !state.red)) }
        .padding(horizontal = 28.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Red night light", color = if (preview) Color.White else IosLabel,
            fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f))
        ApprovedSettingsSwitch(state.red)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LampSliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, preview: Boolean,
    onEdit: () -> Unit, onChange: (Float) -> Unit,
) {
    val palette = LocalApprovedPalette.current
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp)
        .tvFocusableRow(focusFill = palette.selected, onClick = onEdit)
        .padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(label, color = if (preview) Color.White else palette.text,
            fontSize = 26.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Slider(value = value, onValueChange = onChange, valueRange = range,
                modifier = Modifier.width(300.dp).height(68.dp).semantics { contentDescription = label },
                thumb = { Box(Modifier.size(16.dp).background(palette.accent, CircleShape)) },
                track = {
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                        drawRoundRect(palette.divider, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        drawRoundRect(palette.accent, size = Size(size.width * fraction, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    }
                })
            Text("${(value * 100).roundToInt()}%", color = if (preview) Color(0xFFE9EDF3) else palette.secondary,
                fontSize = 24.sp, lineHeight = 32.sp, modifier = Modifier.width(72.dp))
        }
    }
}

/** Reviewed 900 dp numeric popup; draft changes affect the lamp only after Save. */
@Composable
private fun LampControlDialog(
    control: ApprovedLampControl, state: ApprovedLampState, onCancel: () -> Unit, onSave: (Float) -> Unit,
) {
    val palette = LocalApprovedPalette.current
    var draft by remember(control) { mutableStateOf(((if (control == ApprovedLampControl.BRIGHTNESS)
        state.brightness else state.warmth) * 100).roundToInt().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = palette.surface, shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, palette.divider),
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().heightIn(max = 1100.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(38.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(control.title, color = palette.text, fontSize = 34.sp, lineHeight = 44.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Box(Modifier.size(68.dp).background(palette.surface2, CircleShape)
                        .tvFocusable(CircleShape, focusScale = 1f, ringColor = palette.accent, onClick = onCancel)
                        .semantics { contentDescription = "Close dialog" }, contentAlignment = Alignment.Center) {
                        ApprovedIcon("x", tint = palette.text, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.height(25.dp))
                Text(control.title, color = palette.secondary, fontSize = 24.sp, lineHeight = 32.sp)
                Spacer(Modifier.height(12.dp))
                Surface(color = palette.background, shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, palette.divider)) {
                    BasicTextField(draft, { draft = it.replace("\n", ""); error = null }, singleLine = true,
                        textStyle = TextStyle(color = palette.text, fontSize = 26.sp, lineHeight = 36.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 78.dp)
                            .semantics { contentDescription = control.title }
                            .padding(horizontal = 23.dp, vertical = 20.dp))
                }
                Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ApprovedEditorsButton("−") {
                        draft = ((draft.toIntOrNull() ?: control.min) - 1).coerceIn(control.min, 100).toString(); error = null
                    }
                    ApprovedEditorsButton("+") {
                        draft = ((draft.toIntOrNull() ?: control.min) + 1).coerceIn(control.min, 100).toString(); error = null
                    }
                }
                Text(error.orEmpty(), color = palette.error, fontSize = 23.sp, lineHeight = 34.sp,
                    modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                    ApprovedEditorsButton("Cancel", onClick = onCancel)
                    ApprovedEditorsButton("Save", primary = true) {
                        val value = draft.toIntOrNull()
                        if (value == null || value !in control.min..100) error = "Enter a value from ${control.min} to 100."
                        else onSave(value / 100f)
                    }
                }
            }
        }
    }
}
