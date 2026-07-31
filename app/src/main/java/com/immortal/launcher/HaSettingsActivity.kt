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
            value = url,
            onValueChange = { url = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
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

    // Discovered entities to add.
    if (discovered.isNotEmpty()) {
      Card {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
          Text(
              "Discovered — tap to pin",
              color = IosSecondary,
              fontSize = Sizes.rowSub,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
          )
          discovered.forEach { e ->
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
        }
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}
