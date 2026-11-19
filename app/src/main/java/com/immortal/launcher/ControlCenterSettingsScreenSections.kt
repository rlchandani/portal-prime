/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Portals: this Portal's mode. Other Portals are set on each Portal or from the phone. */
@Composable
internal fun CcSetPortals(mode: String, hasPin: Boolean, ui: CcUi, write: (String, Any) -> Unit, needPin: () -> Unit) {
  val c = LocalCc.current
  var pending by remember { mutableStateOf<String?>(null) }
  CcSetHeading("Portals that show it", "Each Portal picks its own mode. Kids hides what you choose under Kids & guests; " +
      "Banners only keeps the app off this Portal but still shows alerts.")
  CcSetGroup {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(72.dp, 50.dp).alpha(PORTAL_ICON_ALPHA).border(4.dp, c.text, RoundedCornerShape(10.dp)))
      Column(Modifier.weight(1f)) {
        Text("This Portal", color = c.text, fontSize = 22.sp)
        Text(modeLine(mode), color = c.sec, fontSize = 18.sp)
      }
      CcSeg(listOf(Triple(ControlCenterConfig.MODE_FULL, "Full", null), Triple(ControlCenterConfig.MODE_KIDS, "Kids", null),
          Triple(ControlCenterConfig.MODE_BANNERS, "Banners only", null)), pending ?: mode, { target ->
        when (CcSetLogic.modeStep(mode, target, hasPin)) {
          CcModeStep.NOTHING -> { pending = null; write("portalMode", target) }
          CcModeStep.CONFIRM -> pending = target
          CcModeStep.SET_PIN -> { pending = null; needPin() }
        }
      })
    }
  }
  pending?.let { target ->
    CcSetGroup {
      CcSetRow(if (target == ControlCenterConfig.MODE_KIDS) "Switch this Portal to Kids mode?" else "Switch this Portal to Banners only?",
          CcSetLogic.modeConfirm(target), divider = false)
      Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CcButton("Switch", { pending = null; write("portalMode", target); ui.go(CcScreen.MAP) }, style = CcButtonStyle.PRIMARY, height = 64)
        CcButton("Cancel", { pending = null }, height = 64)
      }
    }
  }
  CcSetNote("Other Portals are set on each Portal (Settings › Home Assistant › Control Center) or from Portal Toolkit on your phone.")
}

private fun modeLine(mode: String): String = when (mode) {
  ControlCenterConfig.MODE_KIDS -> "Kids mode"
  ControlCenterConfig.MODE_BANNERS -> "Banners only"
  else -> "Full Control Center"
}

/** Kids & guests: what kids mode hides, and the PIN that leaves it. */
@Composable
internal fun CcSetKids(st: ControlCenterConfig.Settings, hasPin: Boolean, write: (String, Any) -> Unit, changePin: () -> Unit) {
  CcSetHeading("Kids & guests", "Applies when this Portal is in Kids mode. It is a Portal-side lock: Home Assistant has no " +
      "per-entity permissions, so the token this Portal uses can still do everything.")
  CcSetGroup {
    CcSetToggle("Hide door locks", null, "kidsHideLocks", st.kidsHideLocks, write)
    CcSetToggle("Hide the garage door", null, "kidsHideGarage", st.kidsHideGarage, write)
    CcSetToggle("Hide security & alarm", null, "kidsHideSecurity", st.kidsHideSecurity, write)
    CcSetToggle("Hide cameras", null, "kidsHideCameras", st.kidsHideCameras, write)
    CcSetToggle("Hide the water valve", null, "kidsHideValve", st.kidsHideValve, write)
    CcSetToggle("Lock the thermostats", "Shown, but they can't be changed", "kidsLockThermostat", st.kidsLockThermostat, write, divider = false)
  }
  CcSetGroup {
    CcSetRow("Leaving kids mode", if (hasPin) "Asks for the kids PIN on this Portal" else "Set a kids PIN before turning kids mode on", divider = false) {
      CcButton(if (hasPin) "Change PIN" else "Set PIN", changePin, height = 60)
    }
  }
}

/** Ask before…: what confirms first (everything else acts at once with Undo). */
@Composable
internal fun CcSetConfirm(st: ControlCenterConfig.Settings, write: (String, Any) -> Unit) {
  CcSetHeading("Ask before…", "Everything else acts at once and offers Undo for 5 seconds.")
  CcSetGroup {
    CcSetToggle("Unlocking a door", "Locking never asks", "askUnlock", st.askUnlock, write)
    CcSetToggle("Opening or closing the garage", "Shows the garage camera first", "askGarage", st.askGarage, write)
    CcSetRow("Disarming the alarm", "Always needs the PIN, checked by Home Assistant") { CcTag("Always") }
    CcSetToggle("Shutting off the water", null, "askValve", st.askValve, write)
    CcSetToggle("First run of a whole-house scene", "Then one tap with Undo", "askScenes", st.askScenes, write)
    CcSetRow("Locking a door", "Never asks — it acts at once", divider = false) { CcTag("Never") }
  }
}

/** Alerts & banners on this Portal. */
@Composable
internal fun CcSetBanners(st: ControlCenterConfig.Settings, ui: CcUi, write: (String, Any) -> Unit) {
  val quiet = remember { runCatching { ChimeConfig.load(ui.context) }.getOrNull() }
  val quietText = quiet?.let { CcSetLogic.quietLabel(it.quietHoursOn, it.quietStartMin, it.quietEndMin) } ?: QUIET_DEFAULT
  CcSetHeading("Alerts & banners", "Banners appear over any Portal app, like the doorbell card.")
  CcSetGroup {
    CcSetRow("Critical — leak, smoke, CO", "Full screen with sound, even in kids mode · can't be turned off") { CcTag("Always", CcTone.RED) }
    CcSetToggle("Alert banners on this Portal", "Keeps a small Home Assistant connection open for alerts", "bannersListen", st.bannersListen, write)
    CcSetToggle("Warnings — garage, doors, windows left open", "One silent banner, at most once an hour",
        "bannersWarnings", st.bannersWarnings, write)
    CcSetToggle("Shared room", "Only shared-room Portals show left-open banners", "sharedRoom", st.sharedRoom, write)
    CcSetRow("Warn after", "How long something stays open before the banner") {
      CcStepper("${st.warningAfterMin} min", "warning delay", { write("warningAfterMin", CcSetLogic.warnStep(st.warningAfterMin, false)) },
          { write("warningAfterMin", CcSetLogic.warnStep(st.warningAfterMin, true)) }, enabled = st.bannersWarnings)
    }
    CcSetToggle("Info — washer, dryer, dishwasher done", "Never during quiet hours", "bannersInfo", st.bannersInfo, write)
    CcSetToggle("Leak, smoke and CO sound in quiet hours", "Everything else stays silent at night", "criticalSoundAtNight",
        st.criticalSoundAtNight, write)
    CcSetRow("Quiet hours", "$quietText · set in Settings › Sounds", divider = false)
  }
}

/** Plan style & motion, plus the alarm keypad's numbers. */
@Composable
internal fun CcSetLook(st: ControlCenterConfig.Settings, ui: CcUi, write: (String, Any) -> Unit) {
  CcSetHeading("Plan style & motion", "The dark plan reads best from across the room; reduced motion stills every glow and walk.")
  CcSetGroup {
    CcSetRow("Theme") {
      CcSeg(listOf(Triple(true, "Dark", "moon"), Triple(false, "Light", "sun")), st.darkTheme, { write("darkTheme", it) })
    }
    CcSetRow("Animation", "Reduced: no pulses or flowing lines (older Portals, ambient mode)") {
      CcSeg(listOf(Triple(false, "Full", null), Triple(true, "Reduced", null)), st.reducedMotion, { write("reducedMotion", it) })
    }
    CcSetRow("Alarm PIN digits", "How many digits the alarm keypad expects") {
      CcStepper("${st.pinLength}", "PIN digits",
          { write("pinLength", CcSetLogic.stepped(st.pinLength, -1, ControlCenterConfig.MIN_PIN, ControlCenterConfig.MAX_PIN)) },
          { write("pinLength", CcSetLogic.stepped(st.pinLength, 1, ControlCenterConfig.MIN_PIN, ControlCenterConfig.MAX_PIN)) })
    }
    CcSetRow("Exit delay shown when arming", "Match your alarm panel's exit delay in Home Assistant", divider = false) {
      CcStepper("${st.exitDelaySec} s", "exit delay",
          { write("exitDelaySec", CcSetLogic.stepped(st.exitDelaySec, -CcSetLogic.EXIT_STEP, ControlCenterConfig.MIN_EXIT_S, ControlCenterConfig.MAX_EXIT_S)) },
          { write("exitDelaySec", CcSetLogic.stepped(st.exitDelaySec, CcSetLogic.EXIT_STEP, ControlCenterConfig.MIN_EXIT_S, ControlCenterConfig.MAX_EXIT_S)) })
    }
  }
  CcButton("Edit the floor plan", { ui.go(CcScreen.EDIT) }, icon = "edit")
}

/** Home Assistant: connection, registry and where the plan lives. Never shows the server address. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CcSetHomeAssistant(slice: CcSetSlice, ui: CcUi) {
  val (phase, tone) = CcSetLogic.phaseTag(slice.phase)
  CcSetHeading("Home Assistant", "Uses the server and long-lived token saved in Hearthpane Settings › Home Assistant.")
  CcSetGroup {
    CcSetRow("Connection", slice.reason ?: "Live updates over one connection · ${slice.entityCount} entities") { CcTag(phase, tone) }
    CcSetRow("Floors & areas", slice.registryLine) {
      if (slice.registryMissing) CcTag("Admin token", CcTone.AMBER)
    }
    CcSetRow("Floor plan stored", "${slice.planWhere} · ${slice.planWhereSub}", divider = false)
  }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    CcButton("Connection settings", { ui.context.openApprovedHomeSettings("ha") }, icon = "link")
    CcButton("Rebuild plan", { ui.go(CcScreen.EDIT) }, icon = "plan")
    CcButton("Classic dashboard", {
      runCatching { ui.context.startApprovedInternalActivity(Intent(ui.context, HaDashboardActivity::class.java)) }
    }, icon = "grid")
  }
}

private const val PORTAL_ICON_ALPHA = 0.7f
private const val QUIET_DEFAULT = "10 pm – 8 am"
