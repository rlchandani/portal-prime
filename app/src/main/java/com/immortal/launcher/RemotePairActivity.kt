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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * "Control from your phone" — the phone remote's pairing screen for the [RemoteRoutes] page
 * served by the fleet agent. The pairing secret is never on screen by default: the QR code and
 * PIN appear (and the remote is enabled, [RemotePairing.setEnabled]) only after the user taps
 * "Show pairing code". The same body is the Phone Remote page of the split-view Settings.
 *
 * The pairing card itself ([RemotePairCard]) is shared with the home-header quick-connect modal.
 */
class RemotePairActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { RemotePairScreen() } }
  }
}

@Composable
private fun RemotePairScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Phone Remote",
      subtitle = "Use a phone or tablet on the same Wi-Fi as a remote for this Portal — nav " +
          "buttons and an app launcher, no extra app to install.",
      onBack = { activity?.finish() },
  ) {
    PhonePairingContent()
    Spacer(Modifier.size(28.dp))
    PairDoneButton { activity?.finish() }
  }
}

/** Hidden until asked: one tap enables the remote, mints a PIN and shows the QR/PIN card. */
@Composable
internal fun PhonePairingContent() {
  val context = LocalContext.current
  var issued by remember { mutableStateOf<Pair<String?, String?>?>(null) }
  val current = issued
  if (current == null) {
    Card {
      NavRow("Show pairing code", subLabel = "Shows a QR code and a one-time PIN for your phone.") {
        issued = enableRemoteAndMintPin(context)
      }
    }
  } else {
    RemotePairCard(current.first, current.second)
    Spacer(Modifier.size(18.dp))
    Card { NavRow("Hide pairing code") { issued = null } }
  }
}
