/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home overlay for a due reminder ([ReminderAlerts]): "Reminder · for Maya — Call Dr. Rao's clinic".
 * A private one reads "A private reminder for Maya" unless Maya is here alone. Done ticks it off
 * everywhere (offered only when the title shows); Dismiss hides it on this Portal.
 */
@Composable
internal fun ReminderAlertBanner(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val alert by ReminderAlerts.alert.collectAsState()
  val a = alert ?: return
  val state by ReminderStore.state.collectAsState()
  val ui by WhosThere.state.collectAsState()
  val r = state.reminders[a.reminderId]?.takeIf { it.open } ?: return
  val showTitle = ReminderAlerts.showsTitle(r, ui)
  val owner = ReminderVisibility.ownerLabel(r, ui.people)
  val use24 = remember { DateFormat.is24HourFormat(context) }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val enter = remember(a.reminderId) { Animatable(0f) }
  LaunchedEffect(a.reminderId) { enter.animateTo(1f, tween(360)) }
  ApprovedHomeSurface(modifier.padding(bottom = 120.dp).widthIn(max = 980.dp)
      .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 40f }, radius = 36.dp) {
    Row(Modifier.padding(horizontal = 32.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      Box(Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(ApprovedHomePalette.Chrome), contentAlignment = Alignment.Center) {
        if (showTitle || r.household) ApprovedHomeIcon("bell", Modifier.size(36.dp), ApprovedHomePalette.Accent)
        else WhosThereGlyph(WtGlyph.LOCK, ApprovedHomePalette.Accent, 34.dp)
      }
      Column(Modifier.weight(1f, fill = false)) {
        val time = r.dueMs?.let { ReminderDue.clock(it, TimeZone.getDefault(), use24) }
        Text(listOfNotNull("Reminder", if (r.household) null else "for $owner", time).joinToString(" · "),
            color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp, maxLines = 1)
        Text(if (showTitle) r.title else "A private reminder for $owner", color = ApprovedHomePalette.Text,
            fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
      if (showTitle) ApprovedHomePillButton("Done") {
        scope.launch { withContext(Dispatchers.IO) { runCatching { ReminderAlerts.markDone(context, r.id) } } }
      }
      ApprovedHomeTextButton("Dismiss") { ReminderAlerts.dismiss() }
    }
  }
}
