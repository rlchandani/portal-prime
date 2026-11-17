/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GREETING_PHOTO = 2
private const val CLOCK_SP = 110f
private const val DATE_SP = 28f
private const val GREETING_SP = 50f

/**
 * Welcome Greeting › Preview: the overlay over a photo with the saved greeting, clock, date,
 * size, colours and background opacity, the clock ticking each minute while visible.
 */
@Composable
internal fun GreetingLivePreview() {
    val settings by rememberPreferenceSnapshot(WelcomeConfig.PREFS) { WelcomeConfig.load(it) }
    val activity = rememberPreviewActivity()
    val photos = rememberPreviewPhotos(activity.started)
    val now = rememberMinuteClock(activity.running)
    val use24 = rememberUse24HourClock()
    val spec = GreetingPreview.spec(PreviewClock.hour(now), settings)
    PreviewStage(16f / 9f, spec.caption, "Greeting preview. ${spec.greeting.orEmpty()}. ${spec.caption}", activity,
        captionAlignment = Alignment.TopEnd) {
        PreviewPhoto(photos.getOrNull(GREETING_PHOTO), ContentScale.Crop, grayscale = false, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = spec.backgroundOpacity)))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (spec.showClock) {
                Text(PreviewClock.time(now, use24), color = Color(spec.clockColor), fontSize = (CLOCK_SP * spec.scale).sp,
                    lineHeight = (CLOCK_SP * spec.scale).sp, fontWeight = FontWeight.ExtraLight)
            }
            if (spec.showDate) {
                Text(PreviewClock.date(now), color = Color(spec.dateColor), fontSize = (DATE_SP * spec.scale).sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
            spec.greeting?.let {
                Text(it, color = Color(spec.greetingColor), fontSize = (GREETING_SP * spec.scale).sp,
                    lineHeight = (GREETING_SP * spec.scale * 1.2f).sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 22.dp))
            }
        }
    }
}
