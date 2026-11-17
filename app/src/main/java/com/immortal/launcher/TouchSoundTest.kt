/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val WAVE_MS = 520
private const val WAVE_MAX_ALPHA = .35f
private val TestButtonShape = RoundedCornerShape(16.dp)
private val TestButtonMinHeight = 68.dp

/** Plays the system key click; Android itself skips it while touch sounds are off. */
internal fun playTouchClick(context: Context) {
    runCatching {
        context.getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEY_CLICK)
    }
}

internal fun touchSoundTestMessage(enabled: Boolean, tapped: Boolean): String = when {
    !enabled -> "Touch sounds are off. Turn them on to hear the click."
    tapped -> "That’s the click you hear when you tap the screen."
    else -> "Tap the button to hear and see the feedback."
}

/**
 * "Test touch sound": plays the real system click (the one Android plays on every tap) with a
 * ripple, or explains that touch sounds are off. Lives in the touch-sounds card.
 */
@Composable
internal fun TouchSoundTestRow(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wave = remember { Animatable(0f) }
    var tapped by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.onPrimary
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ApprovedSettingsLabel("Test touch sound", touchSoundTestMessage(enabled, tapped), Modifier.weight(1f).padding(end = 24.dp))
        val fill = if (enabled) IosBlue else IosDivider
        Box(Modifier.heightIn(min = TestButtonMinHeight).clip(TestButtonShape).background(fill)
            .drawWithContent {
                drawContent()
                val progress = wave.value
                if (progress > 0f) {
                    drawCircle(accent.copy(alpha = WAVE_MAX_ALPHA * (1f - progress)), radius = size.maxDimension * progress)
                }
            }
            .tvFocusable(TestButtonShape, focusScale = 1f, ringColor = IosBlue) {
                tapped = true
                if (!enabled) return@tvFocusable
                playTouchClick(context)
                scope.launch {
                    wave.snapTo(0f)
                    wave.animateTo(1f, tween(WAVE_MS, easing = FastOutSlowInEasing))
                    wave.snapTo(0f)
                }
            }
            .semantics { contentDescription = "Tap to test touch sound" },
            contentAlignment = Alignment.Center) {
            Text("Tap to test", color = if (enabled) accent else IosSecondary, fontSize = Sizes.actionButton,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
        }
    }
}

