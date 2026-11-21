/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Home Assistant, while this Portal isn't connected: "Use <Family Area>'s connection"
 * for each household Portal that is. A confirm step, then the sealed hand-off ([HaShare]) and a
 * check against Home Assistant's `/api/`; the connection is saved only if that succeeds.
 */
@Composable
internal fun HaPeerAdoptSection() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var offers by remember { mutableStateOf<List<HaAdoption.Offer>>(emptyList()) }
  var confirm by remember { mutableStateOf<HaAdoption.Offer?>(null) }
  var busy by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var adopted by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    if (HaConfig.isConfigured(context) || HouseholdKey.get(context) == null) return@LaunchedEffect
    offers = runCatching { withContext(Dispatchers.IO) { HaShare.adoption(context).offers() } }
        .getOrElse { if (it is CancellationException) throw it else emptyList() }
  }
  if (offers.isEmpty() && !adopted) return

  fun adopt(offer: HaAdoption.Offer) {
    confirm = null
    busy = true; error = null; status = "Connecting to Home Assistant…"
    scope.launch {
      val outcome = try {
        withContext(Dispatchers.IO) { HaShare.adoption(context).adopt(offer.peer.id, replace = false) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        HaAdoption.Outcome(HaAdoption.Result.UNREACHABLE, offer.name)
      }
      busy = false
      if (outcome.result.ok) {
        adopted = true
        status = "Connected ✓ — using ${offer.name}'s connection"
      } else {
        status = ""
        error = haAdoptMessage(outcome.result, offer.name)
      }
    }
  }

  Column {
    SectionLabel("From another Portal")
    Card {
      offers.forEachIndexed { i, offer ->
        if (i > 0) Divider()
        NavRow("Use ${offer.name}'s connection", enabled = !busy && !adopted) { confirm = offer }
      }
    }
    if (status.isNotEmpty()) FooterNote(status)
    error?.let { FooterNote(it) }
    if (!adopted) FooterNote("Copies the Home Assistant address and token from that Portal, sealed over your " +
        "home network. It's saved only after Home Assistant accepts it from this Portal.")
    Spacer(Modifier.height(12.dp))
  }
  confirm?.let { offer ->
    ApprovedSettingsDialog("Use ${offer.name}'s connection?", onDismiss = { confirm = null }) {
      Text("This Portal will connect to the same Home Assistant as ${offer.name}, with the same token. " +
          "Home Control, alerts and the doorbell start working here.",
          color = IosSecondary, fontSize = 24.sp, lineHeight = 32.sp)
      Spacer(Modifier.height(28.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ApprovedEditorsButton("Cancel", onClick = { confirm = null })
        ApprovedEditorsButton("Use connection", primary = true, onClick = { adopt(offer) })
      }
    }
  }
}

/** A person-facing sentence for a failed adoption (never mentions the token). */
internal fun haAdoptMessage(result: HaAdoption.Result, peer: String): String = when (result) {
  HaAdoption.Result.PEER_NOT_CONNECTED -> "$peer isn't connected to Home Assistant any more."
  HaAdoption.Result.VALIDATION_FAILED -> "Home Assistant didn't accept the connection from this Portal. Nothing was saved."
  HaAdoption.Result.RATE_LIMITED -> "$peer has shared its connection several times recently. Try again in a few minutes."
  HaAdoption.Result.UNREACHABLE -> "Couldn't reach $peer. Check that it's on and on the same network."
  HaAdoption.Result.ALREADY_CONNECTED -> "This Portal is already connected to Home Assistant."
  HaAdoption.Result.NO_PEER, HaAdoption.Result.AMBIGUOUS -> "Couldn't find $peer on your home network."
  HaAdoption.Result.REFUSED, HaAdoption.Result.INVALID_REPLY -> "$peer didn't share its connection. Nothing was saved."
  HaAdoption.Result.ADOPTED, HaAdoption.Result.UNCHANGED -> "Connected ✓"
}
