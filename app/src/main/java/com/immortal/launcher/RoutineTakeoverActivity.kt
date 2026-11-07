/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.sin

/**
 * The routine takeover: shown at the start time over Home (and the screensaver), kept on while it
 * runs. Back does nothing (kids); a parent closes it with press-and-hold or from Toolkit. When the
 * routine ends (cheer, close, auto-close) it finishes and Home comes back with the toast.
 */
class RoutineTakeoverActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    RoutineEngine.init(this)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
    setContent { TakeoverRoot(onFinished = ::returnHome) }
  }

  override fun onStart() {
    super.onStart()
    RoutineEngine.visible = true
    RoutineScheduler.clearNotification(this)
  }

  override fun onStop() {
    RoutineEngine.visible = false
    super.onStop()
  }

  private fun returnHome() {
    if (isFinishing) return
    runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    finish()
  }
}

@Composable
private fun TakeoverRoot(onFinished: () -> Unit) {
  val screen = RoutineEngine.screen
  val cheer = RoutineEngine.cheer
  var now by remember { mutableLongStateOf(RoutineEngine.repo.clock.nowLocalMs()) }
  // A visible-only ticker for the clock and time bar; it stops with the Activity.
  LaunchedEffect(Unit) { while (true) { now = RoutineEngine.repo.clock.nowLocalMs(); delay(1_000) } }
  val kids = remember(RoutineEngine.revision) { RoutineEngine.repo.doc().kids }
  LaunchedEffect(screen == null && cheer == null) { if (screen == null && cheer == null) onFinished() }
  when {
    cheer != null -> RoutineCheerView(cheer)
    screen != null -> RoutineTakeover(screen, kids, now)
    else -> Box(Modifier.fillMaxSize().background(RoutineColors.Bg))
  }
}

/** The cheer: the routine's pictures, "All done!", the ready line and confetti; Home follows. */
@Composable
internal fun RoutineCheerView(cheer: RoutineCheer) {
  Box(Modifier.fillMaxSize().background(RoutineColors.Bg), contentAlignment = Alignment.Center) {
    RoutineConfetti(90)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        cheer.routine.cards.take(8).forEach { Image(painterResource(RoutinePictureRes.pic(it.pic)), null, Modifier.size(150.dp)) }
      }
      Spacer(Modifier.height(44.dp))
      Text(cheer.title, color = RoutineColors.Text, fontSize = 150.sp, lineHeight = 160.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(22.dp))
      Text(cheer.sub, color = RoutineColors.Muted, fontSize = 38.sp)
      Spacer(Modifier.height(36.dp))
      Text(if (cheer.quiet) "Quiet hours, the cheer is silent" else "", color = RoutineColors.Muted, fontSize = 26.sp)
    }
    Text("Home in a moment", color = RoutineColors.Muted, fontSize = 26.sp,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 24.dp))
  }
}

/** One falling burst of confetti (deterministic pieces, one 1.4 s animation, then it rests). */
@Composable
internal fun RoutineConfetti(count: Int) {
  val fall = remember { Animatable(0f) }
  LaunchedEffect(Unit) { fall.animateTo(1f, tween(1_400)) }
  val colors = remember { listOf(0xFF66C49E, 0xFFF4DB93, 0xFF8EC5F0, 0xFFFF9FBF, 0xFFFFFFFF, 0xFFFFCB47).map { Color(it) } }
  Canvas(Modifier.fillMaxSize()) {
    fun rnd(i: Int, k: Int): Float { val v = sin(i * 12.9898 + (k + 1) * 78.233) * 43758.5453; return (v - floor(v)).toFloat() }
    repeat(count) { i ->
      val w = (14 + rnd(i, 2) * 22).dp.toPx()
      val round = rnd(i, 3) < 0.35f
      val h = if (round) w else w * 0.42f
      val x = rnd(i, 1) * size.width
      val rest = if (i % 6 == 0) size.height * (0.15f + rnd(i, 4) * 0.55f) else size.height * (0.92f + rnd(i, 4) * 0.08f)
      val y = -h + (rest + h) * fall.value
      if (round) drawCircle(colors[i % colors.size], w / 2, Offset(x, y))
      else drawRect(colors[i % colors.size], Offset(x, y), Size(w, h))
    }
  }
}
