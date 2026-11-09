/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.immortal.launcher.settings.SettingsDomains
import org.json.JSONObject

/**
 * The Home Assistant (MQTT) subpage: publish this Portal to Home Assistant as auto-
 * discovered entities (presence, screen, battery, now-playing, controls) over a local
 * MQTT broker. Off by default; Back returns to the main settings page.
 */
@Composable
internal fun MqttScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "MQTT publishing",
      subtitle =
          "Publish this Portal to Home Assistant as auto-discovered entities — presence, " +
              "screen, battery, now-playing, and controls — over your MQTT broker.",
      onBack = onBack,
  ) { MqttContent() }
}

/** Inlinable body of the Home Assistant (MQTT) screen — rendered by [MqttScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun MqttContent() {
  val context = LocalContext.current
  var enabled by remember { mutableStateOf(MqttConfig.isEnabled(context)) }
  fun readConnection() = MqttConnectionValues(MqttConfig.host(context), MqttConfig.port(context),
      MqttConfig.username(context), MqttConfig.password(context), MqttConfig.useTls(context))
  var baseline by remember { mutableStateOf(synchronized(SettingsDomains.mqtt) { readConnection() }) }
  var host by remember { mutableStateOf(baseline.host) }
  var port by remember { mutableStateOf(baseline.port.toString()) }
  var user by remember { mutableStateOf(baseline.username) }
  var pass by remember { mutableStateOf(baseline.password) }
  var useTls by remember { mutableStateOf(baseline.useTls) }
  var error by remember { mutableStateOf<String?>(null) }
  // MqttStatus is a plain holder updated off the main thread, so poll it for live
  // "Connecting… → Connected" feedback (Compose won't recompose on its writes).
  var status by remember { mutableStateOf(MqttStatus.text) }
  LaunchedEffect(enabled) {
    while (enabled) {
      status = MqttStatus.text
      kotlinx.coroutines.delay(800)
    }
  }

  val focusManager = LocalFocusManager.current
  val portFocus = remember { FocusRequester() }
  val userFocus = remember { FocusRequester() }
  val passFocus = remember { FocusRequester() }

  fun apply(): Boolean {
    val chosenPort = port.toIntOrNull()
    val portSpec = SettingsDomains.mqtt.specs.first { it.key == "port" }
        as com.immortal.launcher.settings.IntSpec<*>
    if (chosenPort == null || chosenPort !in portSpec.min..portSpec.max) {
      error = "Enter a port from ${portSpec.min} to ${portSpec.max}."
      return false
    }
    val stored = synchronized(SettingsDomains.mqtt) {
      // Submit only deliberate edits; untouched fields may have newer companion values.
      val changes = baseline.editedValues(host, chosenPort, user, pass, useTls)
      if (changes.isNotEmpty()) {
        val body = JSONObject()
        changes.forEach { (key, value) -> body.put(key, value) }
        SettingsDomains.mqtt.apply(context, body)
      }
      readConnection()
    }
    // No-op Apply refreshes the form without writing or reconnecting the broker.
    baseline = stored
    host = stored.host
    port = stored.port.toString()
    user = stored.username
    pass = stored.password
    useTls = stored.useTls
    enabled = MqttConfig.isEnabled(context)
    error = null
    return true
  }

  Column {
      Card {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Setting it up", color = IosLabel, fontSize = Sizes.rowTitle)
          SetupStep(
              "1",
              "In Home Assistant, add the Mosquitto broker add-on (Settings → Add-ons) and the " +
                  "MQTT integration. New to MQTT? See home-assistant.io/integrations/mqtt")
          SetupStep(
              "2", "Turn on the toggle below and enter your broker's address (and login, if any).")
          SetupStep(
              "3",
              "This Portal appears automatically under Settings → Devices as a new MQTT device — " +
                  "no YAML needed.")
        }
      }
      Spacer(Modifier.size(26.dp))
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Publish to Home Assistant", color = IosLabel, fontSize = Sizes.rowTitle)
            Text(
                "Exposes this Portal's state and controls as Home Assistant entities over MQTT.",
                color = IosSecondary,
                fontSize = Sizes.rowSub,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          ApprovedSettingsSwitch(
              checked = enabled,
              onCheckedChange = { on ->
                SettingsDomains.mqtt.apply(context, JSONObject().put("enabled", on))
                enabled = MqttConfig.isEnabled(context)
              },
              modifier = Modifier.semantics { contentDescription = "Publish to Home Assistant" },
          )
        }
        if (enabled) {
          Divider()
          Row(
              modifier = Modifier.fillMaxWidth().padding(18.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
                colors = iosTextFieldColors(),
                value = host,
                onValueChange = {
                  host = it
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }),
                label = { Text("MQTT broker IP / host") },
                modifier = Modifier.weight(1f),
            )
            ActionPill("Apply", modifier = Modifier.padding(start = 12.dp)) { apply() }
          }
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = port,
              onValueChange = {
                port = it.filter { ch -> ch.isDigit() }
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { userFocus.requestFocus() }),
              label = { Text("Port (default 1883, or 8883 for TLS)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                      .focusRequester(portFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = user,
              onValueChange = {
                user = it
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(
                      capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
              label = { Text("Username (optional)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(userFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = pass,
              onValueChange = {
                pass = it
              },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); apply() }),
              label = { Text("Password (optional)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(passFocus),
          )
          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Use TLS / SSL", color = IosLabel, fontSize = Sizes.rowTitle)
              Text(
                  "Encrypt the connection. Default ports switch automatically; custom ports are kept.",
                  color = IosSecondary,
                  fontSize = Sizes.rowSub,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            ApprovedSettingsSwitch(
                checked = useTls,
                onCheckedChange = { on ->
                  useTls = on
                  // The registry selects defaults using the current saved port.
                  apply()
                },
                modifier = Modifier.semantics { contentDescription = "Use TLS / SSL" },
            )
          }
          // Certificate validation is auto-managed (on by default, settings-reimagining Phase 5):
          // no on-device row. It stays applied and the phone remote can still turn it off for a
          // self-signed broker on a trusted network.
          // Live connection status — gives Apply visible feedback (Connecting… → Connected).
          error?.let { Text(it, color = IosRed, fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp)) }
          Text(
              status.ifBlank { "Starting…" },
              color = IosBlue,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
          )
        }
      }
      Text(
          "Connects to a broker on your LAN over plain MQTT or TLS. Your Portal shows up in " +
              "Home Assistant automatically as a device with presence, screen, battery, " +
              "now-playing and a few controls — no configuration.yaml editing.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
}
