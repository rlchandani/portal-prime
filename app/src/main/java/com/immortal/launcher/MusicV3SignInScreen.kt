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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
  // The clock is read only by the countdown text and the derived view below: a tick re-renders that
  // one line, and the card recomposes only when the code changes or expires (Mini: 1.254 regression).
  val clock = produceState(System.currentTimeMillis()) {
    while (true) { delay(CLOCK_TICK_MS); value = System.currentTimeMillis() }
  }
  val signIn = state
  val stillView = remember(signIn) { derivedStateOf { MusicV3Rules.stillSignInView(signIn, clock.value) } }
  val view = stillView.value
  val remaining = remember(signIn) { { MusicV3Rules.countdown(signIn, clock.value) } }
  if (view is MusicV3SignInView.Success) {
    LaunchedEffect(view) { delay(SUCCESS_HOLD_MS); library.resetSignIn(); onSignedIn() }
    SignedInCard(view.name)
    return
  }
  if (view is MusicV3SignInView.NotConfigured) { MusicV3NotConfigured(); return }
  SignInCard(view, remaining, adding, restart)
}

/** The only reader of the clock: one Text, recomposed each second. */
@Composable
private fun CountdownText(remaining: () -> String, fontSize: TextUnit, singleLine: Boolean = false) {
  Text("Code works for ${remaining()}", color = ApprovedHomePalette.Secondary, fontSize = fontSize,
      maxLines = if (singleLine) 1 else Int.MAX_VALUE, softWrap = !singleLine)
}

@Composable
private fun SignInCard(view: MusicV3SignInView, remaining: () -> String, adding: Boolean, onNewCode: () -> Unit) {
  val layoutClass = currentLayoutClass()
  if (layoutClass.compact) {
    CompactSignInCard(view, remaining, adding, layoutClass.compactPortrait, onNewCode)
    return
  }
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
      SignInStatus(view, remaining, onNewCode)
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
private fun Steps(view: MusicV3SignInView, codeSize: TextUnit = 76.sp, codeLine: TextUnit = 88.sp, top: Dp = 44.dp) {
  val code = view as? MusicV3SignInView.Code
  Column(Modifier.padding(top = top), verticalArrangement = Arrangement.spacedBy(30.dp)) {
    Step(1, "Scan the code with your phone", "Or open ${code?.shortUrl ?: "google.com/device"} in any browser")
    Step(2, "Enter this code", null) {
      val text = code?.userCode ?: "––––-––––"
      val codeText = @Composable { size: TextUnit, line: TextUnit ->
        Text(text, color = ApprovedHomePalette.Text, fontSize = size, lineHeight = line, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, letterSpacing = 6.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.alpha(if (code != null) 1f else 0.5f)
                .semantics { contentDescription = "Code " + text.toList().joinToString(" ") })
      }
      if (!currentLayoutClass().compact) codeText(codeSize, codeLine)
      else BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The code is one token: shrink it to the column (monospace ≈ 0.6 em + 6 sp tracking per glyph).
        val fit = (maxWidth.value / text.length.coerceAtLeast(1) - 6f) / 0.62f
        val size = minOf(codeSize.value, fit).coerceAtLeast(24f)
        codeText(size.sp, (size * 1.16f).sp)
      }
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
private fun SignInStatus(view: MusicV3SignInView, remaining: () -> String, onNewCode: () -> Unit) {
  Row(Modifier.padding(top = 44.dp).widthIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
    when (view) {
      is MusicV3SignInView.Code -> {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(ApprovedHomePalette.Accent))
        Text("Waiting for your phone…", color = ApprovedHomePalette.Text, fontSize = 25.sp)
        CountdownText(remaining, 25.sp)
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

/**
 * Compact sign-in. Portrait: the QR on top (sized to the width), the steps below, scrolling when the
 * window is short. Landscape: side by side with the QR sized to the available height.
 */
@Composable
private fun CompactSignInCard(view: MusicV3SignInView, remaining: () -> String, adding: Boolean, portrait: Boolean,
                              onNewCode: () -> Unit) {
  // [view] carries no countdown, so nothing here recomposes on a tick; [remaining] is read only by CountdownText.
  BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(MusicV3Dims.PaneRadius))
      .background(ApprovedHomePalette.Card)) {
    if (portrait) {
      val qr = minOf(maxWidth - COMPACT_PORTRAIT_PAD * 2, COMPACT_QR_MAX_PORTRAIT).coerceAtLeast(COMPACT_QR_MIN)
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
          .padding(horizontal = COMPACT_PORTRAIT_PAD, vertical = 36.dp),
          horizontalAlignment = Alignment.CenterHorizontally) {
        QrCard(view, boxSize = qr, imageSize = qr * QR_IMAGE_RATIO)
        CompactSignInCopy(view, remaining, adding, onNewCode, Modifier.fillMaxWidth().padding(top = 36.dp))
      }
    } else {
      val qr = minOf(maxHeight - COMPACT_LANDSCAPE_VPAD * 2, COMPACT_QR_MAX_LANDSCAPE).coerceAtLeast(COMPACT_QR_MIN)
      Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = COMPACT_LANDSCAPE_VPAD),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        QrCard(view, boxSize = qr, imageSize = qr * QR_IMAGE_RATIO)
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.CenterStart) {
          CompactSignInCopy(view, remaining, adding, onNewCode, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        }
      }
    }
  }
}

/** The compact title, steps, status and privacy note (whole words; buttons wrap to a new line). */
@Composable
private fun CompactSignInCopy(view: MusicV3SignInView, remaining: () -> String, adding: Boolean, onNewCode: () -> Unit,
                              modifier: Modifier) {
  Column(modifier) {
    Text(if (adding) "Add another account" else "Sign in with your phone", color = ApprovedHomePalette.Text,
        fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.Medium)
    Text(if (adding) "Each person gets their own playlists and liked songs here."
        else "Play your YouTube playlists and liked songs on this Portal.",
        color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 12.dp))
    Steps(view, codeSize = 60.sp, codeLine = 72.sp, top = 32.dp)
    CompactSignInStatus(view, remaining, onNewCode)
    Row(Modifier.padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ApprovedIcon("lock", Modifier.size(26.dp), ApprovedHomePalette.Secondary)
      Text("Portal can only look at your library. It can't post, like or change anything.",
          color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
    }
  }
}

/** [SignInStatus] for a narrow column: the same pieces in a wrapping row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactSignInStatus(view: MusicV3SignInView, remaining: () -> String, onNewCode: () -> Unit) {
  FlowRow(Modifier.padding(top = 32.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)) {
    when (view) {
      is MusicV3SignInView.Code -> {
        Row(Modifier.heightIn(min = MusicV3Dims.Touch), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(ApprovedHomePalette.Accent))
          Text("Waiting for your phone…", color = ApprovedHomePalette.Text, fontSize = 24.sp)
        }
        // The countdown ticks every second: a fixed-width box on its own layer, so a tick re-measures
        // and redraws only this text, never the card around it.
        Box(Modifier.heightIn(min = MusicV3Dims.Touch).width(COUNTDOWN_WIDTH).graphicsLayer { },
            contentAlignment = Alignment.CenterStart) {
          CountdownText(remaining, 24.sp, singleLine = true)
        }
        MusicV3Button("Get a new code", style = MusicV3ButtonStyle.GHOST, onClick = onNewCode)
      }
      MusicV3SignInView.Starting -> Text("Getting a code…", color = ApprovedHomePalette.Text, fontSize = 24.sp)
      else -> {
        val (message, action) = problemCopy(view)
        Text(message, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 32.sp)
        MusicV3Button(action, style = MusicV3ButtonStyle.SOLID, onClick = onNewCode)
      }
    }
  }
}

/** The QR is encoded once at this size (the Portal+ card's image). */
private val QR_SOURCE = 560.dp
/** "Code works for 29:59" at 24 sp, with room to spare. */
private val COUNTDOWN_WIDTH = 270.dp
private val COMPACT_PORTRAIT_PAD = 40.dp
private val COMPACT_LANDSCAPE_VPAD = 32.dp
private val COMPACT_QR_MAX_PORTRAIT = 440.dp
private val COMPACT_QR_MAX_LANDSCAPE = 560.dp
/** Below this a phone camera struggles to read the code. */
private val COMPACT_QR_MIN = 260.dp
/** The QR image inside its white card, as on the Portal+ (560 of 640 dp). */
private const val QR_IMAGE_RATIO = 0.875f

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
private fun QrCard(view: MusicV3SignInView, boxSize: Dp = 640.dp, imageSize: Dp = QR_SOURCE) {
  val url = (view as? MusicV3SignInView.Code)?.qrText
  // Encoded once per code at the Portal+ size: a compact card scales it (nearest-neighbour, crisp),
  // so a size change while the screen animates or rotates never re-encodes or blanks the code.
  val px = with(LocalDensity.current) { QR_SOURCE.roundToPx() }
  val qr by produceState<Bitmap?>(null, url) { value = url?.let { withContext(Dispatchers.Default) { lanSetupQr(it, px) } } }
  val shape = RoundedCornerShape(36.dp)
  Box(Modifier.size(boxSize).clip(shape).background(ApprovedLightPalette.surface).approvedOutline(shape),
      contentAlignment = Alignment.Center) {
    val bitmap = qr
    val overlay = qrOverlay(view)
    if (bitmap != null && url != null) {
      val image = remember(bitmap) { bitmap.asImageBitmap() }
      if (imageSize == QR_SOURCE) Image(image, contentDescription = "QR code for $url", modifier = Modifier.size(imageSize)
          .alpha(if (overlay == null) 1f else 0.08f))
      else Image(image, contentDescription = "QR code for $url", modifier = Modifier.size(imageSize)
          .alpha(if (overlay == null) 1f else 0.08f), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
    } else if (overlay == null) {
      CircularProgressIndicator(color = ApprovedLightPalette.accent, modifier = Modifier.size(64.dp))
    }
    overlay?.let { (icon, title, detail) ->
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ApprovedIcon(icon, Modifier.size(72.dp), ApprovedLightPalette.secondary)
        Text(title, color = ApprovedLightPalette.text, fontSize = 32.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        Text(detail, color = ApprovedLightPalette.secondary, fontSize = 23.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp))
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
