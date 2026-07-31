/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editor for the Home Assistant control widget: connection (base URL + long-lived token), a "Test &
 * discover" action that pings the server and lists controllable entities to pin, and the pinned-entity
 * list. Light iOS layout matching the other widget editors.
 */
class HaSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { HaSettingsScreen() } }
  }
}

@Composable
private fun HaSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Home Assistant",
      subtitle = "Control lights, switches, and scenes. Needs your HA URL + a long-lived access token.",
      onBack = { activity?.finish() },
  ) { HaSettingsContent() }
}

@Composable
internal fun HaSettingsContent() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var url by remember { mutableStateOf(HaConfig.baseUrl(context)) }
  var token by remember { mutableStateOf(HaConfig.token(context)) }
  var status by remember { mutableStateOf("") }
  var discovered by remember { mutableStateOf<List<HaClient.EntityState>>(emptyList()) }
  var pinned by remember { mutableStateOf(HaConfig.entities(context)) }
  var query by remember { mutableStateOf("") }
  // Domains the user has expanded (collapsed by default while browsing). Searching overrides this
  // and expands every group so matches are always visible.
  var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }

  fun refreshPinned() {
    pinned = HaConfig.entities(context)
  }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Connection.
    Card {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connection", color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
        Text(
            "In HA: profile → Long-lived access tokens → Create. Paste the URL (e.g. http://homeassistant.local:8123) and token.",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
        )
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = url,
            onValueChange = { url = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            colors = iosTextFieldColors(),
            value = token,
            onValueChange = { token = it },
            label = { Text("Long-lived token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Save & discover") {
          HaConfig.setConnection(context, url, token)
          status = "Connecting…"
          scope.launch {
            val ok = withContext(Dispatchers.IO) { HaClient.ping(context) }
            if (!ok) {
              status = "Couldn't reach Home Assistant — check URL and token"
              discovered = emptyList()
            } else {
              val list = withContext(Dispatchers.IO) { HaClient.discover(context) }
              discovered = list
              status = "Connected ✓ — ${list.size} controllable entities found"
            }
          }
        }
        if (status.isNotBlank()) {
          Text(status, color = if (status.contains("✓")) Color(0xFF34C759) else IosSecondary, fontSize = Sizes.rowSub)
        }
      }
    }

    // Pinned entities.
    if (pinned.isNotEmpty()) {
      Card {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
          Text(
              "Pinned to widget",
              color = IosSecondary,
              fontSize = Sizes.rowSub,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
          )
          pinned.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(e.name, color = IosLabel, fontSize = Sizes.rowTitle, fontWeight = FontWeight.SemiBold)
                Text(e.entityId, color = IosSecondary, fontSize = Sizes.rowSub)
              }
              Box(
                  modifier = Modifier.size(40.dp).clickable {
                    HaConfig.removeEntity(context, e.entityId)
                    refreshPinned()
                  },
                  contentAlignment = Alignment.Center,
              ) {
                Text("✕", color = Color(0xFFFF3B30), fontSize = 20.sp)
              }
            }
          }
        }
      }
    }

    // Discovered entities to add — searchable + grouped by domain, since a real HA server has
    // hundreds of entities and a flat list is unusable.
    if (discovered.isNotEmpty()) {
      // Search box (its own card so it stays pinned above the results visually).
      Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = query,
              onValueChange = { query = it },
              label = { Text("Search ${discovered.size} entities") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      val q = query.trim().lowercase()
      val matches =
          if (q.isBlank()) discovered
          else discovered.filter {
            it.friendlyName.lowercase().contains(q) || it.entityId.lowercase().contains(q)
          }
      // Group by domain (light/switch/scene/…), ordered so the most-controlled domains come first.
      val grouped = matches.groupBy { it.entityId.substringBefore('.', "") }
      val orderedGroups = grouped.entries.sortedWith(
          compareBy({ HA_GROUP_ORDER.indexOf(it.key).let { i -> if (i < 0) HA_GROUP_ORDER.size else i } }, { it.key }))

      if (matches.isEmpty()) {
        Card {
          Text(
              "No entities match \"$query\".",
              color = IosSecondary,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(18.dp),
          )
        }
      } else {
        // Searching expands every group (matches must be visible); otherwise a group shows only
        // when the user has tapped its header open. Collapsed groups render just their header row,
        // so 15 domains stay scannable. Expanded groups still cap rows (search to narrow further).
        val searching = q.isNotBlank()
        orderedGroups.forEach { (domain, entities) ->
          val expanded = searching || domain in expandedGroups
          val perGroupCap = if (searching) Int.MAX_VALUE else 12
          val shown = if (expanded) entities.take(perGroupCap) else emptyList()
          val hidden = if (expanded) entities.size - shown.size else 0
          Card {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
              // Tappable header toggles the group (disabled while searching — all are forced open).
              Row(
                  modifier = Modifier.fillMaxWidth()
                      .clickable(enabled = !searching) {
                        expandedGroups = if (domain in expandedGroups) expandedGroups - domain else expandedGroups + domain
                      }
                      .padding(horizontal = 18.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                    if (expanded) "▾" else "▸",
                    color = IosSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    "${haGroupLabel(domain)} · ${entities.size}",
                    color = IosLabel,
                    fontSize = Sizes.rowTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
              }
              shown.forEach { e ->
                Divider()
                val already = pinned.any { it.entityId == e.entityId }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !already) {
                          HaConfig.addEntity(context, e.entityId, e.friendlyName)
                          refreshPinned()
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(e.friendlyName, color = IosLabel, fontSize = Sizes.rowTitle)
                    Text(e.entityId, color = IosSecondary, fontSize = Sizes.rowSub)
                  }
                  Text(
                      if (already) "Pinned ✓" else "+ Pin",
                      color = if (already) IosSecondary else IosBlue,
                      fontSize = 15.sp,
                      fontWeight = FontWeight.SemiBold,
                  )
                }
              }
              if (hidden > 0) {
                Text(
                    "+$hidden more — search to narrow",
                    color = IosSecondary,
                    fontSize = Sizes.rowSub,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
              }
            }
          }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}

/** Domain display order for the discovered-entities grouping — controllable things first. */
private val HA_GROUP_ORDER =
    listOf("light", "switch", "fan", "scene", "script", "automation", "input_boolean",
        "climate", "cover", "media_player", "binary_sensor", "sensor", "person", "device_tracker", "weather", "sun")

/** Friendly, pluralized section heading for an HA domain. */
private fun haGroupLabel(domain: String): String =
    when (domain) {
      "light" -> "Lights"
      "switch" -> "Switches"
      "fan" -> "Fans"
      "scene" -> "Scenes"
      "script" -> "Scripts"
      "automation" -> "Automations"
      "input_boolean" -> "Toggles"
      "climate" -> "Climate"
      "cover" -> "Covers"
      "media_player" -> "Media players"
      "binary_sensor" -> "Binary sensors"
      "sensor" -> "Sensors"
      "person" -> "People"
      "device_tracker" -> "Device trackers"
      "weather" -> "Weather"
      "sun" -> "Sun"
      else -> domain.replaceFirstChar { it.uppercase() }
    }
