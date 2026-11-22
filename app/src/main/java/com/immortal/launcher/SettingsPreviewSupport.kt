/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether a live preview animates: only after its page has settled (never during the switch),
 * while at least [MIN_VISIBLE_FRACTION] of it is on screen, the screen is resumed and its page is
 * the active one. Pure, so the rules are tested without Compose.
 */
internal data class PreviewGate(
    val started: Boolean = false,
    val visible: Boolean = false,
    val resumed: Boolean = false,
    val pageActive: Boolean = true,
) {
    val running: Boolean get() = started && visible && resumed && pageActive

    companion object {
        const val MIN_VISIBLE_FRACTION = .25f
    }
}

/**
 * The preview's [PreviewGate]: [started] once the page has settled (load pictures then), [running]
 * while it may animate. Attach [modifier] to the preview itself.
 */
internal class PreviewActivity(val started: Boolean, val running: Boolean, val modifier: Modifier)

@Composable
internal fun rememberPreviewActivity(): PreviewActivity {
    val scheduler = LocalSettingsComposition.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pageActive = LocalApprovedLauncherPageActive.current
    var gate by remember { mutableStateOf(PreviewGate(resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) }
    LaunchedEffect(scheduler) {
        scheduler.awaitPageSettled()
        gate = gate.copy(started = true)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            gate = gate.copy(resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val modifier = remember {
        Modifier.onVisibilityChanged(minFractionVisible = PreviewGate.MIN_VISIBLE_FRACTION) { visible ->
            gate = gate.copy(visible = visible)
        }
    }
    return PreviewActivity(gate.started, gate.copy(pageActive = pageActive).running, modifier)
}

/**
 * [load] re-read whenever its SharedPreferences file changes, so a preview follows the editor
 * rows below it (which keep their own state) without sharing it.
 */
@Composable
internal fun <T> rememberPreferenceSnapshot(prefsName: String, load: (Context) -> T): State<T> {
    val context = LocalContext.current.applicationContext
    val latestLoad = rememberUpdatedState(load)
    val state = remember(prefsName) { mutableStateOf(load(context)) }
    DisposableEffect(prefsName, context) {
        val store = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        // Held here: SharedPreferences keeps listeners weakly.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = latestLoad.value(context) }
        store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/**
 * The bundled photo-frame photos, decoded once for previews (larger than the swatch thumbnails)
 * off the main thread, and dropped when Settings closes.
 */
internal object SettingsPreviewPhotos {
    private const val MAX_EDGE = 720
    val names: List<String> = WallpaperConfig.PHOTOS.map { it.first.removePrefix(WallpaperConfig.PHOTO_PREFIX) }
    private val cache = LruCache<String, ImageBitmap>(names.size.coerceAtLeast(1))

    suspend fun load(context: Context, name: String): ImageBitmap? = cache.get(name) ?: withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("photoframe_fallback/$name").use { SlideshowDecoder.stream(it, MAX_EDGE) }
        }.getOrNull()?.asImageBitmap()
    }?.also { cache.put(name, it) }

    fun clear() = cache.evictAll()

}

/** The bundled photos once [start] is true (never decoded during a page switch). */
@Composable
internal fun rememberPreviewPhotos(start: Boolean): List<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    val photos by produceState(List<ImageBitmap?>(SettingsPreviewPhotos.names.size) { null }, start) {
        if (!start) return@produceState
        value = SettingsPreviewPhotos.names.map { SettingsPreviewPhotos.load(context, it) }
    }
    return photos
}

internal val PreviewShape = RoundedCornerShape(26.dp)
private val PreviewBackdrop = Color(0xFF111111)
private val PreviewChipFill = Color(0x73000000)
private val PreviewChipShape = RoundedCornerShape(14.dp)
/** Height a [PreviewStage] caption chip occupies from the stage's top edge (18 dp inset + chip + 12 dp air):
 * content drawn as a miniature screen starts below it so the chip never covers a widget. */
internal val PreviewCaptionClearance = 72.dp

/** A rounded live-preview stage of [aspect], dark until its picture arrives, with a caption chip. */
@Composable
internal fun PreviewStage(
    aspect: Float,
    caption: String,
    description: String,
    activity: PreviewActivity,
    captionAlignment: Alignment = Alignment.TopStart,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().aspectRatio(aspect).clip(PreviewShape).background(PreviewBackdrop)
        .then(activity.modifier).semantics { contentDescription = description }) {
        content()
        Text(caption, color = Color.White, fontSize = 19.sp,
            modifier = Modifier.align(captionAlignment).padding(18.dp)
                .background(PreviewChipFill, PreviewChipShape).padding(horizontal = 14.dp, vertical = 7.dp))
    }
}
