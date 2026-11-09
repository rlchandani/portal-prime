/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * Closed Now Playing (top bar, artwork, column): fades and lifts as the sheet opens, read in the
 * draw phase. Past the hand-over point it stops taking touches and leaves the accessibility tree.
 */
@Composable
internal fun ClosedLayer(
    layout: MusicStageLayout,
    motion: MusicStageMotion,
    active: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
  Box(Modifier.fillMaxSize().graphicsLayer {
    val f = motion.frame(layout)
    alpha = f.nowAlpha
    translationY = f.lift.dp.toPx()
  }.then(if (active) Modifier else Modifier.clearAndSetSemantics { })) {
    content()
    if (!active) TouchShield()
  }
}

/**
 * The header strip beside the player while open: a tap or a drag down closes the sheet. Only the
 * strip (and the sheet's bar) take drags, so the scrubber and volume never fight the sheet.
 */
@Composable
internal fun StripLayer(
    layout: MusicStageLayout,
    motion: MusicStageMotion,
    closedActive: Boolean,
    onSettled: (Boolean, Float) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
  val none = remember { MutableInteractionSource() }
  Box(Modifier.stageOffset { layout.stripTextX to motion.frame(layout).player.top }
      .width((layout.width - layout.spec.pad - layout.stripTextX).dp).height(layout.spec.playerH.dp)
      .graphicsLayer {
        val f = motion.frame(layout)
        alpha = f.stripAlpha
        translationY = f.stripDrop.dp.toPx()
      }
      .then(if (closedActive) Modifier.clearAndSetSemantics { } else Modifier
          .musicSheetDrag(motion, layout.sheetTravel, onStart = {}, onSettled = onSettled)
          .clickable(none, indication = null, onClick = onClose)
          .semantics { contentDescription = "Now playing. Tap to show it full screen" }),
      contentAlignment = Alignment.CenterStart) {
    content()
    if (closedActive) TouchShield()
  }
}

/** Swallows every touch over an inactive layer (it is still composed, just faded out). */
@Composable
private fun BoxScope.TouchShield() {
  Box(Modifier.matchParentSize().pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
  })
}

/** "Video · Official YouTube player" beside the closed player (large Portal). */
@Composable
internal fun MusicV3PlayerCaption(modifier: Modifier) {
  val ink = LocalMusicStageInk.current
  Column(modifier, verticalArrangement = Arrangement.Bottom) {
    Text(MusicV3StageCopy.PLAYER_CAPTION_TITLE, color = ink.ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    Text(MusicV3StageCopy.PLAYER_CAPTION_DETAIL, color = ink.muted, fontSize = 21.sp)
  }
}

/** "Quiet hours · volume stays low until 7:00 am" while the night cap is on, re-read each [now] minute. */
@Composable
internal fun rememberQuietNote(now: Long): String? {
  val context = LocalContext.current
  return remember(now) {
    runCatching {
      if (MusicNightGuard.state(context)?.active != true) return@runCatching null
      val end = ChimeConfig.load(context).quietEndMin
      val at = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, end / 60)
        set(Calendar.MINUTE, end % 60)
      }
      MusicV3StageCopy.quietNote(DateFormat.getTimeFormat(context).format(at.time))
    }.getOrNull()
  }
}

/** The stage's one toast at a time: short for confirmations, long for problems or with an action. */
@Stable
internal class MusicStageToasts {
  data class Item(val seq: Long, val text: String, val long: Boolean, val action: Pair<String, () -> Unit>?)

  var current by mutableStateOf<Item?>(null)
    private set
  private var seq = 0L

  fun show(text: String, long: Boolean = true, action: Pair<String, () -> Unit>? = null) {
    current = Item(++seq, text, long || action != null, action)
  }

  fun dismiss(item: Item) {
    if (current?.seq == item.seq) current = null
  }

  @Composable
  fun Effects() {
    val item = current
    LaunchedEffect(item?.seq) {
      if (item == null) return@LaunchedEffect
      delay(if (item.long) MusicV3StageCopy.TOAST_LONG_MS else MusicV3StageCopy.TOAST_SHORT_MS)
      dismiss(item)
    }
  }

  /** Draws the toast inside [zone] (a rect the geometry keeps clear of the player). */
  @Composable
  fun Host(zone: MusicRect, bottom: Boolean, end: Boolean = false) {
    val item = current ?: return
    val align = when {
      bottom -> Alignment.BottomCenter
      end -> Alignment.TopEnd
      else -> Alignment.TopCenter
    }
    Box(Modifier.rect(zone)) {
      MusicStageToast(item.text, Modifier.align(align).widthIn(max = zone.width.dp),
          action = item.action?.let { (label, run) -> label to { dismiss(item); run() } })
    }
  }
}
