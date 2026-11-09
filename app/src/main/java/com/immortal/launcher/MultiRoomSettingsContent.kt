/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * Music Assistant connection and the Snapcast now-playing relay. Credentials and the raw player
 * identity are applied through the same registry as the phone remote. Back returns to settings.
 */
@Composable
internal fun MultiRoomScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "Multi-room audio",
      subtitle = "Connect the native music player and Snapcast now-playing to Music Assistant.",
      onBack = onBack,
  ) { MultiRoomContent() }
}

/** Inlinable body of the Multi-room audio screen — rendered by [MultiRoomScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun MultiRoomContent() {
  val context = LocalContext.current
  var baseline by remember(context) {
    mutableStateOf(synchronized(SettingsDomains.immortal) { ImmortalSettings.load(context) })
  }
  var enabled by remember(context) { mutableStateOf(baseline.multiRoomEnabled) }
  var host by remember(context) { mutableStateOf(baseline.snapcastHost) }
  var maPort by remember(context) { mutableStateOf(baseline.maPort.toString()) }
  // Saved tokens are never copied into the editable field; blank means keep the saved secret.
  var maToken by remember(context) { mutableStateOf("") }
  var maPlayer by remember(context) { mutableStateOf(baseline.maPlayerId) }
  var error by remember { mutableStateOf<String?>(null) }
  fun applyConnection(): Boolean {
    val port = maPort.toIntOrNull()
    val portSpec = SettingsDomains.immortal.specs.first { it.key == "maPort" }
        as com.immortal.launcher.settings.IntSpec<*>
    if (port == null || port !in portSpec.min..portSpec.max) {
      error = "Enter a port from ${portSpec.min} to ${portSpec.max}."
      return false
    }
    val values = MultiRoomConnectionValues(baseline.snapcastHost, baseline.maPort,
        baseline.maUsername, baseline.maPassword, baseline.maApiToken, baseline.maPlayerId)
    val changes = values.editedValues(host, port, baseline.maUsername, baseline.maPassword, maToken, maPlayer)
    // Use the registry's validators before writing any field in the native Apply batch.
    listOf("maApiToken", "maPlayerId").forEach { key ->
      val value = changes[key] as? String ?: return@forEach
      val spec = SettingsDomains.immortal.specs.first { it.key == key }
          as com.immortal.launcher.settings.StringSpec<*>
      if (!spec.applyWhen(value)) {
        error = spec.help
        return false
      }
    }
    val stored = synchronized(SettingsDomains.immortal) {
      if (changes.isNotEmpty()) SettingsDomains.immortal.apply(context, JSONObject(changes))
      ImmortalSettings.load(context)
    }
    baseline = stored
    enabled = stored.multiRoomEnabled
    host = stored.snapcastHost
    maPort = stored.maPort.toString()
    maToken = ""
    maPlayer = stored.maPlayerId
    error = null
    return true
  }

  // Chain the text fields so the keyboard's "Next" jumps to the following field instead of
  // closing. The current adapters use API tokens; legacy credentials remain stored only.
  val focusManager = LocalFocusManager.current
  val portFocus = remember { FocusRequester() }
  val tokenFocus = remember { FocusRequester() }
  val playerFocus = remember { FocusRequester() }

  Column {
      Card {
        Column(modifier = Modifier.padding(18.dp)) {
          Text("Setting it up", color = IosLabel, fontSize = Sizes.rowTitle)
          Text(
              "Connect Music Assistant for browsing and playback; use Snapcast for multi-room audio:",
              color = IosSecondary,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
          )
          SetupStep(
              "1",
              "Install and set up Music Assistant as a server on your home network. New to Music " +
                  "Assistant? Learn more at music-assistant.io")
          SetupStep(
              "2",
              "Install Snapcast from the Hearthpane App Store, and point it at your Music Assistant " +
                  "server.")
          SetupStep(
              "3", "Turn on the toggle below, then apply your server address, API token and raw " +
                  "Music Assistant player ID.")
        }
      }
      Spacer(Modifier.size(26.dp))
      Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Show what the group is playing", color = IosLabel, fontSize = Sizes.rowTitle)
            Text(
                "Enables the native Music Assistant connection and shows the Snapcast group's track " +
                    "on the now-playing card.",
                color = IosSecondary,
                fontSize = Sizes.rowSub,
                modifier = Modifier.padding(top = 2.dp),
            )
          }
          ApprovedSettingsSwitch(
              checked = enabled,
              onCheckedChange = { on ->
                SettingsDomains.immortal.apply(context, JSONObject().put("multiRoomEnabled", on))
                enabled = ImmortalSettings.load(context).multiRoomEnabled
              },
              modifier = Modifier.semantics { contentDescription = "Show what the group is playing" },
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
                label = { Text("Music Assistant / Snapcast server IP") },
                modifier = Modifier.weight(1f),
            )
            ActionPill("Apply", modifier = Modifier.padding(start = 12.dp)) {
                      applyConnection()
            }
          }
          // Music Assistant API port — 8095 by default; only change it if MA's web server moved.
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = maPort,
              onValueChange = {
                maPort = it.filter(Char::isDigit).take(5)
              },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { tokenFocus.requestFocus() }),
              label = { Text("Music Assistant port (default 8095)") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 4.dp)
                      .focusRequester(portFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = maToken,
              onValueChange = { maToken = it },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions =
                  KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
              keyboardActions = KeyboardActions(onNext = { playerFocus.requestFocus() }),
              label = { Text("Music Assistant API token") },
              placeholder = {
                Text(if (baseline.maApiToken.isNotBlank()) "Saved token — leave blank to keep" else "API token")
              },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(tokenFocus),
          )
          OutlinedTextField(
              colors = iosTextFieldColors(),
              value = maPlayer,
              onValueChange = { maPlayer = it },
              singleLine = true,
              keyboardOptions =
                  KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
              label = { Text("Music Assistant player ID") },
              placeholder = { Text("Raw player_id; blank leaves selection unset") },
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                      .focusRequester(playerFocus),
          )
          Text(
              "API tokens are required for this Music Assistant connection. Leave the token blank " +
                  "to keep it saved. Use the raw player_id, not a display name (up to 256 printable " +
                  "characters). YouTube and other music-provider credentials stay on your Music " +
                  "Assistant server.",
              color = IosSecondary,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp),
          )
          Row(
              modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            ActionPill("Test connection") { if (applyConnection()) MaControl.testLogin(context) }
            val auth = MultiRoomStatus.maAuth
            if (auth.isNotBlank()) {
              Text(
                  auth,
                  color = if (auth.endsWith("✓")) IosGreen else IosRed,
                  fontSize = Sizes.rowSub,
                  modifier = Modifier.padding(start = 14.dp),
              )
            }
          }
          // Live relay status — gives Apply visible feedback (Connecting… → Connected).
          error?.let { Text(it, color = IosRed, fontSize = Sizes.rowSub, modifier = Modifier.padding(18.dp)) }
          Text(
              MultiRoomStatus.text.ifBlank { "Starting…" },
              color = IosBlue,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
          )
        }
      }
      Text(
          "Join this Portal to the same Snapcast group as your other rooms, then point it " +
              "at your Music Assistant server. Audio is played by the Snapcast app; timing depends " +
              "on the server, network and speaker setup.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
}
