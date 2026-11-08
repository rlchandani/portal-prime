/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.GuestSettings
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Settings → Guest card. The non-secret lines render from [GuestSettings.domain]; the Wi-Fi
 * password is a separate, masked field written only to [GuestPasswordStore].
 */
class GuestSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        SettingsScaffold(title = "Guest card", subtitle = "Wi-Fi and house basics for visitors.",
            onBack = rememberApprovedSettingsBack()) { GuestSettingsContent() }
      }
    }
  }
}

@Composable
internal fun GuestSettingsContent() {
  val context = LocalContext.current
  var snapshot by remember { mutableStateOf(GuestSettings.domain.load(context)) }
  GuestPasswordSection()
  SettingsRows(GuestSettings.domain, snapshot) { key, value ->
    GuestSettings.domain.apply(context, JSONObject().put(key, value))
    snapshot = GuestSettings.domain.load(context)
  }
  SectionLabel("Guest mode")
  Card {
    val mode by GuestMode.state.collectAsState()
    val on = mode.isOn(System.currentTimeMillis())
    ActionSettingRow("Show the guest card",
        if (on) "Guest mode is on. ${guestUntilLabel(mode)}." else "Guest mode hides photos, calendar, notes and your music library.",
        actionLabel = "Open") { GuestEntry.openCard(context) }
    if (on) ActionSettingRow("End Guest mode", actionLabel = "End") { GuestFeature.end(context) }
  }
}

@Composable
private fun GuestPasswordSection() {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  var saved by remember { mutableStateOf<Boolean?>(null) }
  var draft by remember { mutableStateOf("") }
  var message by remember { mutableStateOf<String?>(null) }
  val store = remember(context) { GuestPasswordStore.get(context) }
  LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { store.isSet() } }
  fun write(value: String, done: String) = scope.launch {
    val ok = withContext(Dispatchers.IO) { store.write(value) }
    message = if (ok) done else "Couldn't save the password. Try again."
    if (ok) { draft = ""; saved = value.isNotEmpty() }
  }
  SectionLabel("Wi-Fi password")
  Card {
    ApprovedEditorsField("Wi-Fi password", draft, { draft = it.take(GuestPasswordStore.MAX_LENGTH) },
        placeholder = if (saved == true) "Saved. Type to replace it" else "Type the password", secret = true)
    Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ActionPill("Save password", enabled = GuestPasswordStore.isValid(draft)) { write(draft, "Password saved.") }
      if (saved == true) ActionPill("Remove password") { write("", "Password removed.") }
    }
    Column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 20.dp)) {
      Text(message ?: "Stored encrypted on this Portal only. It isn't included in backups or settings exports.",
          color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp)
    }
  }
}
