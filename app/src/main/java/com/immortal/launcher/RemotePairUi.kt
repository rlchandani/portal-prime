/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Shared "control from your phone" pairing UI + enable step, used by both the full
 * [RemotePairActivity] (Settings entry) and the home-header quick-connect modal. Keeping the
 * QR/PIN card in one place stops the two surfaces from drifting.
 */

/**
 * Turn the phone remote on and mint a fresh pairing PIN, returning `(url, pin)` for the QR/PIN
 * card. Enables the feature ([RemotePairing]), the accessibility service it needs
 * ([SettingsGuard.reconcileBarWatch]) and the agent that serves it; `url` is null off Wi-Fi.
 */
fun enableRemoteAndMintPin(context: Context): Pair<String?, String?> {
  RemotePairing.setEnabled(context, true)
  SettingsGuard.reconcileBarWatch(context)
  // The nav buttons + touchpad route through the accessibility service; if an app update left it
  // enabled-but-unbound, force a rebind now so the remote isn't half-dead on connect.
  SettingsGuard.ensureBarWatchConnected(context)
  FleetAgentService.ensureRunning(context)
  val pin = RemotePairing.newPin()
  val ip = lanIp()
  val url = if (ip != null) "http://$ip:${FleetConfig.port(context)}/remote/ui" else null
  return url to pin
}

/**
 * The scan-to-pair card: a QR (URL + `#pin=` so scanning auto-pairs) plus the manual code and
 * address. Shows a Wi-Fi hint when [url] is null.
 */
@Composable
fun RemotePairCard(url: String?, pin: String?, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  var issued by remember(url, pin) { mutableStateOf(url to pin) }
  val displayedUrl = issued.first
  val displayedPin = issued.second
  var livePin by remember(displayedPin) {
    mutableStateOf(RemotePairing.displayPin(displayedPin)?.value)
  }
  LaunchedEffect(owner, displayedPin) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      try {
        RemotePairing.pinChanges.collectLatest {
          val active = RemotePairing.displayPin(displayedPin)
          livePin = active?.value
          if (active != null) {
            delay((active.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            livePin = null
          }
        }
      } finally {
        livePin = null
      }
    }
  }
  fun photoAccessGranted() = listOf(Manifest.permission.READ_EXTERNAL_STORAGE,
      Manifest.permission.WRITE_EXTERNAL_STORAGE).all {
    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
  }
  var photoAccess by remember { mutableStateOf(photoAccessGranted()) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    photoAccess = photoAccessGranted()
  }
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth()) {
    if (displayedUrl == null) {
      Column(Modifier.padding(35.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Connect your Portal to Wi-Fi to use the remote.", color = IosSecondary, fontSize = 24.sp)
        TextButton(onClick = { issued = enableRemoteAndMintPin(context) },
            modifier = Modifier.heightIn(min = 68.dp)) {
          Text("Check connection", color = IosBlue, fontSize = 22.sp)
        }
      }
    } else {
      Row(
          modifier = Modifier.fillMaxWidth().padding(35.dp),
          horizontalArrangement = Arrangement.spacedBy(50.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
          Text("Control from your phone", color = IosLabel, fontSize = 28.sp,
              fontWeight = FontWeight.Medium)
          Text("Scan with your phone camera, or open the address below on the same Wi-Fi.",
              color = IosSecondary, fontSize = 24.sp, lineHeight = 36.sp)
          Text(displayedUrl, color = IosBlue, fontSize = 24.sp, lineHeight = 36.sp)
          if (livePin != null) {
            Text("This code works once and expires after 5 minutes.",
                color = IosSecondary, fontSize = 22.sp, lineHeight = 32.sp)
          }
          TextButton(onClick = { issued = enableRemoteAndMintPin(context) },
              modifier = Modifier.heightIn(min = 68.dp)) {
            Text("Generate new code", color = IosBlue, fontSize = 22.sp)
          }
          if (!photoAccess) {
            Text("To import photos from your phone, allow file access on this Portal.",
                color = IosSecondary, fontSize = 20.sp, lineHeight = 28.sp)
            TextButton(onClick = {
              permission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                  Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }) { Text("Allow photo imports", color = IosBlue, fontSize = 22.sp) }
          }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
          val current = livePin
          if (current != null) {
            val pairUrl = remember(displayedUrl, current) { "$displayedUrl#pin=$current" }
            // A replaced code gets a fresh bitmap state; never show an old QR beside new digits.
            key(pairUrl) {
              val qr by produceState<android.graphics.Bitmap?>(null, pairUrl) {
                value = withContext(Dispatchers.Default) { lanSetupQr(pairUrl, 400) }
              }
              qr?.let { image ->
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 14.dp)) {
                  Image(bitmap = image.asImageBitmap(), contentDescription = "Remote pairing QR code",
                      modifier = Modifier.padding(12.dp).size(200.dp))
                }
              }
            }
            Text(current, color = IosLabel, fontSize = 90.sp, lineHeight = 110.sp,
                letterSpacing = 8.sp, fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(top = 18.dp))
          } else {
            Text("Code no longer valid", color = IosLabel, fontSize = 32.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.Medium)
            Text("Generate a new code to connect a phone.", color = IosSecondary,
                fontSize = 22.sp, lineHeight = 32.sp, modifier = Modifier.padding(top = 18.dp))
          }
        }
      }
    }
  }
}

/**
 * Compact, centred "Done" for the pairing surfaces (modal + [RemotePairActivity]). A wrap-content
 * pill rather than a full-width bar, which looked heavy in the dialog. Ring focus for the remote.
 */
@Composable
fun PairDoneButton(modifier: Modifier = Modifier, onDone: () -> Unit) {
  Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Surface(
        color = IosBlue,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.heightIn(min = 68.dp).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) { onDone() },
    ) {
      Text(
          "Done",
          color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
          fontSize = 24.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 44.dp, vertical = 18.dp),
      )
    }
  }
}
