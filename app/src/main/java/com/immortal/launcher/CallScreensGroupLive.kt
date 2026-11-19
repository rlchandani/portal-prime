/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The Add person control: "Add person", "Full · 4" (opens the sheet that explains), or disabled. */
@Immutable
internal data class CallAddControl(val label: String, val look: ControlLook, val onClick: () -> Unit) {
  companion object {
    fun of(call: ActiveCall, context: Context, open: () -> Unit): CallAddControl {
      val possible = call.kind == CallKind.CALL && !call.peer.phone && call.phase == CallPhase.LIVE &&
          CallingConfig.load(context).groupsOn
      return when {
        !possible -> CallAddControl(ADD, ControlLook.DISABLED) {}
        call.full -> CallAddControl("Full · ${CallGroup.MAX_PEOPLE}", ControlLook.PLAIN, open)
        else -> CallAddControl(ADD, ControlLook.PLAIN, open)
      }
    }

    private const val ADD = "Add person"
  }
}

/** A group call on screen: tiles, the live bar with "N of 4", the same controls, volume. */
@Composable
internal fun CallGroupLive(call: ActiveCall, media: CallMedia?, motion: Boolean, layout: LayoutClass, volumeOpen: Boolean,
                           onVolume: () -> Unit, add: CallAddControl, toast: CallToastState, probe: Boolean = false) {
  val edge = (CallsCompactLayout.edge(layout) + TILE_INSET).dp
  val ctl = CallsCompactLayout.controls(layout)
  val framing = { toast.show("Portal frames you automatically", "follow") }
  Box(Modifier.fillMaxSize()) {
    CallGroupStage(call, media, motion, layout, probe = probe)
    CallLiveBar(CallTiles.liveBar(call) + if (probe) " · +1 test peer" else "", CallingCopy.liveDotActive(call.cameraOn, call.micOn), motion,
        Modifier.align(Alignment.TopStart).padding(edge))
    if (volumeOpen) CallVolumePopover(Modifier.align(Alignment.BottomCenter).padding(bottom = (ctl.bottom + ctl.height).dp + POPOVER_GAP))
    val bottom = Modifier.align(Alignment.BottomCenter).padding(bottom = ctl.bottom.dp)
    if (layout.compact) CompactCallControls(call, ctl, volumeOpen, onVolume, framing, add, bottom)
    else CallControls(call, volumeOpen, onVolume, framing, add, bottom)
  }
}

private const val TILE_INSET = 4
private val POPOVER_GAP = 24.dp
