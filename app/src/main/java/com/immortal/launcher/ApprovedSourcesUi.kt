/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

/** Source work belongs to the visible form, never to a detached thread or application scope. */
internal class ApprovedSourcesWork(
    private val scope: CoroutineScope,
    private val activity: Activity?,
) {
    private val operation = ApprovedSourcesOperation()
    private var job: Job? = null
    var busy by mutableStateOf(false)
        private set
    var allowed = true

    fun <T> start(work: () -> T, reply: (Result<T>) -> Unit) {
        if (!allowed || activity?.isFinishing == true || activity?.isDestroyed == true) return
        cancel()
        val request = operation.begin()
        busy = true
        job = scope.launch {
            val result = try {
                Result.success(runInterruptible(Dispatchers.IO) { work() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            ensureActive()
            if (allowed && operation.accepts(request) && activity?.isFinishing != true &&
                activity?.isDestroyed != true) {
                busy = false
                reply(result)
            }
        }
    }

    fun cancel() {
        operation.cancel()
        job?.cancel()
        job = null
        busy = false
    }

    fun close() { operation.close(); cancel() }
}

@Composable
internal fun rememberApprovedSourcesWork(requiresAdvanced: Boolean = true): ApprovedSourcesWork {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    val owner = LocalLifecycleOwner.current
    val work = remember(scope, activity) { ApprovedSourcesWork(scope, activity) }
    val advanced = LocalAdvancedSettings.current
    work.allowed = !requiresAdvanced || advanced
    LaunchedEffect(advanced, requiresAdvanced) { if (requiresAdvanced && !advanced) work.cancel() }
    DisposableEffect(owner, work) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) work.cancel()
            if (event == Lifecycle.Event.ON_DESTROY) work.close()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            work.close()
            owner.lifecycle.removeObserver(observer)
        }
    }
    return work
}

/** Scans and decoding stop when the Activity stops as well as when its lazy item is disposed. */
@Composable
internal fun <T> approvedSourcesIoState(
    initial: T, vararg keys: Any?, load: () -> T,
): State<T> {
    val owner = LocalLifecycleOwner.current
    return key(owner, *keys) {
        produceState(initial, owner) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                value = initial
                value = runInterruptible(Dispatchers.IO) { load() }
            }
        }
    }
}

/** Consume the shared policy; direct Activity launches need the same Advanced gate as navigation. */
@Composable
internal fun ApprovedSourcesPage(
    title: String,
    onBack: () -> Unit,
    advancedOnly: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val advanced = LocalAdvancedSettings.current
    var consent by remember { mutableStateOf(false) }
    SettingsScaffold(title = title, onBack = onBack) {
        if (advancedOnly && !advanced) {
            ApprovedSettingsAdvancedGate { consent = true }
        } else content()
    }
    if (consent) {
        ApprovedSettingsAdvancedConsent(
            onEnable = {
                SettingsRegistry.apply(context, "immortal", JSONObject().put("advancedSettings", true))
                consent = false
            },
            onCancel = { consent = false },
        )
    }
}

/** Confirmed source flows return to the frame settings, clearing the intervening picker stack. */
internal fun approvedSourcesReturn(activity: Activity, page: String = "screensaver") {
    if (activity.isFinishing || activity.isDestroyed) return
    activity.startActivity(approvedSettingsIntent(activity, page)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    activity.finish()
}

/** The approved source-form geometry: 1.2 : 1 columns, 32 dp gap and 32 dp panel inset. */
@Composable
internal fun ApprovedSourcesForm(
    helpTitle: String,
    help: String,
    fields: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalApprovedPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.Top) {
        Surface(color = palette.surface, shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, palette.divider), modifier = Modifier.weight(1.2f)) {
            Column(Modifier.padding(32.dp), content = fields)
        }
        Surface(color = palette.surface, shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, palette.divider), modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(32.dp)) {
                Text(helpTitle, color = palette.text, fontSize = 30.sp, lineHeight = 40.sp,
                    fontWeight = FontWeight.Medium)
                Text(help, color = palette.secondary, fontSize = 24.sp, lineHeight = 38.sp,
                    modifier = Modifier.padding(top = 22.dp))
            }
        }
    }
}

@Composable
internal fun ApprovedSourcesField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalApprovedPalette.current
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
        Text(label, color = palette.text, fontSize = 24.sp, lineHeight = 32.sp)
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = palette.text, fontSize = 26.sp,
                lineHeight = 36.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                autoCorrectEnabled = false),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(palette.accent),
            modifier = modifier.fillMaxWidth().height(78.dp)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .background(palette.background, RoundedCornerShape(15.dp))
                .border(if (focused) 2.dp else 1.dp,
                    if (focused) palette.accent else Color(0xFF8CA4B5), RoundedCornerShape(15.dp))
                .padding(horizontal = 23.dp, vertical = 20.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = palette.secondary,
                        fontSize = 26.sp, lineHeight = 36.sp, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun ApprovedSourcesStatus(message: String?, error: Boolean = true) {
    val palette = LocalApprovedPalette.current
    Text(message.orEmpty(), color = if (error) palette.error else palette.secondary,
        fontSize = 23.sp, lineHeight = 34.sp,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 34.dp)
            .padding(top = 10.dp, bottom = 20.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun ApprovedSourcesButton(
    label: String, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit,
) {
    val palette = LocalApprovedPalette.current
    val shape = RoundedCornerShape(16.dp)
    Surface(color = if (primary) palette.accent else palette.surface2, shape = shape,
        modifier = Modifier.defaultMinSize(minHeight = 68.dp)
            .alpha(if (enabled) 1f else .55f)
            .semantics { role = Role.Button; if (!enabled) disabled() }
            .then(if (enabled) Modifier.tvFocusable(shape, focusScale = 1f, ringColor = palette.accent,
                onClick = onClick)
                else Modifier)) {
        Text(label, color = if (primary) Color.White else palette.text,
            fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp))
    }
}

@Composable
internal fun ApprovedSourcesActions(
    primaryLabel: String, enabled: Boolean = true, onConfirm: () -> Unit, onCancel: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ApprovedSourcesButton(primaryLabel, enabled = enabled, onClick = onConfirm)
        ApprovedSourcesButton("Cancel", primary = false, onClick = onCancel)
    }
}

/** Actual connection results are information, with no navigation affordance or inert click. */
@Composable
internal fun ApprovedSourcesInfoRow(title: String, value: String) {
    val palette = LocalApprovedPalette.current
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp)
        .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(title, color = palette.text, fontSize = 26.sp, lineHeight = 34.sp,
            modifier = Modifier.weight(1f))
        Text(value, color = palette.secondary, fontSize = 23.sp, lineHeight = 30.sp,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 600.dp))
    }
}

@Composable
internal fun ApprovedSourcesPreviewFooter(topGap: Dp = 34.dp, onPreview: () -> Unit) {
    val palette = LocalApprovedPalette.current
    Surface(color = palette.surface, shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(top = topGap)) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Preview before choosing.", color = palette.secondary, fontSize = 23.sp,
                lineHeight = 32.sp, modifier = Modifier.weight(1f))
            ApprovedSourcesButton("Preview", onClick = onPreview)
        }
    }
}
