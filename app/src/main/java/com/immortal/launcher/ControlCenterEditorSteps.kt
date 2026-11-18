/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Right column of the editor: the current step's card and its buttons. */
@Composable
internal fun ColumnScope.CcEditorSide(ctx: CcEdCtx) {
  when (ctx.st.step) {
    CcEdStep.FOUND -> CcStepFound(ctx)
    CcEdStep.ROOMS -> CcStepRooms(ctx)
    CcEdStep.DEVICES -> CcStepDevices(ctx)
    CcEdStep.OPENINGS -> CcStepOpenings(ctx)
    CcEdStep.READY -> CcStepReady(ctx)
  }
  if (!ctx.st.first) CcEditSaveBar(ctx)
}

/** Mock `.card.wiz`: "Step N of 5", serif title, explanation, an optional status tag, then content. */
@Composable
internal fun CcWizCard(ctx: CcEdCtx, body: String, modifier: Modifier = Modifier, tag: Pair<String, CcTone>? = null,
                       scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
  val c = LocalCc.current
  Column(modifier.fillMaxWidth().ccCard().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
      .padding(horizontal = 28.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(ctx.st.stepLabel, color = c.sec, fontSize = 18.sp)
    Text(ctx.st.step.title, color = c.text, fontSize = 38.sp, fontFamily = CcStyle.Serif, lineHeight = 44.sp)
    Text(body, color = c.sec, fontSize = 21.sp, lineHeight = 29.sp)
    tag?.let { (t, tone) -> CcTag(t, tone) }
    content()
    CcEdMessage(ctx.st.message)
  }
}

/** Back + Continue during the first build. */
@Composable
internal fun CcWizNav(ctx: CcEdCtx, continueLabel: String = "Continue") {
  if (!ctx.st.first) return
  Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    if (ctx.st.step.ordinal > CcEdStep.FOUND.ordinal) CcButton("Back", { ctx.update(CcEd::previous) })
    CcButton(continueLabel, { ctx.update(CcEd::next) }, style = CcButtonStyle.PRIMARY, enabled = CcEd.canContinue(ctx.st))
  }
}

/** Mock `.areaR`: a soft row with an icon, a label and a right-aligned note. */
@Composable
internal fun CcAreaRow(icon: String, label: String, note: String, iconTint: androidx.compose.ui.graphics.Color? = null,
                       highlighted: Boolean = false, onClick: (() -> Unit)? = null) {
  val c = LocalCc.current
  val base = Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(CcStyle.CardSmall).background(c.surface2)
  Row((if (onClick != null) base.ccPress(label, onClick) else base)
      .then(if (highlighted) Modifier.ccOutline(CcStyle.Ember) else Modifier).padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
    CcIcon(icon, iconTint ?: c.text, 26.dp)
    Text(label, color = c.text, fontSize = 21.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
    Text(note, color = c.sec, fontSize = 18.sp, maxLines = 1)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.CcStepFound(ctx: CcEdCtx) {
  val c = LocalCc.current
  val counts = remember(ctx.reg, ctx.live) { CcEdFound.counts(ctx.reg, ctx.live) }
  val body = when {
    !ctx.hasAreas -> "Home Assistant didn't share its floors and areas — reading the registry needs an administrator's token. " +
        "You can still draw rooms and name them yourself, then drag devices in."
    counts.areas == 0 -> "Home Assistant has no areas yet. Add them in HA (Settings › Areas) to place devices automatically, or draw and name rooms here."
    else -> "Floors and areas come straight from Home Assistant. You only draw the shapes."
  }
  val tag = if (ctx.hasAreas) "${counts.floors} ${plural(counts.floors, "floor")} · ${counts.areas} ${plural(counts.areas, "area")}" to CcTone.GREEN
  else "No areas from Home Assistant" to CcTone.AMBER
  CcWizCard(ctx, body, tag = tag, scroll = true, modifier = Modifier.weight(1f, fill = false)) {
    if (ctx.hasAreas) CcEd.floors(ctx.reg, ctx.st.plan).forEach { f ->
      val areas = CcPlanEdit.areasFor(ctx.reg, f.id)
      if (areas.isEmpty()) return@forEach
      CcSectionLabel("${f.name} · ${areas.size} ${plural(areas.size, "area")}", Modifier.padding(top = 8.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        areas.forEach { a -> key(a.id) { CcAreaChip(a.name) } }
      }
    }
    Spacer(Modifier.height(6.dp))
    if (counts.withArea > 0) CcAreaRow("sparkle", "${counts.withArea} ${plural(counts.withArea, "device")} with an area", "auto-placed later")
    CcAreaRow(if (counts.withoutArea > 0) "warn" else "check", "${counts.withoutArea} ${plural(counts.withoutArea, "device")} without an area",
        if (counts.withoutArea > 0) "you'll drag these" else "nothing to drag")
  }
  // Kept outside the scrolling card so the next step is always in reach, however many areas HA has.
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    CcButton("Start drawing", { ctx.update(CcEd::next) }, style = CcButtonStyle.PRIMARY)
    if (ctx.hasAreas && counts.areas > 0) CcButton("Suggest a layout", { ctx.update { CcEd.suggest(CcEd.next(it), ctx.reg) } }, icon = "sparkle")
  }
}

@Composable
private fun ColumnScope.CcStepReady(ctx: CcEdCtx) {
  val sum = remember(ctx.st.plan) { CcEd.summary(ctx.st.plan) }
  val live = ctx.canControl
  val body = "${sum.rooms} ${plural(sum.rooms, "room")} · ${sum.devices} ${plural(sum.devices, "device")} · " +
      "${sum.openings} ${if (sum.openings == 1) "door or window" else "doors & windows"}."
  val tag = if (live) "Saves to Home Assistant" to CcTone.GREEN else "Saves on this Portal until Home Assistant is back" to CcTone.AMBER
  CcWizCard(ctx, body, tag = tag) {
    CcAreaRow("home", "Stored in Home Assistant", "shared", iconTint = LocalCc.current.green)
    Text("The plan lives in your Home Assistant user's data, so every Portal signed in as this HA user shows the same plan. " +
        "Each Portal also keeps a copy, so the map still opens when HA can't be reached.",
        color = LocalCc.current.sec, fontSize = 19.sp, lineHeight = 27.sp)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      CcButton("Back", { ctx.update(CcEd::previous) })
      CcButton("Save to Home Assistant", ctx.save, icon = "check", style = CcButtonStyle.EMBER)
    }
  }
}

/** Save / Discard while editing an existing plan; Discard asks once more when there are changes. */
@Composable
private fun CcEditSaveBar(ctx: CcEdCtx) {
  var confirm by remember { mutableStateOf(false) }
  val c = LocalCc.current
  Column(Modifier.fillMaxWidth().ccCard().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(if (ctx.st.dirty) "Unsaved changes" else "No changes yet", color = c.sec, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      CcButton("Save", ctx.save, icon = "check", style = CcButtonStyle.PRIMARY, enabled = ctx.st.dirty)
      CcButton(if (confirm) "Discard changes?" else "Discard", {
        if (!ctx.st.dirty || confirm) ctx.discard() else confirm = true
      }, style = if (confirm) CcButtonStyle.DANGER else CcButtonStyle.TONAL)
    }
  }
}

/** One area name chip in step 1 (takes just the name, so it skips). */
@Composable
private fun CcAreaChip(name: String) {
  val c = LocalCc.current
  Text(name, color = c.text, fontSize = 18.sp, modifier = Modifier.clip(CcStyle.Pill).background(c.surface2).padding(horizontal = 20.dp, vertical = 10.dp))
}

internal fun plural(n: Int, word: String): String = if (n == 1) word else "${word}s"
