/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ScreenPadding = 40.dp
private val SectionGap = 20.dp
private val CardPadding = 28.dp
private val DismissHeight = 72.dp

/**
 * The briefing card, drawn with the Home palette tokens (light and dark). [briefing] null is the
 * first frame, before any data has been read: the greeting alone.
 */
@Composable
internal fun BriefingScreen(briefing: Briefing?, onDismiss: () -> Unit) {
  Column(Modifier.fillMaxSize().background(ApprovedHomePalette.Background).padding(ScreenPadding)) {
    BriefingHeader(briefing, onDismiss)
    Spacer(Modifier.height(28.dp))
    if (briefing == null) {
      Text("Getting your morning ready…", color = ApprovedHomePalette.Secondary, fontSize = 26.sp, lineHeight = 34.sp)
      return@Column
    }
    val (left, right) = briefing.sections.partition {
      it.kind == BriefingSectionKind.WEATHER || it.kind == BriefingSectionKind.CALENDAR
    }
    if (currentLayoutClass().compactPortrait) {
      // The Mini upright: one full-width column (two would leave ~290 dp per card and cut lines short).
      Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(SectionGap)) {
        SectionColumn(left, Modifier.fillMaxWidth())
        SectionColumn(right, Modifier.fillMaxWidth())
      }
    } else Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(SectionGap)) {
      SectionColumn(left, Modifier.weight(1f))
      SectionColumn(right, Modifier.weight(1f))
    }
    briefing.note?.let { BriefingNote(it) }
  }
}

/** The personal briefing's privacy promise, quiet, under the sections. */
@Composable
private fun BriefingNote(note: String) {
  Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
    ApprovedHomeIcon("lock", Modifier.size(24.dp), ApprovedHomePalette.Secondary)
    Text(note, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 28.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
  }
}

@Composable
private fun BriefingHeader(briefing: Briefing?, onDismiss: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      // Upright on the Mini the greeting may take a second line rather than lose the name.
      Text(briefing?.title ?: BriefingCopy.CARD_TITLE, color = ApprovedHomePalette.Text, fontSize = 52.sp,
          lineHeight = 58.sp, fontWeight = FontWeight.Bold, maxLines = if (currentLayoutClass().compactPortrait) 2 else 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.semantics { heading() })
      briefing?.subtitle?.let {
        Text(it, color = ApprovedHomePalette.Secondary, fontSize = 26.sp, lineHeight = 34.sp,
            modifier = Modifier.padding(top = 6.dp))
      }
    }
    DismissButton(onDismiss)
  }
}

@Composable
private fun DismissButton(onClick: () -> Unit) {
  val shape = RoundedCornerShape(DismissHeight / 2)
  Box(Modifier.heightIn(min = DismissHeight).clip(shape).background(ApprovedHomePalette.Accent)
      .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Text, onClick = onClick)
      .semantics { contentDescription = "Dismiss the morning briefing" }
      .padding(horizontal = 36.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
    Text(BriefingCopy.DISMISS, color = ApprovedHomePalette.Background, fontSize = 26.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun SectionColumn(sections: List<BriefingSection>, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(SectionGap)) {
    sections.forEach { SectionCard(it) }
  }
}

@Composable
private fun SectionCard(section: BriefingSection) {
  ApprovedHomeSurface(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(CardPadding)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ApprovedHomeIcon(section.kind.icon, Modifier.size(30.dp), ApprovedHomePalette.Accent)
        Text(section.heading, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 28.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp).semantics { heading() })
      }
      Spacer(Modifier.height(14.dp))
      section.lines.forEachIndexed { index, line ->
        val lead = index == 0 && !section.muted
        Text(line, color = if (section.muted) ApprovedHomePalette.Secondary else ApprovedHomePalette.Text,
            fontSize = if (lead) 30.sp else 24.sp, lineHeight = if (lead) 38.sp else 32.sp,
            fontWeight = if (lead) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
      }
    }
  }
}
