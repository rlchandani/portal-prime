/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YtAccount
import java.util.Date
import kotlinx.coroutines.delay

private const val MINUTE_MS = 60_000L

/** Wall-clock milliseconds, refreshed on each minute boundary. */
@Composable
internal fun rememberMinuteClock(): Long {
  val now by produceState(System.currentTimeMillis()) {
    while (true) {
      val t = System.currentTimeMillis()
      value = t
      delay(MINUTE_MS - t % MINUTE_MS)
    }
  }
  return now
}

/**
 * The stage's top bar: Back to Home, the Hearthpane wordmark, the clock and the account chip
 * ([account] null hides it: guests, or no one signed in).
 */
@Composable
internal fun MusicV3TopBar(
    spec: MusicStageSpec,
    now: Long,
    account: YtAccount?,
    accountsOpen: Boolean,
    onBack: () -> Unit,
    onAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val context = LocalContext.current
  Row(modifier.fillMaxWidth().height(spec.top.dp).padding(horizontal = spec.edge.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    MusicV3RoundButton("Back to Home", Modifier.clip(CircleShape).border(1.dp, ink.line, CircleShape),
        size = dims.target.dp, fill = ink.fill, onClick = onBack) {
      ApprovedIcon("back", Modifier.size(30.dp), ink.ink)
    }
    HearthpaneWordmark(ink.muted, fontSize = if (spec.compact) 20.sp else 26.sp)
    Spacer(Modifier.weight(1f))
    Text(DateFormat.getTimeFormat(context).format(Date(now)), color = ink.muted,
        fontSize = if (spec.compact) 19.sp else 24.sp, fontWeight = FontWeight.SemiBold)
    if (account != null) AccountChip(account, accountsOpen, onAccounts)
  }
}

@Composable
private fun AccountChip(account: YtAccount, open: Boolean, onClick: () -> Unit) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val shape = RoundedCornerShape(999.dp)
  val avatar = if (dims == MusicStageDims.COMPACT) 48.dp else 58.dp
  Row(Modifier.height(dims.target.dp).widthIn(max = 420.dp).clip(shape).background(ink.fill).border(1.dp, ink.line, shape)
      .tvFocusable(shape, ringColor = ink.ember, onClick = onClick)
      .semantics { contentDescription = "Signed in as ${account.title}. ${if (open) "Close accounts" else "Switch accounts"}" }
      .padding(start = 10.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    MusicV3Avatar(account.title, account.thumbnailUrl, avatar)
    Text(account.title, color = ink.ink, fontSize = if (dims == MusicStageDims.COMPACT) 18.sp else 22.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    MusicV3GlyphIcon(if (open) MusicV3Glyph.UP else MusicV3Glyph.DOWN, ink.muted, Modifier.size(20.dp))
  }
}
