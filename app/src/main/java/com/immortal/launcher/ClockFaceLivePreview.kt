/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val CLOCK_PHOTO = 1
private val FaceShadow = Shadow(Color(0x73000000), blurRadius = 16f)
private val FaceTextStyle = TextStyle(color = Color.White, shadow = FaceShadow)
private val EmberGlow = Brush.verticalGradient(listOf(Color(0xFFF0A65A), Color(0xFFD9773A)))
private val FlipTile = Color(0xFF1C1D21)
private val FlipText = Color(0xFFF2F2F2)
private val FlipSeam = Color(0x8C000000)
private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

/**
 * Clock Faces › Live preview: the chosen face over a photo at its saved size, ticking each minute
 * while visible. Follows the face, size and time-format settings as they change below it.
 */
@Composable
internal fun ClockFaceLivePreview() {
    val settings by rememberPreferenceSnapshot(ScreensaverConfig.PREFS) { ScreensaverConfig.load(it) }
    val spec = remember(settings.facesEnabled, settings.faceId, settings.faceSizeIndex) {
        ClockFacePreview.spec(settings.facesEnabled, settings.faceId, settings.faceSizeIndex)
    }
    val activity = rememberPreviewActivity()
    val photos = rememberPreviewPhotos(activity.started)
    val now = rememberMinuteClock(activity.running)
    val time = PreviewClock.time(now, rememberUse24HourClock())
    PreviewStage(16f / 9f, spec.caption, "Live preview. ${spec.caption}", activity, captionAlignment = Alignment.TopEnd) {
        PreviewPhoto(photos.getOrNull(CLOCK_PHOTO), ContentScale.Crop, grayscale = false, Modifier.fillMaxSize())
        if (spec.shown) FaceOverlay(spec, time, PreviewClock.date(now))
    }
}

@Composable
private fun FaceOverlay(spec: ClockFacePreviewSpec, time: String, date: String) {
    val dateText: @Composable () -> Unit = {
        spec.dateSp?.let { Text(date, style = FaceTextStyle, fontSize = it.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
    Box(Modifier.fillMaxSize()) {
        when (spec.placement) {
            FacePlacement.CORNER_CLASSIC -> Row(Modifier.align(Alignment.BottomStart).padding(40.dp, 34.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(30.dp, 36.dp).background(EmberGlow, RoundedCornerShape(topStartPercent = 27,
                    topEndPercent = 27, bottomStartPercent = 50, bottomEndPercent = 50)))
                Column {
                    Text(time, style = FaceTextStyle, fontSize = spec.timeSp.sp, fontFamily = FontFamily.Serif, lineHeight = spec.timeSp.sp)
                    dateText()
                }
            }
            FacePlacement.CENTER -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(time, style = FaceTextStyle, fontSize = spec.timeSp.sp, lineHeight = spec.timeSp.sp,
                    fontFamily = if (spec.condensed) Condensed else FontFamily.SansSerif,
                    fontWeight = if (spec.condensed) FontWeight.Bold else FontWeight.ExtraLight)
                dateText()
            }
            FacePlacement.FLIP -> Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                time.split(':').forEach { part -> FlipDigits(part, spec.timeSp) }
            }
            FacePlacement.CORNER_SMALL -> Text(time, style = FaceTextStyle, fontSize = spec.timeSp.sp,
                fontWeight = FontWeight.ExtraLight, modifier = Modifier.align(Alignment.BottomStart).padding(34.dp, 30.dp))
        }
    }
}

@Composable
private fun FlipDigits(digits: String, sizeSp: Float) {
    Box(Modifier.background(FlipTile, RoundedCornerShape(16.dp))
        .drawWithContent {
            drawContent()
            val seam = 2.dp.toPx()
            drawRect(FlipSeam, topLeft = Offset(0f, (size.height - seam) / 2f), size = Size(size.width, seam))
        }
        .padding(horizontal = 22.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(digits, color = FlipText, fontSize = sizeSp.sp, lineHeight = sizeSp.sp, fontWeight = FontWeight.SemiBold)
    }
}
