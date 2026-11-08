/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.ApprovedLightPalette
import com.immortal.launcher.youtube.YouTubeMusicLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SUCCESS_HOLD_MS = 2_500L
private const val CLOCK_TICK_MS = 1_000L

/**
 * QR sign-in (OAuth device flow). Starts a flow on entry, cancels it on exit; "Get a new code"
 * cancels and restarts. On success shows "Signed in as …" briefly, then [onSignedIn].
 */
@Composable
internal fun MusicV3SignIn(library: YouTubeMusicLibrary, adding: Boolean, onSignedIn: () -> Unit) {
  val scope = rememberCoroutineScope()
  val job = remember { mutableStateOf<Job?>(null) }
  val restart = {
    val old = job.value
    job.value = scope.launch { old?.cancelAndJoin(); library.resetSignIn(); library.signIn() }
  }
  LaunchedEffect(Unit) { restart() }
  DisposableEffect(Unit) { onDispose { job.value?.cancel() } }
  val state by library.signInState.collectAsState()
  val now by produceState(System.currentTimeMillis()) {
    while (true) { delay(CLOCK_TICK_MS); value = System.currentTimeMillis() }
  }
  val view = MusicV3Rules.signInView(state, now)
  if (view is MusicV3SignInView.Success) {
    LaunchedEffect(view) { delay(SUCCESS_HOLD_MS); library.resetSignIn(); onSignedIn() }
    SignedInCard(view.name)
    return
  }
  if (view is MusicV3SignInView.NotConfigured) { MusicV3NotConfigured(); return }
  SignInCard(view, adding, restart)
}

@Composable
private fun SignInCard(view: MusicV3SignInView, adding: Boolean, onNewCode: () -> Unit) {
  Row(Modifier.fillMaxSize().clip(RoundedCornerShape(MusicV3Dims.PaneRadius)).background(ApprovedHomePalette.Card)
      .padding(horizontal = 96.dp, vertical = 64.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(88.dp)) {
    QrCard(view)
    Column(Modifier.weight(1f)) {
      Text(if (adding) "Add another account" else "Sign in with your phone", color = ApprovedHomePalette.Text,
          fontSize = 54.sp, lineHeight = 64.sp, fontWeight = FontWeight.Medium)
      Text(if (adding) "Each person gets their own playlists and liked songs here."
          else "Play your YouTube playlists and liked songs on this Portal.",
          color = ApprovedHomePalette.Secondary, fontSize = 26.sp, lineHeight = 36.sp, modifier = Modifier.padding(top = 14.dp))
      Steps(view)
      SignInStatus(view, onNewCode)
      Row(Modifier.padding(top = 36.dp), verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ApprovedIcon("lock", Modifier.size(26.dp), ApprovedHomePalette.Secondary)
        Text("Portal can only look at your library. It can't post, like or change anything.",
            color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
      }
    }
  }
}

@Composable
private fun Steps(view: MusicV3SignInView) {
  val code = view as? MusicV3SignInView.Code
  Column(Modifier.padding(top = 44.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
    Step(1, "Scan the code with your phone", "Or open ${code?.shortUrl ?: "google.com/device"} in any browser")
    Step(2, "Enter this code", null) {
      val text = code?.userCode ?: "––––-––––"
      Text(text, color = ApprovedHomePalette.Text, fontSize = 76.sp, lineHeight = 88.sp, fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace, letterSpacing = 6.sp,
          modifier = Modifier.alpha(if (code != null) 1f else 0.5f)
              .semantics { contentDescription = "Code " + text.toList().joinToString(" ") })
    }
    Step(3, "Choose your account", "and allow Portal to see your YouTube library")
  }
}

@Composable
private fun Step(n: Int, title: String, detail: String?, extra: @Composable () -> Unit = {}) {
  Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
    Box(Modifier.size(58.dp).clip(RoundedCornerShape(29.dp)).approvedOutline(RoundedCornerShape(29.dp)),
        contentAlignment = Alignment.Center) { Text("$n", color = ApprovedHomePalette.Secondary, fontSize = 26.sp) }
    Column {
      Text(title, color = ApprovedHomePalette.Text, fontSize = 30.sp, lineHeight = 40.sp)
      detail?.let { Text(it, color = ApprovedHomePalette.Secondary, fontSize = 23.sp) }
      extra()
    }
  }
}

@Composable
private fun SignInStatus(view: MusicV3SignInView, onNewCode: () -> Unit) {
  Row(Modifier.padding(top = 44.dp).widthIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    when (view) {
      is MusicV3SignInView.Code -> {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(ApprovedHomePalette.Accent))
        Text("Waiting for your phone…", color = ApprovedHomePalette.Text, fontSize = 25.sp)
        Text("Code works for ${view.remaining}", color = ApprovedHomePalette.Secondary, fontSize = 25.sp)
        MusicV3Button("Get a new code", style = MusicV3ButtonStyle.GHOST, onClick = onNewCode)
      }
      MusicV3SignInView.Starting -> Text("Getting a code…", color = ApprovedHomePalette.Text, fontSize = 25.sp)
      else -> {
        val (message, action) = problemCopy(view)
        Text(message, color = ApprovedHomePalette.Text, fontSize = 25.sp, modifier = Modifier.weight(1f, fill = false))
        MusicV3Button(action, style = MusicV3ButtonStyle.SOLID, onClick = onNewCode)
      }
    }
  }
}

private fun problemCopy(view: MusicV3SignInView): Pair<String, String> = when (view) {
  MusicV3SignInView.Expired -> "Codes last 15 minutes." to "Get a new code"
  MusicV3SignInView.Denied -> "Sign-in was cancelled on the phone. Nothing was shared." to "Try again"
  MusicV3SignInView.Offline -> "This Portal is offline. Connect to Wi-Fi, then try again." to "Try again"
  else -> "Google didn't give a code just now. Try again in a minute." to "Try again"
}

/** Over-QR message for every state but a live code (copy from the mock). */
private fun qrOverlay(view: MusicV3SignInView): Triple<String, String, String>? = when (view) {
  is MusicV3SignInView.Code, MusicV3SignInView.Starting -> null
  MusicV3SignInView.Expired -> Triple("clock", "This code has expired", "Get a new one to keep going")
  MusicV3SignInView.Offline -> Triple("wifi", "No connection", "A code appears once you're online")
  MusicV3SignInView.Denied -> Triple("close", "Cancelled on the phone", "Nothing was shared")
  else -> Triple("info", "Something went wrong", "Nothing was shared")
}

/** The QR always sits on a white card (the day palette's surface) so phones can read it. */
@Composable
private fun QrCard(view: MusicV3SignInView) {
  val url = (view as? MusicV3SignInView.Code)?.qrText
  val px = with(LocalDensity.current) { 560.dp.roundToPx() }
  val qr by produceState<Bitmap?>(null, url) { value = url?.let { withContext(Dispatchers.Default) { lanSetupQr(it, px) } } }
  val shape = RoundedCornerShape(36.dp)
  Box(Modifier.size(640.dp).clip(shape).background(ApprovedLightPalette.surface).approvedOutline(shape),
      contentAlignment = Alignment.Center) {
    val bitmap = qr
    val overlay = qrOverlay(view)
    if (bitmap != null && url != null) {
      Image(bitmap.asImageBitmap(), contentDescription = "QR code for $url", modifier = Modifier.size(560.dp)
          .alpha(if (overlay == null) 1f else 0.08f))
    } else if (overlay == null) {
      CircularProgressIndicator(color = ApprovedLightPalette.accent, modifier = Modifier.size(64.dp))
    }
    overlay?.let { (icon, title, detail) ->
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ApprovedIcon(icon, Modifier.size(72.dp), ApprovedLightPalette.secondary)
        Text(title, color = ApprovedLightPalette.text, fontSize = 32.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 14.dp))
        Text(detail, color = ApprovedLightPalette.secondary, fontSize = 23.sp, modifier = Modifier.padding(top = 6.dp))
      }
    }
  }
}

@Composable
private fun SignedInCard(name: String) {
  MusicV3CenterCard {
    MusicV3Avatar(name, null, 160.dp)
    Text("Signed in as $name", color = ApprovedHomePalette.Text, fontSize = 54.sp, lineHeight = 64.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 36.dp))
    Row(Modifier.padding(top = 36.dp), verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(color = ApprovedHomePalette.Accent, modifier = Modifier.size(30.dp), strokeWidth = 3.5.dp)
      Text("Getting your playlists…", color = ApprovedHomePalette.Secondary, fontSize = 26.sp,
          modifier = Modifier.padding(start = 14.dp))
    }
  }
}

/** No OAuth client in this build: say so plainly, with a hint for whoever set up the Portal. */
@Composable
internal fun MusicV3NotConfigured() {
  MusicV3CenterCard {
    ApprovedIcon("music", Modifier.size(96.dp), ApprovedHomePalette.Secondary)
    Text("Music sign-in isn't set up on this Portal yet.", color = ApprovedHomePalette.Text, fontSize = 44.sp,
        lineHeight = 54.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 36.dp))
    Text("For the household admin: add a Google sign-in client for TVs to this Portal's build, then reinstall.",
        color = ApprovedHomePalette.Secondary, fontSize = 26.sp, lineHeight = 36.sp, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp).widthIn(max = 900.dp))
  }
}

/** After the last sign-out. */
@Composable
internal fun MusicV3NoOne(onSignIn: () -> Unit) {
  MusicV3CenterCard {
    ApprovedIcon("music", Modifier.size(96.dp), ApprovedHomePalette.Secondary)
    Text("No one is signed in", color = ApprovedHomePalette.Text, fontSize = 54.sp, lineHeight = 64.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 36.dp))
    Text("Sign in with your phone to play your playlists and liked songs here.", color = ApprovedHomePalette.Secondary,
        fontSize = 26.sp, lineHeight = 36.sp, modifier = Modifier.padding(top = 10.dp))
    MusicV3Button("Sign in", Modifier.padding(top = 40.dp), style = MusicV3ButtonStyle.SOLID, onClick = onSignIn)
  }
}

@Composable
private fun MusicV3CenterCard(content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().clip(RoundedCornerShape(MusicV3Dims.PaneRadius)).background(ApprovedHomePalette.Card)
      .padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    content()
  }
}
