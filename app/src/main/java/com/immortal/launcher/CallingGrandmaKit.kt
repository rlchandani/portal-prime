/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Grandma mode words and small pure rules (JVM-tested). */
internal object GrandmaCopy {
  const val CAMERA_LIGHT = "The camera light comes on when it answers"
  const val NOT_NOW = "Not now"
  const val QUIETER = "Quieter"
  const val LOUDER = "Louder"
  const val HANG_UP = "Hang up"

  fun calling(name: String) = "$name is calling"

  /** Whole seconds left before auto-answer, counting 10 → 1 (0 once it is due). */
  fun secondsLeft(autoAnswerAtMs: Long, nowMs: Long): Int =
      ((autoAnswerAtMs - nowMs + MS_PER_S - 1) / MS_PER_S).toInt().coerceIn(0, (CallTimings.AUTO_ANSWER_MS / MS_PER_S).toInt())

  fun countdown(seconds: Int): String = when {
    seconds <= 0 -> "Answering now…"
    seconds == 1 -> "Answering by itself in 1 second"
    else -> "Answering by itself in $seconds seconds"
  }

  /** Share of the countdown already spent, 0 → 1 (the ring fills as it runs). */
  fun progress(autoAnswerAtMs: Long, nowMs: Long): Float =
      (1f - (autoAnswerAtMs - nowMs).toFloat() / CallTimings.AUTO_ANSWER_MS).coerceIn(0f, 1f)

  fun messages(count: Int): String = when (count) {
    0 -> "Messages"
    1 -> "1 new message"
    else -> "$count new messages"
  }

  fun liveBar(cameraOn: Boolean, micOn: Boolean, with: String) = "${CallingCopy.liveBar(cameraOn, micOn)} · with $with"

  /** "Tuesday afternoon", as the Grandma clock says it. */
  fun partOfDay(hour: Int): String = when (hour) {
    in 5..11 -> "morning"
    in 12..16 -> "afternoon"
    in 17..20 -> "evening"
    else -> "night"
  }

  private const val MS_PER_S = 1_000L
}

/** One step of the call volume (±10 % of the voice stream), pure so Quieter/Louder are tested. */
internal object CallVolumeMath {
  const val STEP_SHARE = 0.1f
  const val PERCENT = 100

  fun step(level: Int, delta: Int, max: Int): Int {
    val units = maxOf(1, (max * STEP_SHARE).roundToInt())
    return (level + delta * units).coerceIn(0, max.coerceAtLeast(1))
  }

  fun percent(level: Int, max: Int): Int = level * PERCENT / max.coerceAtLeast(1)
}

/** The voice-call stream volume, shared by the volume popover and Grandma's Quieter / Louder. */
internal class CallVolume(context: Context) {
  private val audio = context.getSystemService(AudioManager::class.java)
  val max: Int = audio?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)?.coerceAtLeast(1) ?: 1

  fun level(): Int = audio?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: 0

  /** Applies one step from [level] and returns the new level. */
  fun step(level: Int, delta: Int): Int {
    val next = CallVolumeMath.step(level, delta, max)
    runCatching { audio?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, next, 0) }
    return next
  }
}

/** Grandma mode tokens from the mock (`.gm`, `.gbtn`, `.gface`). dp at 1920×1280; compact scales. */
@Immutable
internal data class GrandmaSizes(val button: Dp, val buttonText: TextUnit, val buttonIcon: Dp, val radius: Dp, val edge: Dp, val gap: Dp) {
  companion object {
    /** `.gbtn` is 160 dp tall everywhere: the Mini keeps the full height and trims only text and insets. */
    fun of(layout: LayoutClass) = if (layout.compact) GrandmaSizes(160.dp, 38.sp, 50.dp, 44.dp, 28.dp, 20.dp)
    else GrandmaSizes(160.dp, 46.sp, 60.dp, 48.dp, 48.dp, 32.dp)
  }
}

/** `.gbtn`: a 160 dp pill-ish button with a big icon and label. */
@Composable
internal fun GrandmaButton(label: String, icon: String, fill: Color, ink: Color, sizes: GrandmaSizes, onClick: () -> Unit,
                           modifier: Modifier = Modifier, edge: Color? = null) {
  val shape = RoundedCornerShape(sizes.radius)
  Row(modifier.height(sizes.button).background(fill, shape).let { if (edge != null) it.border(2.dp, edge, shape) else it }
      .callClick(label, true, onClick).padding(horizontal = 28.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
    CallIcon(icon, ink, sizes.buttonIcon)
    Text(label, color = ink, fontSize = sizes.buttonText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}
