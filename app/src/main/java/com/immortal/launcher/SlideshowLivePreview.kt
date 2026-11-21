/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val NO_SLIDE = -1
private const val OFF_DIM = .5f
private const val PAIR_WIDTH = .8f
private const val PAIR_HEIGHT = .92f
/** A pair card before its photo decodes holds the 2:3 portrait the pair asks the loader for. */
private const val PAIR_PLACEHOLDER_WIDTH = 2
private const val PAIR_PLACEHOLDER_HEIGHT = 3
private const val WHOLE_WIDTH = .68f
private const val WHOLE_HEIGHT = .88f
private const val PRINT_WIDTH = .56f
private const val PRINT_HEIGHT = .84f
private val StageDark = Color(0xFF0C0D10)
private val PrintRoom = Color(0xFF1A1714)
private val PrintMatte = Color(0xFFF3EFE6)
private val PhotoPlaceholder = Brush.verticalGradient(listOf(Color(0xFF2C3E50), Color(0xFF4CA1AF)))
private val NowPlayingFill = Color(0x66000000)
private val OverlayShadow = Shadow(Color(0x80000000), blurRadius = 12f)
private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Photo Frame › Live preview: the saved style's slides change every few seconds with that style's
 * transition (crossfade, or a push for the push styles), gentle motion when it is on, and the
 * clock and now-playing card when they are shown. Animates only while [rememberPreviewActivity]
 * says so; motion and fades run in the draw phase.
 */
@Composable
internal fun SlideshowLivePreview() {
    val settings by rememberPreferenceSnapshot(ScreensaverConfig.PREFS) { ScreensaverConfig.load(it) }
    val spec = remember(settings) { SlideshowPreview.spec(settings) }
    val activity = rememberPreviewActivity()
    val running = activity.running && spec.on
    val photos = rememberPreviewPhotos(activity.started)
    var index by remember { mutableIntStateOf(0) }
    var previous by remember { mutableIntStateOf(NO_SLIDE) }
    val change = remember { Animatable(1f) }
    val drift = remember { Animatable(0f) }
    LaunchedEffect(running, spec.transition) {
        if (!running) return@LaunchedEffect
        val duration = if (spec.transition == SlideTransition.PUSH) SlideshowPreview.PUSH_MS else SlideshowPreview.CROSSFADE_MS
        while (true) {
            delay(SlideshowPreview.SLIDE_MS)
            previous = index
            index = SlideshowPreview.next(index)
            change.snapTo(0f)
            change.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
            previous = NO_SLIDE
        }
    }
    LaunchedEffect(running, spec.motion) {
        if (!spec.motion) drift.snapTo(0f)
        if (!running || !spec.motion) return@LaunchedEffect
        while (true) {
            drift.animateTo(1f, tween(SlideshowPreview.MOTION_MS, easing = FastOutSlowInEasing))
            drift.animateTo(0f, tween(SlideshowPreview.MOTION_MS, easing = FastOutSlowInEasing))
        }
    }
    val push = spec.transition == SlideTransition.PUSH
    val motion = Modifier.graphicsLayer {
        val d = drift.value
        val scale = SlideshowPreview.MOTION_START_SCALE + (SlideshowPreview.MOTION_END_SCALE - SlideshowPreview.MOTION_START_SCALE) * d
        scaleX = scale
        scaleY = scale
        translationX = size.width * SlideshowPreview.MOTION_DRIFT_X * d
        translationY = size.height * SlideshowPreview.MOTION_DRIFT_Y * d
    }
    val now = rememberMinuteClock(running)
    val use24 = rememberUse24HourClock()
    PreviewStage(16f / 9f, spec.caption, "Live preview. ${spec.caption}", activity) {
        if (previous != NO_SLIDE) {
            PreviewSlide(spec.slides[previous], previous, photos, motion, !spec.on,
                Modifier.graphicsLayer { if (push) translationX = -change.value * size.width })
        }
        PreviewSlide(spec.slides[index % spec.slides.size], index, photos, motion, !spec.on,
            Modifier.graphicsLayer {
                if (push) translationX = (1f - change.value) * size.width else alpha = change.value
            })
        if (spec.showClock) {
            Text(PreviewClock.time(now, use24), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light,
                style = TextStyle(shadow = OverlayShadow), modifier = Modifier.align(Alignment.BottomStart).padding(28.dp, 22.dp))
        }
        if (spec.showNowPlaying) {
            Text("♪ Now playing", color = Color.White, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp, 22.dp)
                    .background(NowPlayingFill, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        if (!spec.on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = OFF_DIM)))
    }
}

@Composable
private fun PreviewSlide(
    layout: SlideLayout,
    slide: Int,
    photos: List<ImageBitmap?>,
    motion: Modifier,
    grayscale: Boolean,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (layout) {
            SlideLayout.FILL -> PreviewPhoto(photos.wrapped(slide), ContentScale.Crop, grayscale, Modifier.fillMaxSize().then(motion))
            SlideLayout.PAIR -> Box(Modifier.fillMaxSize().background(StageDark), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth(PAIR_WIDTH).fillMaxHeight(PAIR_HEIGHT), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(slide * 2 + 1, slide * 2 + 2).forEach { photo ->
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))) {
                            PreviewPhoto(photos.wrapped(photo), ContentScale.Crop, grayscale, Modifier.fillMaxSize().then(motion))
                        }
                    }
                }
            }
            SlideLayout.PAIR_WHOLE -> Box(Modifier.fillMaxSize().background(StageDark), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth(PAIR_WIDTH).fillMaxHeight(PAIR_HEIGHT), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(slide * 2 + 1, slide * 2 + 2).forEach { photo ->
                        WholePairCard(photos.wrapped(photo), grayscale, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            SlideLayout.WHOLE -> Box(Modifier.fillMaxSize().background(StageDark), contentAlignment = Alignment.Center) {
                PreviewPhoto(photos.wrapped(slide), ContentScale.Fit, grayscale,
                    Modifier.fillMaxWidth(WHOLE_WIDTH).fillMaxHeight(WHOLE_HEIGHT))
            }
            SlideLayout.PRINT -> Box(Modifier.fillMaxSize().background(PrintRoom), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth(PRINT_WIDTH).fillMaxHeight(PRINT_HEIGHT).background(PrintMatte).padding(14.dp)) {
                    PreviewPhoto(photos.wrapped(slide), ContentScale.Crop, grayscale, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Portrait pair at fit: the card shrinks to its whole, still photo, centred in its slot. */
@Composable
private fun WholePairCard(photo: ImageBitmap?, grayscale: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val card = SlideshowPreview.pairCard(photo?.width ?: PAIR_PLACEHOLDER_WIDTH,
            photo?.height ?: PAIR_PLACEHOLDER_HEIGHT, constraints.maxWidth, constraints.maxHeight)
        val density = LocalDensity.current
        with(density) {
            Box(Modifier.offset(card.left.toDp(), card.top.toDp()).size(card.width.toDp(), card.height.toDp())
                .clip(RoundedCornerShape(6.dp))) {
                PreviewPhoto(photo, ContentScale.Crop, grayscale, Modifier.fillMaxSize())
            }
        }
    }
}

private fun List<ImageBitmap?>.wrapped(index: Int): ImageBitmap? = if (isEmpty()) null else this[Math.floorMod(index, size)]

/** A bundled photo, or a calm gradient until it has decoded. */
@Composable
internal fun PreviewPhoto(photo: ImageBitmap?, scale: ContentScale, grayscale: Boolean, modifier: Modifier) {
    if (photo == null) {
        Box(modifier.background(PhotoPlaceholder))
    } else {
        Image(photo, contentDescription = null, contentScale = scale, colorFilter = if (grayscale) Grayscale else null,
            modifier = modifier)
    }
}
