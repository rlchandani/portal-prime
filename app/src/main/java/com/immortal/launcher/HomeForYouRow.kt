/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.immortal.launcher.memory.ForYouChips
import com.immortal.launcher.memory.ForYouFeedback.Kind
import com.immortal.launcher.memory.ForYouHome
import com.immortal.launcher.memory.JarvisKnowsActivity
import com.immortal.launcher.memory.MemoryUsage
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Home's "For you right now" row (mock `mocks/jarvis-personalization-20261122`, Home: the row between the header and
 * the widgets): up to three chips from what's usually opened here around now, the best with the ember outline, each
 * saying why. Tap opens the why-sheet (Open · Show less often · Don't suggest this); ✕ is "not now". Nothing shows
 * with the switch off, for guests, children and teens, or before a habit has formed, so the row costs no space until
 * it has something to say. Never spoken.
 */
@Composable
internal fun HomeForYouRow(
    apps: List<AppEntry>,
    onLaunch: (ComponentName) -> Unit,
    onOpenMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current.applicationContext
  val active = LocalApprovedLauncherPageActive.current
  val who by WhosThere.homeView.collectAsState()
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(active) { while (active) { delay(REFRESH_MS); tick++ } }
  val snapshot by produceState<ForYouHome.Snapshot?>(null, active, who, tick) {
    if (active) value = withContext(Dispatchers.IO) { runCatching { ForYouHome.load(context) }.getOrNull() }
  }
  val snap = snapshot ?: return
  var hidden by remember(snap.scope) { mutableStateOf(emptySet<String>()) }
  var why by remember { mutableStateOf<ForYouChips.Chip?>(null) }
  var toast by remember { mutableStateOf<Pair<String, (() -> Unit)?>?>(null) }
  LaunchedEffect(toast) { if (toast != null) { delay(TOAST_MS); toast = null } }
  LaunchedEffect(active) { if (!active) { hidden = emptySet(); why = null } }
  val icons = remember(apps) { apps.associate { MemoryUsage.surfaceForApp(it.component.packageName) to it } }
  val chips = remember(snap, icons, hidden) {
    ForYouChips.build(snap.ranked.filter { it.surface !in hidden }, snap.household) { target(it, icons) }
  }
  val dismissedAll = chips.isEmpty() && hidden.isNotEmpty()
  if (chips.isEmpty() && !dismissedAll && toast == null) return
  LaunchedEffect(chips.map { it.surface }, tick) {
    chips.forEach { ForYouHome.note(context, snap.scope, it.surface, Kind.SHOWN) }
  }
  val open: (ForYouChips.Chip) -> Unit = { chip ->
    ForYouHome.note(context, snap.scope, chip.surface, Kind.OPENED)
    when (chip.surface) {
      com.immortal.launcher.memory.ForYouRanker.SCREEN_MUSIC -> onOpenMusic()
      com.immortal.launcher.memory.ForYouRanker.SCREEN_CALLS -> onLaunch(CALLS_COMPONENT)
      com.immortal.launcher.memory.MemoryHandoff.JARVIS_SURFACE -> onLaunch(ComponentName(JarvisHandoff.PACKAGE, ""))
      else -> icons[chip.surface]?.let { onLaunch(it.component) }
    }
  }
  val t = forYouTokens()
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.gap)) {
    if (snap.paused && !snap.household) ForYouBanner("Learning paused.", "Suggestions use what Jarvis already knows.", t,
        action = "Resume") { ForYouHome.resume(context, snap.scope) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Text(ForYouChips.heading(snap.household).uppercase(), color = ApprovedHomePalette.Secondary, fontSize = t.label,
          fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em, maxLines = 1, modifier = Modifier.weight(1f))
      toast?.let { (text, undo) -> ForYouToast(text, undo, t) { toast = null } }
      if (!snap.household) ForYouPill("What Jarvis knows", "jarvis", t) { JarvisKnowsActivity.open(context) }
    }
    if (dismissedAll) {
      ForYouBanner(null, "No more suggestions right now.", t)
    } else {
      val chipRow: @Composable (Modifier) -> Unit = { m ->
        chips.forEach { chip ->
          ForYouChip(chip, icons[chip.surface]?.icon, t, m, onOpen = { why = chip }) {
            ForYouHome.note(context, snap.scope, chip.surface, Kind.NOT_NOW)
            hidden = hidden + chip.surface
          }
        }
      }
      if (t.stacked) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { chipRow(Modifier.fillMaxWidth()) }
      else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        chipRow(Modifier.widthIn(max = t.chipMax))
      }
    }
  }
  why?.let { chip ->
    ForYouWhySheet(chip, snap.household, onDismiss = { why = null },
        onOpen = { why = null; open(chip) },
        onLessOften = {
          why = null
          ForYouHome.note(context, snap.scope, chip.surface, Kind.LESS_OFTEN)
          toast = "Will show this less often" to null
        },
        onNever = {
          why = null
          ForYouHome.note(context, snap.scope, chip.surface, Kind.NEVER)
          hidden = hidden + chip.surface
          toast = "Won't suggest this again" to {
            ForYouHome.allowAgain(context, snap.scope, chip.surface)
            hidden = hidden - chip.surface
          }
        })
  }
}

private const val REFRESH_MS = 10 * 60_000L
private const val TOAST_MS = 4_000L
private val Ember = Color(0xFFD9773A)
private val EmberInk = Color(0xFF1B120B)

/** The mock's per-device tokens: Portal+ (`plus`), the Mini turned (`miniL`) and upright (`miniP`). */
private class ForYouTokens(
    val chipHeight: Dp, val chipMax: Dp, val target: Dp, val gap: Dp, val title: TextUnit, val small: TextUnit,
    val label: TextUnit, val button: TextUnit, val stacked: Boolean,
)

@Composable
private fun forYouTokens(): ForYouTokens {
  val layout = currentLayoutClass()
  return when {
    !layout.compact -> ForYouTokens(92.dp, 620.dp, 72.dp, 24.dp, 23.sp, 18.sp, 21.sp, 22.sp, stacked = false)
    layout.compactPortrait -> ForYouTokens(84.dp, 640.dp, 64.dp, 16.dp, 20.sp, 16.sp, 17.sp, 20.sp, stacked = true)
    else -> ForYouTokens(84.dp, 460.dp, 64.dp, 18.dp, 20.sp, 16.sp, 17.sp, 20.sp, stacked = false)
  }
}

private fun target(surface: String, apps: Map<String, AppEntry>): ForYouChips.Target? {
  ForYouChips.screenTarget(surface)?.let { return it }
  val app = apps[surface]?.takeIf { it.component != CALLS_COMPONENT } ?: return null
  return if (app.component.packageName == JarvisHandoff.PACKAGE) ForYouChips.Target(ForYouChips.JARVIS, "Ask Jarvis", "jarvis")
  else ForYouChips.Target(surface, app.label, null)
}

@Composable
private fun ForYouChip(chip: ForYouChips.Chip, icon: ImageBitmap?, t: ForYouTokens, modifier: Modifier,
    onOpen: () -> Unit, onNotNow: () -> Unit) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(50)
  val fill = if (chip.top) Brush.verticalGradient(listOf(p.surface, lerpEmber(p.surface)))
      else Brush.verticalGradient(listOf(p.surface, p.surface))
  Row(modifier.height(t.chipHeight).clip(shape).background(fill)
      .border(1.5.dp, if (chip.top) Ember else p.outline, shape)
      .padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.weight(1f, fill = false).heightIn(min = t.chipHeight).clip(shape)
        .tvFocusable(shape, focusScale = 1f, ringColor = p.accent, onClick = onOpen)
        .semantics { contentDescription = "${chip.title}. ${chip.why}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Box(Modifier.size(56.dp).clip(CircleShape).background(if (chip.top) Ember else p.surface2),
          contentAlignment = Alignment.Center) {
        when {
          chip.glyph != null -> ApprovedHomeIcon(chip.glyph, Modifier.size(28.dp), tint = if (chip.top) EmberInk else p.text)
          icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
        }
      }
      Column(Modifier.padding(end = 6.dp)) {
        Text(chip.title, color = p.text, fontSize = t.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(chip.why, color = p.secondary, fontSize = t.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp))
      }
    }
    Box(Modifier.size(t.target).clip(CircleShape)
        .tvFocusable(CircleShape, ringColor = p.accent, onClick = onNotNow)
        .semantics { contentDescription = "Not now: ${chip.title}" }, contentAlignment = Alignment.Center) {
      ApprovedHomeIcon("x", Modifier.size(24.dp), tint = p.secondary)
    }
  }
}

/** The top chip's wash: ember at 10% over the surface (mock `.chip.top`). */
private fun lerpEmber(surface: Color): Color = androidx.compose.ui.graphics.lerp(surface, Ember, 0.10f)

@Composable
private fun ForYouPill(label: String, glyph: String?, t: ForYouTokens, onClick: () -> Unit) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(50)
  Row(Modifier.height(t.target).clip(shape).background(p.surface).border(1.5.dp, p.outline, shape)
      .tvFocusable(shape, ringColor = p.accent, onClick = onClick).padding(horizontal = 24.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    if (glyph != null) ApprovedHomeIcon(glyph, Modifier.size(24.dp), tint = p.text)
    Text(label, color = p.text, fontSize = t.button, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

@Composable
private fun ForYouBanner(bold: String?, text: String, t: ForYouTokens, action: String? = null, onAction: () -> Unit = {}) {
  val p = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(24.dp)).background(p.surface2)
      .padding(start = 22.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(buildAnnotatedString {
      if (bold != null) { withStyle(SpanStyle(color = p.text, fontWeight = FontWeight.SemiBold)) { append("$bold ") } }
      append(text)
    }, color = p.secondary, fontSize = t.small * 1.15f, modifier = Modifier.weight(1f))
    if (action != null) ForYouPill(action, null, t, onAction)
  }
}

@Composable
private fun ForYouToast(text: String, undo: (() -> Unit)?, t: ForYouTokens, onDone: () -> Unit) {
  val p = LocalApprovedPalette.current
  Row(Modifier.heightIn(min = t.target).clip(RoundedCornerShape(24.dp)).background(p.text).padding(horizontal = 22.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(text, color = p.background, fontSize = t.small, maxLines = 1)
    undo?.let { u ->
      Box(Modifier.heightIn(min = t.target).clip(RoundedCornerShape(50))
          .tvFocusable(RoundedCornerShape(50), ringColor = p.accent) { u(); onDone() }.padding(horizontal = 14.dp),
          contentAlignment = Alignment.Center) {
        Text("Undo", color = Ember, fontSize = t.small, fontWeight = FontWeight.Bold)
      }
    }
  }
}

/** The why-sheet (mock `whySheet`): where the suggestion came from, and Open · Show less often · Don't suggest this. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ForYouWhySheet(chip: ForYouChips.Chip, household: Boolean, onDismiss: () -> Unit, onOpen: () -> Unit,
    onLessOften: () -> Unit, onNever: () -> Unit) {
  val p = LocalApprovedPalette.current
  ApprovedHomeDialog(chip.title, onDismiss) {
    Text(buildAnnotatedString {
      withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = p.text)) { append("Why you're seeing this: ") }
      append(ForYouChips.explanation(chip, household))
    }, color = p.secondary, fontSize = 21.sp, lineHeight = 30.sp)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SheetPill("Open", p.accent, p.background, onOpen)
      SheetPill("Show less often", p.surface2, p.text, onLessOften)
      SheetPill("Don't suggest this", p.surface2, p.error, onNever)
    }
  }
}

@Composable
private fun SheetPill(label: String, fill: Color, ink: Color, onClick: () -> Unit) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(50)
  Box(Modifier.heightIn(min = 72.dp).clip(shape).background(fill)
      .tvFocusable(shape, ringColor = p.accent, onClick = onClick).padding(horizontal = 24.dp),
      contentAlignment = Alignment.Center) {
    Text(label, color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}
