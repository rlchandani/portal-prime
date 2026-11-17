/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The short "goal reached" celebration. Every Portal shows it when a kid's weekly total crosses
 * their goal (by a tick here, on another Portal, or a parent's OK); only the room where it
 * happened chimes, and never in quiet hours (the chime's window, shared with Call everyone).
 */
internal object ChoresCelebration {
  private const val TAG = "HearthpaneChores"
  const val SHOW_MS = 6_000L

  data class Cheer(val seq: Long, val name: String, val avatar: String, val color: Int, val line: String)

  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val mutable = MutableStateFlow<Cheer?>(null)
  val cheer: StateFlow<Cheer?> = mutable.asStateFlow()
  private var seq = 0L
  private val hide = Runnable { mutable.value = null }

  /** Pure: should this room chime for a celebration that came from [source]? */
  fun chimes(source: ChoreStore.Source, quietHours: Boolean): Boolean = source != ChoreStore.Source.PEER && !quietHours

  /** "Mia reached 20 ★ — Movie night!" */
  fun line(w: ChoreBoard.Week, name: String): String =
      if (w.reward.isBlank()) "$name reached ${w.goal} ★!" else "$name reached ${w.goal} ★ — ${w.reward}!"

  fun onChanged(context: Context, before: ChoreState, after: ChoreState, source: ChoreStore.Source) {
    val day = ChoreStore.today()
    val reached = ChoreBoard.goalsReached(before, after, day)
    if (reached.isEmpty()) return
    val weeks = ChoreBoard.week(after, day).associateBy { it.kid }
    val kid = after.kids[reached.first()] ?: return
    val week = weeks[kid.id] ?: return
    main.post {
      mutable.value = Cheer(++seq, kid.name, kid.avatar, kid.color, line(week, kid.name))
      main.removeCallbacks(hide)
      main.postDelayed(hide, SHOW_MS)
      if (chimes(source, quiet(context))) runCatching { ChimePlayer.playChime(context) }
          .onFailure { Log.w(TAG, "celebration chime failed", it) }
    }
  }

  fun dismiss() = main.post { main.removeCallbacks(hide); mutable.value = null }

  private fun quiet(context: Context): Boolean = runCatching {
    DinnerQuiet.isQuiet(ChimeConfig.load(context), ChoreStore.clock(), ChoreStore.zone())
  }.getOrDefault(true)
}

/** Home overlay for [ChoresCelebration]: a bouncing star banner that hides itself. */
@Composable
internal fun ChoresCelebrationBanner(modifier: Modifier = Modifier) {
  val cheer by ChoresCelebration.cheer.collectAsState()
  val c = cheer ?: return
  val pulse by rememberInfiniteTransition(label = "cheer").animateFloat(
      initialValue = 1f, targetValue = CHEER_PULSE,
      animationSpec = infiniteRepeatable(tween(CHEER_PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
  ApprovedHomeSurface(modifier.padding(bottom = 120.dp).tvFocusable(RoundedCornerShape(36.dp)) { ChoresCelebration.dismiss() },
      radius = 36.dp) {
    Row(Modifier.padding(horizontal = 40.dp, vertical = 26.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)) {
      ChoreKidAvatar(c.avatar, c.color, ChoreTokens.AvatarLarge)
      Column {
        Text("★ ★ ★", color = ChoreTokens.Star, fontSize = 40.sp, modifier = Modifier.scale(pulse))
        Text(c.line, color = ApprovedHomePalette.Text, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
        Text("Goal reached. Well done!", color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 32.sp)
      }
    }
  }
}

private const val CHEER_PULSE = 1.25f
private const val CHEER_PULSE_MS = 420
