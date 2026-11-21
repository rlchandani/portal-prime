/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop

/**
 * Home Assistant control widget ([HaConfig] + [HaClient]). Renders a tile per pinned entity showing
 * its on/off state; tapping toggles it via HA's REST service call with optimistic UI (flip locally,
 * then reconcile with the fetched truth). Polls state every 30s. Requires a base URL + long-lived
 * token — shows a "connect" prompt until configured. This is the read/control path, distinct from
 * the publish-only MQTT integration.
 */
@Composable
internal fun ImmortalHaWidget(modifier: Modifier = Modifier, maxTileRows: Int = 3) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var dashboardConfigured by remember { mutableStateOf(HomeDashboardConfig.configured(context)) }
  var target by remember { mutableStateOf(haWidgetTarget(context)) }
  OnResume {
    dashboardConfigured = HomeDashboardConfig.configured(context)
    target = haWidgetTarget(context)
  }
  // A connection adopted while Home is on screen (Settings sync of the connection, fleet ha_adopt).
  val haRevision by HaConfig.revision.collectAsState()
  LaunchedEffect(haRevision) { target = haWidgetTarget(context) }
  val controlCenter = target == HomeRefinements.HaWidgetTarget.CONTROL_CENTER
  val openControlCenter: () -> Unit = { ControlCenterActivity.open(context) }
  if (dashboardConfigured) {
    val openHome: () -> Unit = if (controlCenter) openControlCenter else { { HomeDashboardActivity.openDashboard(context) } }
    ImmortalWidgetShell(title = "Home Assistant", openPage = "ha", modifier = modifier,
        onOpen = openHome, duplicatesBodyAction = true) {
      Text("Your home dashboard", color = ApprovedHomePalette.Text, fontSize = 26.sp,
          lineHeight = 34.sp, fontWeight = FontWeight.Medium)
      Text("Open your rooms, devices and scenes.", color = ApprovedHomePalette.Secondary,
          fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.padding(top = 14.dp))
      Spacer(Modifier.height(22.dp))
      ApprovedHomePillButton(if (controlCenter) "Open Home Control" else "Open dashboard", onClick = openHome)
    }
    return
  }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var entities by remember { mutableStateOf(HaConfig.entities(context)) }
  var configured by remember { mutableStateOf(HaConfig.isConfigured(context)) }
  OnResume {
        entities = HaConfig.entities(context)
        configured = HaConfig.isConfigured(context)
  }
  LaunchedEffect(haRevision) { configured = HaConfig.isConfigured(context) }
  // A local override map lets a tap flip the tile instantly (optimistic) before the poll confirms.
  var optimistic by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
  val ids = remember(entities) { entities.map { it.entityId } }
  val states by
      produceHomeState<List<HaClient.EntityState>?>(initialValue = null, ids, configured) {
        while (true) {
          value = withContext(Dispatchers.IO) {
            val worker = kotlinx.coroutines.currentCoroutineContext()
            HaClient.fetchStates(context, ids, checkCancelled = { worker.ensureActive() })
          }
          optimistic = emptyMap() // truth arrived; drop optimistic overrides
          homeRefreshDelay(30_000L)
        }
      }
  val open: () -> Unit = { context.openApprovedHomeSettings("ha") }
  val openDashboard: () -> Unit = { context.openApprovedHomeSettings("ha-dashboard") }
  ImmortalWidgetShell(title = "Home Assistant", openPage = "ha", onOpen = if (controlCenter) openControlCenter else null,
      modifier = modifier.tvFocusableRow { if (controlCenter) openControlCenter() else open() }) {
    when {
      !configured -> {
        Text("Connect your home", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp)
        ApprovedHomeTextButton("Set up Home Assistant", onClick = open)
      }
      entities.isEmpty() -> ApprovedHomeWidgetMessage("Add entities to control")
      else -> {
        val byId = remember(states) { (states ?: emptyList()).associateBy { it.entityId } }
        val visiblePairs = remember(entities, maxTileRows) { entities.chunked(2).take(maxTileRows) }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
          // Two tiles per row; row count scales with the widget's height.
          visiblePairs.forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              pair.forEach { ent ->
                val fetched = byId[ent.entityId]
                val domain = ent.entityId.substringBefore('.', "")
                // Scenes are momentary (no on/off) — a tap just runs them.
                val momentary = domain == "scene"
                // Read-only entities (sensors, weather, presence) show a value, no control.
                val readonly = fetched?.readable == true
                val on = optimistic[ent.entityId] ?: fetched?.on ?: false
                val known = optimistic.containsKey(ent.entityId) || fetched != null
                val displayName = ent.name.ifBlank { fetched?.friendlyName ?: ent.entityId }
                if (readonly) {
                  HaSensorTile(
                      name = displayName,
                      value = fetched?.displayValue?.ifBlank { "—" } ?: "—",
                      modifier = Modifier.weight(1f).tvFocusableRow { openDashboard() },
                  )
                } else {
                  HaTile(
                      name = displayName,
                      on = on,
                      known = known || momentary,
                      momentary = momentary,
                      modifier = Modifier.weight(1f),
                  ) {
                    if (momentary) {
                      scope.launch { withContext(Dispatchers.IO) { HaClient.activate(context, ent.entityId) } }
                    } else {
                      optimistic = optimistic + (ent.entityId to !on)
                      scope.launch { withContext(Dispatchers.IO) { HaClient.toggle(context, ent.entityId) } }
                    }
                  }
                }
              }
              if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

/** [HomeRefinements.haWidgetTarget] from the saved Home Assistant, Portal-mode and dashboard settings. */
private fun haWidgetTarget(context: Context): HomeRefinements.HaWidgetTarget =
    HomeRefinements.haWidgetTarget(HaConfig.isConfigured(context), ControlCenterConfig.load(context).bannersOnly,
        HomeDashboardConfig.configured(context))

/**
 * One HA control tile: name + status line. Toggle entities show On/Off (dimmed while unknown);
 * momentary entities (scenes) show a "Tap to run" affordance and a purple accent, since they have
 * no persistent state.
 */
@Composable
private fun HaTile(
    name: String,
    on: Boolean,
    known: Boolean,
    momentary: Boolean = false,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  val bg = when {
    momentary -> ApprovedHomePalette.Selected
    on -> if (palette.dark) palette.selected
        else Color(0xFFD7EEE2)
    else -> ApprovedHomePalette.Chrome
  }
  val fg = ApprovedHomePalette.Text
  val fgSub = ApprovedHomePalette.Secondary
  // Mock anatomy: glyph on its own line up top, then name over the state line — the tile reads
  // as an object (a lamp, a scene) rather than a labelled button.
  Column(
      modifier = modifier.heightIn(min = 150.dp).clip(RoundedCornerShape(17.dp)).background(bg)
          .tvFocusableRow { onToggle() }
          .padding(20.dp),
      verticalArrangement = Arrangement.SpaceBetween,
  ) {
    ApprovedHomeIcon(if (momentary) "tools" else "bulb", Modifier.size(32.dp),
        if (palette.dark && known && on && !momentary) palette.green else fg)
    Column {
      Text(name, color = fg, fontSize = 23.sp, lineHeight = 30.sp,
          fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
          when {
            momentary -> "Scene"
            !known -> "—"
            on -> "On"
            else -> "Off"
          },
          color = fgSub,
          fontSize = 20.sp,
          lineHeight = 27.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }
}

/** A read-only HA value tile: big value + name underneath (sensor / weather / presence). No control. */
@Composable
private fun HaSensorTile(name: String, value: String, modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.heightIn(min = 150.dp).clip(RoundedCornerShape(17.dp))
          .background(ApprovedHomePalette.Chrome)
          .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(value, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp,
        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(name, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}
