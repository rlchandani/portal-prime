/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
private fun HeaderBar(onScreensaver: () -> Unit) {
  var now by remember { mutableStateOf(Date()) }
  androidx.compose.runtime.LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  val context = androidx.compose.ui.platform.LocalContext.current
  // The unit preference is re-read on resume and keys the fetch loop, so flipping
  // °F/°C in Immortal Settings updates the header the moment the user returns.
  var weatherUnit by remember { mutableStateOf(ImmortalSettings.load(context).weatherUnit) }
  // The clock format is likewise re-read on resume so flipping Auto/12h/24h in
  // Immortal Settings updates the header the moment the user returns home.
  var use24Hour by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  // The "hey" button only appears when Millennium is installed; re-checked on
  // resume so it shows up the moment the user finishes provisioning without
  // needing to relaunch the launcher.
  var heyPkg by remember { mutableStateOf(heyPackage(context)) }
  // The header mini-player toggle is re-read on resume so flipping it in Immortal
  // Settings shows/hides the player the moment the user returns home.
  var showMiniPlayer by remember { mutableStateOf(ImmortalSettings.load(context).showMiniPlayer) }
  // Live now-playing from the device's media session. The hub notifies off-main, so
  // hop back to the main thread before touching Compose state.
  var nowPlaying by remember { mutableStateOf(NowPlayingHub.current) }
  val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> mainHandler.post { nowPlaying = s } }
    NowPlayingHub.addListener(l) // replays current immediately
    onDispose { NowPlayingHub.removeListener(l) }
  }
  OnResume {
        weatherUnit = ImmortalSettings.load(context).weatherUnit
        use24Hour = ImmortalSettings.use24HourClock(context)
        heyPkg = heyPackage(context)
        showMiniPlayer = ImmortalSettings.load(context).showMiniPlayer
  }
  val weather by produceState(initialValue = "", weatherUnit) {
    // Retry soon on failure (e.g. a transient geolocation rate-limit), then
    // refresh periodically. Location is cached after the first success.
    while (true) {
      val w = withContext(Dispatchers.IO) { Weather.fetch(context) }
      if (w.isNotBlank()) {
        value = w
        delay(30L * 60 * 1000) // refresh every 30 min
      } else {
        delay(60L * 1000) // retry in 1 min
      }
    }
  }
  val battery = batteryState()

  // Quick-connect: the remote header button enables the remote and pops a QR/PIN modal.
  var showPair by remember { mutableStateOf(false) }
  var pairUrl by remember { mutableStateOf<String?>(null) }
  var pairPin by remember { mutableStateOf<String?>(null) }

  // Layout: the big clock anchors the left; the weather and date stack on the
  // right, right-aligned, so the header reads as a balanced pair of blocks. The
  // clock and the right-hand stack are centred against each other.
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // Clock anchors the top-left corner; the action buttons sit just to its right
    // (now that there's a group of them, leading with the buttons looked off).
    Text(
        SimpleDateFormat(if (use24Hour) "H:mm" else "h:mm", Locale.getDefault()).format(now),
        color = Color.White,
        fontSize = 72.sp,
        fontWeight = FontWeight.Light,
        lineHeight = 72.sp,
    )
    Spacer(Modifier.size(28.dp))
    // Screensaver entry — the stock launcher's stacked-photo icon so the affordance
    // reads the same as the Portal users already know.
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              onScreensaver()
            },
    ) {
      Box(contentAlignment = Alignment.Center) { StackedPhotoIcon() }
    }
    // Remote — one-tap "control from your phone": turn the remote on (if off) and show a
    // QR/PIN modal, so a phone pairs without digging into Settings.
    Spacer(Modifier.size(14.dp))
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier =
            Modifier.size(56.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
              val (u, p) = enableRemoteAndMintPin(context)
              pairUrl = u
              pairPin = p
              showPair = true
            },
    ) {
      Box(contentAlignment = Alignment.Center) { RemoteGlyph() }
    }
    // "Hey" button — push-to-talk for the active assistant. The launcher stays
    // dumb: it just broadcasts the trigger; Millennium owns assistant selection,
    // the premium gate and the falcon mic handoff. Only shown when Millennium is
    // installed (so a bare launcher has no dead button).
    heyPkg?.let { pkg ->
      Spacer(Modifier.size(14.dp))
      Surface(
          color = Color(0x33FFFFFF),
          shape = androidx.compose.foundation.shape.CircleShape,
          modifier =
              Modifier.size(56.dp).tvFocusable(
                  shape = androidx.compose.foundation.shape.CircleShape,
                  onLongClick = { openHeyPicker(context, pkg) },
              ) {
                fireHey(context, pkg)
              },
      ) {
        Box(contentAlignment = Alignment.Center) { MicGlyph() }
      }
    }
    // Mini-player — sits in the left action cluster, only while something's playing.
    // When shown it takes the flexible space (so its text uses the header width before
    // it scrolls); otherwise a weight spacer pushes the weather/date to the right.
    val np = nowPlaying
    if (showMiniPlayer && np != null && np.active && np.source != SOURCE_YOUTUBE) {
      Spacer(Modifier.size(16.dp))
      MiniPlayer(np, modifier = Modifier.weight(1f).padding(end = 16.dp)) {
        context.startActivity(Intent(context, HomeActivity::class.java).putExtra(APPROVED_HOME_MUSIC, true))
      }
    } else {
      Spacer(Modifier.weight(1f))
    }
    Column(horizontalAlignment = Alignment.End) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        // Charge level is OPTIONAL — only Portal Go has a battery; mains-powered
        // Portals report no battery present, so we render nothing for them.
        if (battery.present) {
          BatteryIndicator(percent = battery.percent, charging = battery.charging)
        }
        if (weather.isNotBlank()) {
          Text(weather, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
        }
      }
      Text(
          DateFormatter.format(now, "EEEEMMMMd"),
          color = Color(0xFFAAAAAA),
          fontSize = 20.sp,
          fontWeight = FontWeight.Normal,
          letterSpacing = 0.5.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
    }
  }

  if (showPair) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { showPair = false }) {
      com.immortal.launcher.ui.components.DarkDialogSurface {
        Column(modifier = Modifier.padding(20.dp)) {
          Text("Control from your phone", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
          Text(
              "Scan with a phone on the same Wi-Fi, or open the address and enter the code.",
              color = Color(0xFF9A9A9A),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
          )
          RemotePairCard(pairUrl, pairPin)
          Spacer(Modifier.size(16.dp))
          PairDoneButton { showPair = false }
        }
      }
    }
  }
}

/** White line-art phone glyph for the header "remote" (control from your phone) button. */
@Composable
private fun RemoteGlyph() {
  Canvas(modifier = Modifier.size(28.dp)) {
    val w = size.minDimension
    val s = w * 0.08f
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Phone body (portrait rounded rect).
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.34f, w * 0.12f),
        size = Size(w * 0.32f, w * 0.66f),
        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        style = stroke,
    )
    // Speaker slit near the top, home dot near the bottom.
    drawLine(Color.White, Offset(w * 0.44f, w * 0.22f), Offset(w * 0.56f, w * 0.22f), strokeWidth = s)
    drawCircle(color = Color.White, radius = w * 0.045f, center = Offset(w * 0.5f, w * 0.67f))
  }
}

/** White line-art photo glyph (single frame), matching the stock screensaver. */
@Composable
private fun StackedPhotoIcon() {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    val s = w * 0.075f // stroke width
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Photo frame outline.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.16f, w * 0.20f),
        size = Size(w * 0.68f, w * 0.60f),
        cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        style = stroke,
    )
    // Sun.
    drawCircle(
        color = Color.White,
        radius = w * 0.075f,
        center = Offset(w * 0.36f, w * 0.40f),
        style = Stroke(width = s),
    )
    // Mountains.
    val path =
        androidx.compose.ui.graphics.Path().apply {
          moveTo(w * 0.20f, w * 0.72f)
          lineTo(w * 0.40f, w * 0.48f)
          lineTo(w * 0.52f, w * 0.60f)
          lineTo(w * 0.62f, w * 0.50f)
          lineTo(w * 0.80f, w * 0.72f)
        }
    drawPath(path, color = Color.White, style = stroke)
  }
}

/** Bottom-right Manage / Done toggle. */
@Composable
private fun EditButton(editMode: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = if (editMode) Color(0xFFE53935) else Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(if (editMode) "✓" else "✎", color = Color.White, fontSize = 28.sp)
    }
  }
}

/** Edit-mode "clean up" button — re-packs every tile into a tidy ordered grid. */
@Composable
private fun TidyButton(onClick: () -> Unit) {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Surface(
      color = Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier =
          Modifier.size(60.dp).tvFocusable(androidx.compose.foundation.shape.CircleShape) {
            onClick()
          },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Canvas(Modifier.size(26.dp)) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
      }
    }
  }
}

@Composable
internal fun ApprovedHeaderButton(icon: String, label: String, size: Dp = 68.dp, onClick: () -> Unit) =
    ApprovedHeaderButton(icon, label, lit = false, size = size, onClick = onClick)

/** [lit]: inverted fill (the music button while a song is loaded). [size]: 56 dp in the compact tier. */
@Composable
internal fun ApprovedHeaderButton(icon: String, label: String, lit: Boolean, size: Dp = 68.dp, onClick: () -> Unit) {
  Box(Modifier.size(size).clip(CircleShape)
      .background(if (lit) ApprovedHomePalette.Text else ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
      .semantics { contentDescription = label }
      .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, onClick = onClick),
      contentAlignment = Alignment.Center) {
    ApprovedHomeIcon(icon, tint = if (lit) ApprovedHomePalette.Background else ApprovedHomePalette.Text)
  }
}
