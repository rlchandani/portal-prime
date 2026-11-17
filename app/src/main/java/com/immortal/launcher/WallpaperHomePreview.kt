/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.ApprovedDarkPalette

private const val HOME_ASPECT = 16f / 10f
private const val APP_COUNT = 6
private const val WIDGET_HEIGHT = .34f
// Widths follow the mock: 40% clock widget, 4% gap, 46% second widget.
private const val CLOCK_WIDGET_WEIGHT = 40f
private const val WIDGET_GAP_WEIGHT = 4f
private const val SECOND_WIDGET_WEIGHT = 46f
private const val GREETING_ALPHA = .75f
private const val APP_CORNER_PERCENT = 22
private val HomePreviewMaxWidth = 760.dp
private val WidgetShape = RoundedCornerShape(18.dp)
private val WidgetFill = Color(0x24FFFFFF)
private val SecondWidgetFill = Color(0x1FFFFFFF)
private val AppFill = Color(0x2EFFFFFF)
private val NeutralWallpaper = Color(0xFF333740)
/** A few fixed stars (fractions of the preview) for the Star field background. */
private val Stars = listOf(.2f to .3f, .7f to .6f, .4f to .8f, .85f to .2f, .55f to .4f, .12f to .7f, .92f to .75f)
private const val STAR_RADIUS_DP = 1.5f

/**
 * Wallpaper › Preview: the chosen background on a miniature Home (clock widget with the
 * greeting, a second widget and the app row), with film grain when it is on. Follows the
 * swatches below it live; the clock ticks each minute while visible.
 */
@Composable
internal fun WallpaperHomePreview() {
    val wallpaper by rememberPreferenceSnapshot(WallpaperConfig.PREFS) { WallpaperConfig.load(it) }
    val welcome by rememberPreferenceSnapshot(WelcomeConfig.PREFS) { WelcomeConfig.load(it) }
    val activity = rememberPreviewActivity()
    val now = rememberMinuteClock(activity.running)
    val photo = rememberWallpaperPhoto(wallpaper.mode, activity.started)
    val greeting = GreetingPreview.fullGreeting(PreviewClock.hour(now), welcome)
    val label = "Home with this background"
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PreviewStage(HOME_ASPECT, label, "Wallpaper preview on Home", activity,
            modifier = Modifier.widthIn(max = HomePreviewMaxWidth)) {
            WallpaperBackdrop(wallpaper.mode, photo)
            if (wallpaper.grain) GrainOverlay()
            MiniHome(PreviewClock.time(now, rememberUse24HourClock()), greeting)
        }
    }
}

@Composable
private fun rememberWallpaperPhoto(mode: String, start: Boolean): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val photo by produceState<ImageBitmap?>(null, mode, start) {
        value = if (start && mode.startsWith(WallpaperConfig.PHOTO_PREFIX)) {
            SettingsPreviewPhotos.load(context, mode.removePrefix(WallpaperConfig.PHOTO_PREFIX))
        } else null
    }
    return photo
}

@Composable
private fun WallpaperBackdrop(mode: String, photo: ImageBitmap?) {
    val colors = wallpaperPreviewColors(mode)
    when {
        photo != null -> PreviewPhoto(photo, ContentScale.Crop, grayscale = false, Modifier.fillMaxSize())
        colors != null -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors.map { Color(it) })))
        mode == WallpaperConfig.DARK -> Box(Modifier.fillMaxSize().background(ApprovedDarkPalette.background))
        else -> Box(Modifier.fillMaxSize().background(NeutralWallpaper))
    }
    if (mode == WallpaperConfig.STARFIELD) {
        Canvas(Modifier.fillMaxSize()) {
            Stars.forEach { (x, y) -> drawCircle(Color.White, STAR_RADIUS_DP.dp.toPx(), Offset(size.width * x, size.height * y)) }
        }
    }
}

@Composable
private fun MiniHome(time: String, greeting: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth().fillMaxHeight(WIDGET_HEIGHT)) {
            Column(Modifier.weight(CLOCK_WIDGET_WEIGHT).fillMaxHeight().background(WidgetFill, WidgetShape)
                .padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(time, color = Color.White, fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraLight)
                Text(greeting, color = Color.White.copy(alpha = GREETING_ALPHA), fontSize = 16.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.weight(WIDGET_GAP_WEIGHT))
            Box(Modifier.weight(SECOND_WIDGET_WEIGHT).fillMaxHeight().background(SecondWidgetFill, WidgetShape))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(APP_COUNT) {
                Box(Modifier.weight(1f).aspectRatio(1f).background(AppFill, RoundedCornerShape(APP_CORNER_PERCENT)))
            }
        }
    }
}
