/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Device health" subpage: a live status of every special permission provisioning
 * grants (screen-off device admin, notification access, install, overlay, secure settings),
 * with — for anything that's missing — what's degraded and how to fix it. A diagnostic to
 * point a struggling user at. Replaces the old destructive "turn off device admin" button;
 * the uninstall path it served is kept as a clearly-warned advanced action at the bottom.
 */
@Composable
internal fun DeviceHealthScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "Device health",
      subtitle = "Check the permissions and control access your Portal needs.",
      onBack = onBack,
  ) { DeviceHealthContent() }
}

/** Inlinable body of the Device health screen — rendered by [DeviceHealthScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. Software update leads the page. */
@Composable
internal fun DeviceHealthContent() {
  Column {
    OtaSettingsCard()
    DeviceHealthChecks()
  }
}

@Composable
private fun DeviceHealthChecks() {
  val context = LocalContext.current.applicationContext
  val advanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
  val owner = LocalLifecycleOwner.current
  var refresh by remember { mutableStateOf(0) }
  var confirmingDisable by remember(advanced) { mutableStateOf(false) }
  var disablingAdmin by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  var recheckRequested by remember { mutableStateOf(false) }
  var reconnectingControls by remember { mutableStateOf(false) }
  val health by produceState(DeviceHealthStatus(), context, owner, refresh, advanced) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      confirmingDisable = false
      value = value.beginCheck()
      value = value.received(loadDeviceHealth { DevicePermissions.snapshot(context) })
      recheckRequested = false
      // Query on request/resume, cancel on departure, and retain completed rows while checking.
      awaitCancellation()
    }
  }
  val checking = health.checking || recheckRequested || reconnectingControls
  fun recheck() {
    // Set synchronously so burst input cannot enqueue parallel permission queries.
    if (health.checking || recheckRequested || reconnectingControls) return
    recheckRequested = true
    confirmingDisable = false
    refresh++
  }
  fun reconnectControls() {
    // Admit once from a fresh completed result; never repair while checking stale rows.
    if (reconnectingControls || recheckRequested || !health.canReconnectControls) return
    reconnectingControls = true
    confirmingDisable = false
    scope.launch {
      try {
        // Revalidate at the action boundary: permission or connection may have changed.
        val requested = withContext(Dispatchers.IO) {
          runCatching {
            if (SettingsGuard.canWriteSecureSettings(context) && !RemoteInput.available()) {
              SettingsGuard.ensureBarWatchConnected(context)
              true
            } else false
          }.getOrDefault(false)
        }
        // The existing repair re-adds the service after 2 seconds. One delayed check,
        // cancelled when this page leaves composition, avoids a polling loop.
        if (requested) kotlinx.coroutines.delay(2400)
        recheckRequested = true
        refresh++
      } finally {
        reconnectingControls = false
      }
    }
  }
  val snapshot = health.snapshot
  if (snapshot == null) {
    Card {
      Column(Modifier.padding(28.dp).semantics {
        contentDescription = if (checking) "Checking device health" else "Device health check unavailable"
      }) {
        Text(if (checking) "Checking device health…" else "Could not check device health.",
            color = IosSecondary, fontSize = Sizes.rowTitle)
        if (!checking) ApprovedSettingsTextButton("Try again") { recheck() }
      }
    }
    return
  }
  val checks = snapshot.checks
  val issues = checks.count { !it.granted }
  val adminActive = snapshot.adminActive

  Column {
      // Summary banner — green when healthy, amber when something needs attention.
      // Light semantic tints (dark green/amber would clash with the light background).
      Surface(
          color = when {
            checking -> IosCard
            health.failed || issues > 0 -> Color(0xFFFDF1DD)
            else -> Color(0xFFE6F7EA)
          },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = when {
              checking -> "Checking device health"
              health.failed -> "Device health check unavailable"
              else -> "Device health checked"
            }
          },
      ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
              when {
                reconnectingControls -> "Reconnecting controls…"
                checking -> "Checking device health…"
                health.failed -> "Could not recheck device health. Last results are shown."
                issues == 0 -> "✓  Device checks ready."
                else -> "!  $issues device check${if (issues == 1) " needs" else "s need"} attention."
              },
              color = when {
                checking -> IosSecondary
                health.failed || issues > 0 -> Color(0xFFB26A00)
                else -> Color(0xFF1E7B34)
              },
              fontSize = Sizes.rowTitle,
              modifier = Modifier.weight(1f),
          )
          ActionPill(if (checking) "Checking…" else if (health.failed) "Try again" else "Check again",
              enabled = !checking, modifier = Modifier.semantics { if (checking) disabled() }) { recheck() }
        }
      }
      Spacer(Modifier.size(20.dp))

      Card {
        checks.forEachIndexed { i, c ->
          if (i > 0) Divider()
          HealthRow(c, advanced, reconnectingControls,
              repairEnabled = !checking && !health.failed, onReconnect = ::reconnectControls)
        }
      }

      if (issues > 0) {
        Spacer(Modifier.size(22.dp))
        SectionLabel("How to fix")
        Text(
            if (checks.filter { !it.granted }.all { it.repair == DevicePermissions.Repair.RECONNECT_CONTROLS })
              "Use Reconnect controls above. This page will check the connection after reconnecting."
            else
              "Run Hearthpane setup from a connected computer to restore missing system permissions, " +
                  "then choose Check again. Any available Reconnect controls action can be used here.",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            lineHeight = 20.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
      }

      // Advanced: deactivating the screen-off admin is the clean path to uninstall Immortal
      // (the shell can't force-remove a non-test admin). Tucked away and clearly warned — it
      // turns off screen-off until re-provisioned.
      if (advanced && adminActive) {
        Spacer(Modifier.size(28.dp))
        Text(
            "Prepare to uninstall Hearthpane",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Text(
            "Remove Hearthpane’s screen-lock permission before uninstalling. This stops automatic " +
                "and remote screen-off until Hearthpane setup restores the permission.",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Text(
            if (disablingAdmin) "Removing screen-lock permission…"
            else if (confirmingDisable) "Tap again to confirm — automatic screen-off will stop"
            else "Remove screen-lock permission",
            color = if (checking || health.failed) IosSecondary else IosRed,
            fontSize = Sizes.actionButton,
            fontWeight = if (confirmingDisable) FontWeight.SemiBold else FontWeight.Normal,
            modifier =
                Modifier.tvFocusable(RoundedCornerShape(8.dp), enabled = !disablingAdmin && !checking && !health.failed) {
                      // Reject burst input immediately, before the disabled row can recompose.
                      if (!advanced || disablingAdmin || recheckRequested || reconnectingControls || !health.canRemoveAdmin) return@tvFocusable
                      // Two-tap arm: a single stray remote press shouldn't tear down device admin
                      // (which silently breaks screensaver sleep + the HA screen-off control).
                      if (confirmingDisable) {
                        confirmingDisable = false
                        disablingAdmin = true
                        scope.launch {
                          try {
                            withContext(Dispatchers.IO) { ScreenControl.deactivateAdmin(context) }
                            refresh++
                          } finally {
                            disablingAdmin = false
                          }
                        }
                      } else {
                        confirmingDisable = true
                      }
                    }
                    .padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        )
      }
    }
}

@Composable
private fun HealthRow(c: DevicePermissions.Check, advanced: Boolean,
    reconnectingControls: Boolean, repairEnabled: Boolean, onReconnect: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Text(
        if (c.granted) "✓" else "!",
        color = if (c.granted) IosGreen else Color(0xFFB26A00),
        fontSize = Sizes.rowTitle,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 14.dp, top = 1.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(c.title, color = IosLabel, fontSize = Sizes.rowTitle)
      Text(
          c.enables,
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          lineHeight = 28.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
      if (!c.granted) {
        Text(
            "Without it: ${c.degraded}",
            color = Color(0xFFB26A00),
            fontSize = Sizes.rowSub,
            lineHeight = 28.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            c.fix,
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            lineHeight = 28.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (c.repair == DevicePermissions.Repair.RECONNECT_CONTROLS) {
          Spacer(Modifier.height(10.dp))
          ActionPill(if (reconnectingControls) "Reconnecting…" else "Reconnect controls",
              enabled = repairEnabled, onClick = onReconnect)
        }
        if (advanced && c.technicalFix.isNotBlank()) {
          Text("Setup command: adb shell ${c.technicalFix}", color = IosSecondary,
              fontSize = Sizes.rowSub, lineHeight = 28.sp,
              fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
              modifier = Modifier.padding(top = 6.dp))
        }
      }
    }
  }
}
