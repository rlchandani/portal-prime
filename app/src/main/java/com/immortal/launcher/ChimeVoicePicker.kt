/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private val PreviewButtonShape = RoundedCornerShape(12.dp)
private val PreviewButtonMinHeight = 68.dp

/**
 * Installed offline voices, or null when the engine is unavailable. The engine is created, asked
 * and shut down off the main thread (binding and enumerating it took the main thread during a
 * page switch); only its init callback arrives on the main thread.
 */
internal suspend fun loadInstalledVoices(context: Context): List<InstalledVoiceChoice>? = withContext(Dispatchers.Default) {
    var engine: TextToSpeech? = null
    try {
        val ready = suspendCancellableCoroutine { continuation ->
            engine = runCatching {
                TextToSpeech(context.applicationContext) { status ->
                    if (continuation.isActive) continuation.resume(status == TextToSpeech.SUCCESS)
                }
            }.getOrNull()
            if (engine == null && continuation.isActive) continuation.resume(false)
        }
        if (!ready) null else runCatching {
            engine?.voices.orEmpty().filter { !it.isNetworkConnectionRequired }.sortedWith(TtsVoiceLabels.sort)
                .map { InstalledVoiceChoice(it.name, TtsVoiceLabels.label(it), it.locale.getDisplayName(Locale.US)) }
                .distinctBy { it.name }
        }.getOrNull()
    } finally {
        runCatching { engine?.shutdown() }
    }
}

/**
 * Bounded chooser over the existing on-device engine. Empty identity means engine default. The
 * voice list loads once the page has settled, so the engine never runs during the switch.
 */
@Composable
internal fun ChimeVoicePicker(current: String, enabled: Boolean, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val scheduler = LocalSettingsComposition.current
    var voices by remember { mutableStateOf<List<InstalledVoiceChoice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    LaunchedEffect(Unit) {
        scheduler.awaitPageSettled()
        val found = loadInstalledVoices(context)
        voices = found.orEmpty()
        loadFailed = found == null
        loading = false
    }
    Row(
        modifier = Modifier.fillMaxWidth().tvFocusableRow(enabled = enabled) { expanded = true }
            .semantics { contentDescription = "Spoken-time voice" }
            .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Voice", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
        Text(installedVoiceLabel(current, voices, loading), color = IosSecondary, fontSize = Sizes.rowValue,
            fontWeight = FontWeight.SemiBold)
        Text("  ▾", color = IosSecondary, fontSize = Sizes.rowValue)
    }
    if (expanded && enabled) InstalledVoiceChoiceDialog(voices, current, loading, loadFailed, enabled,
        onDismiss = { expanded = false }, onPick = { if (enabled) onPick(it) })
}

/** A pill that plays one preview sound (or stops the current one). */
@Composable
internal fun ChimePreviewButton(label: String, enabled: Boolean, isStop: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = IosFocusTint,
        shape = PreviewButtonShape,
        modifier = Modifier.heightIn(min = PreviewButtonMinHeight)
            .tvFocusable(PreviewButtonShape, focusScale = 1f, enabled = enabled) { onClick() },
    ) {
        Text(
            if (isStop) label else "▶  $label",
            color = if (enabled) IosBlue else IosSecondary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        )
    }
}
