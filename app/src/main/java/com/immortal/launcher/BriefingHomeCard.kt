/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardHeight = 88.dp
private val CloseTarget = 64.dp
private val CardMaxWidth = 420.dp
private const val MS_PER_MINUTE = 60_000L

/**
 * The "Good morning" card beside the Home greeting, 06:00–11:00 while the briefing is on and until
 * dismissed for the day. Tapping it opens [BriefingActivity]. Settings are read off the main thread.
 */
@Composable
internal fun BriefingHomeCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var refresh by remember { mutableIntStateOf(0) }
  OnResume { refresh++ }
  val visible by produceHomeState(false, refresh) {
    while (true) {
      value = withContext(Dispatchers.IO) { cardVisibleNow(context) }
      homeRefreshDelay(MS_PER_MINUTE - System.currentTimeMillis() % MS_PER_MINUTE)
    }
  }
  if (!visible) return
  val shape = RoundedCornerShape(CardHeight / 2)
  ApprovedHomeSurface(modifier.widthIn(max = CardMaxWidth).heightIn(min = CardHeight), radius = CardHeight / 2) {
    Row(Modifier.padding(start = 8.dp, end = 12.dp).heightIn(min = CardHeight),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.weight(1f, fill = false).heightIn(min = CardHeight)
          .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) { BriefingActivity.open(context) }
          .semantics(mergeDescendants = true) { contentDescription = "Open the morning briefing" }
          .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        ApprovedHomeIcon("sun", Modifier.size(40.dp), ApprovedHomePalette.Accent)
        Column(Modifier.padding(start = 16.dp)) {
          Text(BriefingCopy.CARD_TITLE, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 32.sp,
              fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(BriefingCopy.CARD_SUBTITLE, color = ApprovedHomePalette.Secondary, fontSize = 20.sp,
              lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      Box(Modifier.size(CloseTarget)
          .tvFocusable(CircleShape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) {
            dismissCard(context)
            refresh++
          }
          .semantics { contentDescription = "Hide Good morning for today" },
          contentAlignment = Alignment.Center) {
        ApprovedHomeIcon("close", Modifier.size(26.dp))
      }
    }
  }
}

private fun cardVisibleNow(context: Context): Boolean = runCatching {
  BriefingPolicy.cardVisible(BriefingConfig.load(context), System.currentTimeMillis(), TimeZone.getDefault(),
      BriefingDayMarks.dismissedDay(context))
}.getOrElse { Log.w("ImmortalBriefing", "Good morning card check failed", it); false }

private fun dismissCard(context: Context) {
  BriefingDayMarks.markDismissed(context, BriefingPolicy.localDay(System.currentTimeMillis(), TimeZone.getDefault()))
}
