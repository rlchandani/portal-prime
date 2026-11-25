/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared Control Center primitives, one per mock CSS class, so every screen looks like the spec:
 * [CcButton] (`.btn`), [CcSeg] (`.seg`), [CcSectionLabel] (uppercase section heads),
 * [CcToggle] (`.tg`), [CcRoundButton] (`.hbtn`), [CcTag] (`.tag`), [CcCardBox] (`.card`).
 */
internal enum class CcButtonStyle { TONAL, PRIMARY, EMBER, DANGER }

@Composable
internal fun CcButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    style: CcButtonStyle = CcButtonStyle.TONAL,
    enabled: Boolean = true,
    height: Int = 76,
    /** Screen-reader name when [label] repeats on the screen (e.g. each alert's Close); visual unchanged. */
    spoken: String = label,
) {
  val c = LocalCc.current
  val (bg, fg) = when (style) {
    CcButtonStyle.TONAL -> c.surface2 to c.text
    CcButtonStyle.PRIMARY -> c.text to c.bg
    CcButtonStyle.EMBER -> CcStyle.Ember to CcStyle.EmberInk
    CcButtonStyle.DANGER -> c.red to Color.White
  }
  Row(modifier.height(height.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill).background(bg)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).ccSpeak(spoken).padding(horizontal = 34.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
    icon?.let { CcIcon(it, fg, 30.dp) }
    Text(label, color = fg, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}

/**
 * Segmented control; [options] are (key, label, icon?). A thin adapter over the shared
 * [HearthSegmented]: Control Center keeps its own [CcColors] (so the pill stays `segOn` and the
 * dark hairline/`cardEdge` are honoured), its one-node `ccSpeak` tab semantics, and its
 * [selectedModifier] (used by `CcScrollingSeg` to scroll the selected option into view). Options
 * stay content-sized here — several CC call sites place `CcSeg` in horizontally-scrolling rows
 * where equal-width weights can't apply. The selected pill now glides unless CC's reduced-motion
 * gate ([LocalCcMotion]) or the OS animation-scale is off.
 */
@Composable
internal fun <T> CcSeg(options: List<Triple<T, String, String?>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
                       selectedModifier: Modifier = Modifier) {
  val c = LocalCc.current
  HearthSegmented(
      segments = options.map { (_, label, icon) ->
        HearthSegment(label, icon?.let { name -> { tint -> CcIcon(name, tint, HearthSegmentedTokens.IconSize) } })
      },
      selectedIndex = options.indexOfFirst { it.first == selected },
      colors = HearthSegmentedColors(
          track = c.surface2, pill = c.segOn, pillEdge = if (c.dark) c.cardEdge else Color.Transparent,
          selectedLabel = c.text, unselectedLabel = c.sec, elevated = !c.dark),
      modifier = modifier,
      reduceMotion = rememberReducedMotion() || !LocalCcMotion.current,
      selectedSegmentModifier = selectedModifier,
      cellModifier = { i, on, label ->
        Modifier.clickable(role = Role.Tab) { onSelect(options[i].first) }.ccSpeak(label, selected = on)
      })
}

@Composable
internal fun CcSectionLabel(text: String, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  Text(text.uppercase(), color = c.sec, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, modifier = modifier.ccSpeak(text, heading = true))
}

/** iOS-style switch from the mock (green when on), 88×50. */
@Composable
internal fun CcToggle(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, label: String = "") {
  val c = LocalCc.current
  val x by animateFloatAsState(if (on) 1f else 0f, tween(TOGGLE_MS), label = "toggle")
  Box(Modifier.size(88.dp, 50.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).clip(CcStyle.Pill)
      .background(if (on) c.green else c.surface2).clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }
      .semantics { contentDescription = "$label ${if (on) "on" else "off"}" }) {
    Box(Modifier.padding(5.dp).offset(x = (38 * x).dp).size(40.dp).background(Color.White, CircleShape))
  }
}

@Composable
internal fun CcRoundButton(icon: String, onClick: () -> Unit, description: String, size: Int = 72, modifier: Modifier = Modifier,
                           enabled: Boolean = true, filled: Boolean = false) {
  val c = LocalCc.current
  Box(modifier.size(size.dp).alpha(if (enabled) 1f else CC_DISABLED_ALPHA).then(if (filled) Modifier.clip(CircleShape).background(c.text) else Modifier.ccCard(CircleShape))
      .clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
      contentAlignment = Alignment.Center) {
    CcIcon(icon, if (filled) c.bg else c.text, (size * 0.47f).dp)
  }
}

/** Small status tag (`.tag`, `.tag.green`, amber, red). */
@Composable
internal fun CcTag(text: String, tone: CcTone = CcTone.NEUTRAL) {
  val c = LocalCc.current
  val (bg, fg) = when (tone) {
    CcTone.NEUTRAL -> c.surface2 to c.sec
    CcTone.GREEN -> c.greenBg to c.green
    CcTone.AMBER -> c.amberBg to c.amber
    CcTone.RED -> c.redBg to c.red
  }
  Text(text, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
      // A filled pill, not a clip (nothing inside overflows): one render node fewer per tag.
      modifier = Modifier.background(bg, CcStyle.Pill).padding(horizontal = 14.dp, vertical = 6.dp))
}

internal enum class CcTone { NEUTRAL, GREEN, AMBER, RED }

@Composable
internal fun CcCardBox(modifier: Modifier = Modifier, padding: Int = 24, content: @Composable () -> Unit) {
  Box(modifier.ccCard().padding(padding.dp)) { content() }
}

/** Centred state card used by empty/offline/not-configured screens. */
@Composable
internal fun BoxScope.CcCenteredMessage(icon: String, title: String, body: String, primary: Pair<String, () -> Unit>? = null,
                                        secondary: Pair<String, () -> Unit>? = null, tone: CcTone = CcTone.NEUTRAL,
                                        primaryIcon: String? = null,
                                        /** Drawn between the body and the actions (the unreachable checklist). */
                                        extra: (@Composable () -> Unit)? = null) {
  val c = LocalCc.current
  // Compact: kept off the screen edges (and scrolls if a short landscape window can't hold it).
  val frame = if (currentLayoutClass().compact) Modifier.align(Alignment.Center).padding(horizontal = 30.dp, vertical = 24.dp)
      .widthIn(max = 900.dp).ccCard(CcStyle.Modal).verticalScroll(rememberScrollState())
  else Modifier.align(Alignment.Center).widthIn(max = 900.dp).ccCard(CcStyle.Modal)
  Column(frame.padding(48.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Box(Modifier.size(100.dp).clip(CcStyle.CardSmall).background(when (tone) { CcTone.RED -> c.redBg; CcTone.AMBER -> c.amberBg; else -> c.surface2 }),
        contentAlignment = Alignment.Center) {
      CcIcon(icon, when (tone) { CcTone.RED -> c.red; CcTone.AMBER -> c.amber; else -> c.text }, 50.dp)
    }
    Text(title, color = c.text, fontSize = 50.sp, fontFamily = CcStyle.Serif, lineHeight = 56.sp)
    Text(body, color = c.sec, fontSize = 25.sp, lineHeight = 34.sp)
    extra?.invoke()
    if (primary != null || secondary != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      primary?.let { (l, f) -> CcButton(l, f, icon = primaryIcon, style = CcButtonStyle.PRIMARY) }
      secondary?.let { (l, f) -> CcButton(l, f) }
    }
  }
}

internal const val CC_DISABLED_ALPHA = 0.35f
internal const val CC_DIMMED_ALPHA = 0.55f
private const val TOGGLE_MS = 180
