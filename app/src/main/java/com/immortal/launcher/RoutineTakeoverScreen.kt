/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HOLD_MS = 1_500 // README: press and hold 1.5 s; a tap only shows a hint
private const val HINT_MS = 2_200L

/** The full-screen routine: picture cards, a time bar to Ready by, pips and hold-to-close. */
@Composable
internal fun RoutineTakeover(screen: RoutineScreen, kids: List<RoutineKid>, nowMs: Long) {
  val r = screen.routine
  val kid = screen.kidTab
  val ticked = screen.run.ticks[kid].orEmpty()
  val line = RoutineRunLogic.timeLine(screen.run, nowMs)
  Box(Modifier.fillMaxSize().background(RoutineColors.Bg)) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 36.dp, bottom = 24.dp)) {
      TopRow(screen, kids, nowMs)
      Spacer(Modifier.height(24.dp))
      TimeBar(line)
      Spacer(Modifier.height(44.dp))
      CardGrid(r, ticked, screen.lastTick, Modifier.weight(1f)) { RoutineEngine.tick(it, kid) }
      Spacer(Modifier.height(24.dp))
      Footer(r, ticked, screen.preview)
    }
    screen.chime?.let { ChimeBubble(it, screen.quiet, Modifier.align(Alignment.TopCenter)) }
    screen.kidCheer?.let { KidCheer(r, screen.run, it, kids) }
  }
}

@Composable
private fun TopRow(screen: RoutineScreen, kids: List<RoutineKid>, nowMs: Long) {
  Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(28.dp)) {
    Column(Modifier.weight(1f)) {
      Text(screen.routine.name, color = RoutineColors.Text, fontSize = 54.sp, lineHeight = 64.sp,
          fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (screen.preview) Text("Preview, ticks are not saved", color = RoutineColors.Sun, fontSize = 26.sp)
    }
    KidTabs(screen, kids)
    Text(RoutineDates.clock12(RoutineDates.minuteOfDay(nowMs)), color = RoutineColors.Text, fontSize = 84.sp,
        lineHeight = 88.sp, letterSpacing = (-2).sp)
  }
}

private fun kidOf(kids: List<RoutineKid>, id: String) = kids.firstOrNull { it.id == id } ?: RoutineKid(id, "Everyone", "everyone")

@Composable
private fun KidTabs(screen: RoutineScreen, kids: List<RoutineKid>) {
  val r = screen.routine
  val keys = RoutineRunLogic.kidKeys(r)
  if (keys.size < 2) {
    if (r.kids.isEmpty()) return
    val k = kidOf(kids, r.kids.first())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Image(painterResource(RoutinePictureRes.avatar(k.avatar)), null, Modifier.size(64.dp))
      Text(k.name, color = RoutineColors.Muted, fontSize = 30.sp)
    }
    return
  }
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    keys.forEach { id ->
      val k = kidOf(kids, id)
      val n = RoutineRunLogic.doneCount(r, screen.run, id)
      val on = id == screen.kidTab
      val shape = RoundedCornerShape(44.dp)
      Row(Modifier.heightIn(min = 88.dp).clip(shape).background(if (on) RoutineColors.Selected else RoutineColors.Card)
          .border(2.dp, if (on) RoutineColors.Text else RoutineColors.Border, shape)
          .semantics { role = Role.Tab; selected = on }
          .clickable { RoutineEngine.selectKid(id) }.padding(start = 12.dp, end = 26.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Image(painterResource(RoutinePictureRes.avatar(k.avatar)), null, Modifier.size(64.dp))
        Text(k.name, color = RoutineColors.Text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(if (n == r.cards.size) "✓" else "$n/${r.cards.size}",
            color = if (n == r.cards.size) RoutineColors.Green else RoutineColors.Muted, fontSize = 24.sp)
      }
    }
  }
}

@Composable
private fun TimeBar(line: RoutineRunLogic.TimeLine) {
  val fraction by animateFloatAsState(line.fraction, tween(1000, easing = LinearEasing), label = "routine-time")
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
    Text(line.main, color = if (line.late) RoutineColors.Sun else RoutineColors.Text, fontSize = 38.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.width(16.dp))
    Text(line.sub, color = RoutineColors.Muted, fontSize = 28.sp, modifier = Modifier.weight(1f))
    Text("⌂ ${line.goal}", color = RoutineColors.Muted, fontSize = 28.sp)
  }
  Spacer(Modifier.height(14.dp))
  Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp))
      .background(if (line.late) RoutineColors.LateTrack else RoutineColors.Raised)) {
    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(20.dp)).background(RoutineColors.Green))
  }
}

@Composable
private fun CardGrid(r: Routine, ticked: List<String>, lastTick: String?, modifier: Modifier, onTick: (String) -> Unit) {
  val n = r.cards.size
  val rows = if (n <= 5) listOf(r.cards) else r.cards.chunked((n + 1) / 2)
  val cols = rows.maxOf { it.size }
  val dense = n > 5
  BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    // One or two cards stay card-sized in the middle instead of stretching across the room.
    val width = if (n <= 2) minOf(maxWidth, (380 * n + 12 * (n - 1)).dp) else maxWidth
    Column(Modifier.width(width).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      rows.forEach { row ->
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          row.forEach { c -> PictureCard(c, c.id in ticked, c.id == lastTick, dense, Modifier.weight(1f).fillMaxHeight()) { onTick(c.id) } }
          repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun PictureCard(c: RoutineCard, done: Boolean, fresh: Boolean, dense: Boolean, modifier: Modifier, onTap: () -> Unit) {
  val shape = RoundedCornerShape(if (dense) 28.dp else 32.dp)
  val picScale by animateFloatAsState(if (done) 0.9f else 1f, tween(300), label = "pic")
  val pop = remember(c.id) { Animatable(1f) }
  LaunchedEffect(done, fresh) { if (done && fresh) { pop.snapTo(0.6f); pop.animateTo(1f, tween(350)) } }
  Box(modifier.clip(shape).background(if (done) RoutineColors.DoneCard else RoutineColors.Card)
      .border(2.dp, if (done) RoutineColors.Green else RoutineColors.Border, shape)
      .semantics { contentDescription = c.label + if (done) ", done" else "" }
      .clickable(onClick = onTap)) {
    Column(Modifier.fillMaxSize().padding(horizontal = if (dense) 12.dp else 6.dp, vertical = if (dense) 18.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (dense) 14.dp else 30.dp, Alignment.CenterVertically)) {
      Image(painterResource(RoutinePictureRes.pic(c.pic)), null,
          Modifier.weight(1f, fill = false).sizeIn(maxWidth = 250.dp, maxHeight = 250.dp).aspectRatio(1f).scale(picScale),
          alpha = if (done) 0.5f else 1f)
      // Labels stay 64 sp at every size (PM: readable from 3 m); long ones wrap or ellipsize.
      Text(c.label, color = if (done) RoutineColors.Muted else RoutineColors.Text, fontSize = 64.sp, lineHeight = 70.sp,
          fontWeight = FontWeight.SemiBold, letterSpacing = (-1.6).sp, textAlign = TextAlign.Center,
          maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis)
    }
    val ck = if (dense) 72.dp else 84.dp
    Box(Modifier.align(Alignment.TopEnd).padding(if (dense) 16.dp else 22.dp).size(ck).scale(pop.value).clip(CircleShape)
        .background(if (done) RoutineColors.Green else RoutineColors.Card)
        .border(5.dp, if (done) RoutineColors.Green else RoutineColors.Border, CircleShape), contentAlignment = Alignment.Center) {
      if (done) Text("✓", color = RoutineColors.OnGreen, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun Footer(r: Routine, ticked: List<String>, preview: Boolean) {
  var hint by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(hint) { if (hint != null && hint != HOLDING) { delay(HINT_MS); hint = null } }
  Row(Modifier.fillMaxWidth().heightIn(min = 92.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      r.cards.forEach { c ->
        val on = c.id in ticked
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (on) RoutineColors.Green else RoutineColors.Raised)
            .border(3.dp, if (on) RoutineColors.Green else RoutineColors.Border, CircleShape))
      }
      Spacer(Modifier.width(14.dp))
      Text("${r.cards.count { it.id in ticked }} of ${r.cards.size} done", color = RoutineColors.Muted, fontSize = 30.sp)
    }
    hint?.let {
      Text(if (it == HOLDING) "Keep holding to close it for today" else it, color = RoutineColors.Text, fontSize = 26.sp,
          modifier = Modifier.padding(end = 16.dp).clip(RoundedCornerShape(22.dp)).background(RoutineColors.Sheet)
              .border(1.dp, RoutineColors.Border, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp, vertical = 16.dp))
    }
    if (preview) PillButton("✕  End preview") { RoutineEngine.endPreview() }
    else HoldToClose(onHint = { hint = it })
    // Portal firmware floats its bug-report pill (BugnubPillViewService) in the bottom-right
    // corner over every app; keep the close controls clear of it so they stay pressable.
    Spacer(Modifier.width(FIRMWARE_PILL_CLEARANCE))
  }
}

private val FIRMWARE_PILL_CLEARANCE = 64.dp

private const val HOLDING = "\u0000holding"

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
  Box(Modifier.heightIn(min = 92.dp).clip(RoundedCornerShape(46.dp)).background(RoutineColors.Raised)
      .clickable(onClick = onClick).padding(horizontal = 34.dp), contentAlignment = Alignment.Center) {
    Text(label, color = RoutineColors.Text, fontSize = 26.sp)
  }
}

/** Press and hold 1.5 s with a filling ring; a short tap only shows "Press and hold to close". */
@Composable
private fun HoldToClose(onHint: (String?) -> Unit) {
  val progress = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  val pressing = progress.value > 0f
  Row(Modifier.heightIn(min = 92.dp).clip(RoundedCornerShape(46.dp))
      .background(if (pressing) RoutineColors.Selected else RoutineColors.Raised)
      .semantics { contentDescription = "Press and hold to close the routine for today" }
      .pointerInput(Unit) {
        detectTapGestures(onPress = {
          onHint(HOLDING)
          val job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
            RoutineEngine.holdClose()
          }
          val released = tryAwaitRelease()
          if (job.isActive && released) { job.cancel(); onHint("Press and hold to close") } else { job.cancel(); onHint(null) }
          progress.snapTo(0f)
        })
      }.padding(start = 14.dp, end = 32.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
      Canvas(Modifier.fillMaxSize()) {
        val stroke = 5.dp.toPx()
        drawCircle(RoutineColors.Border, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
        if (progress.value > 0f) drawArc(RoutineColors.Text, -90f, 360f * progress.value, false,
            topLeft = Offset(stroke, stroke), size = androidx.compose.ui.geometry.Size(size.width - 2 * stroke, size.height - 2 * stroke),
            style = Stroke(stroke, cap = StrokeCap.Round))
      }
      Text("✕", color = RoutineColors.Text, fontSize = 26.sp)
    }
    Text("Hold to close", color = if (pressing) RoutineColors.Text else RoutineColors.Muted, fontSize = 26.sp)
  }
}

@Composable
private fun ChimeBubble(text: String, quiet: Boolean, modifier: Modifier) {
  Text(if (quiet) "Quiet hours, no chime" else "Chime: “$text”", color = RoutineColors.Text, fontSize = 28.sp,
      modifier = modifier.padding(top = 150.dp).clip(RoundedCornerShape(40.dp)).background(RoutineColors.Sheet)
          .border(1.dp, RoutineColors.Border, RoundedCornerShape(40.dp)).padding(horizontal = 34.dp, vertical = 20.dp))
}

@Composable
private fun KidCheer(r: Routine, run: RoutineRun, kidId: String, kids: List<RoutineKid>) {
  val k = kidOf(kids, kidId)
  val next = RoutineRunLogic.nextKid(r, run, kidId).takeIf { it != kidId }?.let { kidOf(kids, it).name }
  Box(Modifier.fillMaxSize().background(RoutineColors.Bg.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
    RoutineConfetti(36)
    Row(Modifier.clip(RoundedCornerShape(40.dp)).background(RoutineColors.Sheet).padding(48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
      Image(painterResource(RoutinePictureRes.avatar(k.avatar)), null, Modifier.size(140.dp))
      Column {
        Text("${k.name} is done!", color = RoutineColors.Text, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        if (next != null) Text("$next’s cards are next", color = RoutineColors.Muted, fontSize = 32.sp)
      }
    }
  }
}
