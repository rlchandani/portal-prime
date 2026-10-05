/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** The approved app destinations use the same bounded 104 dp header / 92 dp navigation shell. */
@Composable
internal fun ApprovedAppsShell(
    selected: String,
    onExit: () -> Unit,
    onSearch: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
  KeepInteractiveScreenAwake()
  val context = LocalContext.current
  ApprovedAppsImmersive()
  Column(Modifier.fillMaxSize().background(IosBackground)) {
    ApprovedAppsHeader(onSearch ?: {
      context.startActivity(Intent(context, AppSwitcherActivity::class.java)
          .putExtra(AppSwitcherActivity.EXTRA_ALL_APPS, true).putExtra("approved_search", true))
    })
    Box(Modifier.weight(1f).fillMaxWidth(), content = content)
    Spacer(Modifier.height(24.dp))
    ApprovedAppsDock(selected, onExit, Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(32.dp))
  }
}

@Composable
internal fun ApprovedAppsHeader(onSearch: () -> Unit) {
  val context = LocalContext.current
  val resume = rememberApprovedResume()
  val connected = remember(resume) {
    runCatching {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrNull() ?: runCatching {
      @Suppress("DEPRECATION")
      val info = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
      info.supplicantState == android.net.wifi.SupplicantState.COMPLETED && info.ipAddress != 0
    }.getOrDefault(false)
  }
  Column {
    Row(Modifier.fillMaxWidth().height(103.dp).padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(25.dp, 32.dp).border(2.dp, IosLabel, RoundedCornerShape(topStart = 15.dp, topEnd = 15.dp, bottomEnd = 11.dp, bottomStart = 11.dp)))
      Spacer(Modifier.width(18.dp))
      Text("immortal", color = IosLabel, fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp)
      Spacer(Modifier.width(30.dp))
      Box(Modifier.width(1.dp).height(40.dp).background(IosDivider))
      Text(FleetConfig.name(context), color = IosSecondary, fontSize = 22.sp, modifier = Modifier.padding(start = 24.dp))
      Spacer(Modifier.weight(1f))
      ApprovedAppsGlyph("wifi", IosSecondary, 22.dp)
      Text(if (connected) "Connected" else "Offline", color = IosSecondary, fontSize = 20.sp, modifier = Modifier.padding(start = 10.dp, end = 30.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ApprovedAppsRoundAction("phone", "Connect your phone") { context.startActivity(Intent(context, RemotePairActivity::class.java)) }
        ApprovedAppsRoundAction("moon", "Sleep screen") { ScreenControl.sleep(context) }
        ApprovedAppsRoundAction("search", "Search apps", onSearch)
        ApprovedAppsRoundAction("gear", "Settings") { context.startActivity(Intent(context, ImmortalSettingsActivity::class.java)) }
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(IosDivider))
  }
}

@Composable
private fun ApprovedAppsRoundAction(glyph: String, label: String, onClick: () -> Unit) {
  Box(Modifier.size(68.dp).clip(CircleShape).background(LocalApprovedPalette.current.surface2)
      .tvFocusable(CircleShape, focusScale = 1f, onClick = onClick), contentAlignment = Alignment.Center) {
    ApprovedAppsGlyph(glyph, IosLabel, 30.dp, label)
  }
}

@Composable
internal fun ApprovedAppsDock(selected: String, onExit: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  Surface(modifier = modifier, color = IosCard, shape = RoundedCornerShape(26.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider)) {
    Row(Modifier.height(92.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf(Triple("home", "Home", "home"), Triple("apps", "Apps", "gridapps"), Triple("photos", "Photos", "photo"), Triple("tools", "Tools", "tools")).forEach { (id, label, glyph) ->
        val active = id == selected
        Row(Modifier.widthIn(min = 176.dp).height(74.dp).clip(RoundedCornerShape(18.dp))
            .background(if (active) LocalApprovedPalette.current.selected else Color.Transparent)
            .tvFocusable(RoundedCornerShape(18.dp), focusScale = 1f) {
              if (!active) when (id) {
                "home" -> { context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); onExit() }
                "apps" -> { context.startActivity(Intent(context, AppSwitcherActivity::class.java).putExtra("approved_all_apps", true)); onExit() }
                "photos" -> context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
                "tools" -> { context.startActivity(Intent(context, ToolsActivity::class.java)); onExit() }
              }
            }.padding(horizontal = 25.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
          val color = if (active) IosBlue else IosSecondary
          ApprovedAppsGlyph(glyph, color, 30.dp)
          Text(label, color = color, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}

@Composable
internal fun ApprovedAppsButton(label: String, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val shape = RoundedCornerShape(16.dp)
  Box(modifier.heightIn(min = 68.dp).clip(shape)
      .background(if (primary) IosBlue else LocalApprovedPalette.current.surface2)
      .then(if (enabled) Modifier.tvFocusable(shape, focusScale = 1f, onClick = onClick) else Modifier)
      .padding(horizontal = 26.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
    Text(label, color = (if (primary) MaterialTheme.colorScheme.onPrimary else IosLabel).copy(alpha = if (enabled) 1f else 0.5f), fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
internal fun ApprovedAppsHeading(title: String, subtitle: String? = null) {
  Column(Modifier.padding(bottom = 34.dp)) {
    Text(title, color = IosLabel, fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
    if (!subtitle.isNullOrBlank()) Text(subtitle, color = IosSecondary, fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 12.dp))
  }
}

@Composable
internal fun ApprovedAppsSearch(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
  OutlinedTextField(value = query, onValueChange = onQuery, singleLine = true, textStyle = TextStyle(fontSize = 24.sp, color = IosLabel),
      placeholder = { Text(placeholder, fontSize = 24.sp, color = IosSecondary) },
      leadingIcon = { ApprovedAppsGlyph("search", IosSecondary, 28.dp) }, shape = RoundedCornerShape(16.dp), colors = iosTextFieldColors(), modifier = modifier.fillMaxWidth().heightIn(min = 68.dp))
}

/** Refresh install/app/presentation state when returning from an Android-owned screen. */
@Composable
internal fun rememberApprovedResume(owner: LifecycleOwner = LocalLifecycleOwner.current): Int {
  var revision by remember { mutableStateOf(0) }
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  return revision
}

@Composable
internal fun ApprovedAppsGlyph(name: String, color: Color, size: Dp, description: String? = null) {
  val modifier = if (description == null) Modifier.size(size)
      else Modifier.size(size).semantics { contentDescription = description }
  ApprovedIcon(name, modifier = modifier, tint = color)
}

internal fun approvedAppIdentity(pkg: String): Pair<String, Color>? = when (pkg) {
  "org.chromium.chrome", "org.mozilla.firefox" -> "globe" to Color(0xFF2879B8)
  "org.videolan.vlc" -> "vlc" to Color(0xFFCB7338)
  "org.schabi.newpipe" -> "film" to Color(0xFFC84E68)
  "io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal" -> "home" to Color(0xFF2879B8)
  "me.zhanghai.android.files" -> "folder" to Color(0xFF2879B8)
  "org.fdroid.fdroid" -> "bag" to Color(0xFF408763)
  "com.aurora.store" -> "bag" to Color(0xFF7661AA)
  "org.breezyweather" -> "weather" to Color(0xFF2879B8)
  "org.y20k.transistor" -> "radio" to Color(0xFF2879B8)
  "com.facebook.alohaapps.settings" -> "gear" to Color(0xFF647480)
  "com.android.quicksearchbox" -> "search" to Color(0xFF647480)
  "com.portal.assistant" -> "notes" to Color(0xFF7661AA)
  "com.portal.calendar" -> "cal" to Color(0xFFCB7338)
  "builtin:store" -> "bag" to Color(0xFF408763)
  "builtin:tools" -> "tools" to Color(0xFF647480)
  "builtin:music" -> "music" to Color(0xFFC84E68)
  "builtin:calls" -> "phone" to Color(0xFF408763)
  "builtin:settings" -> "gear" to Color(0xFF647480)
  else -> null
}

@Composable
internal fun ApprovedAppsIcon(pkg: String, label: String, size: Dp, bitmap: androidx.compose.ui.graphics.ImageBitmap? = null) {
  val identity = approvedAppIdentity(pkg)
  if (identity == null && bitmap != null) {
    androidx.compose.foundation.Image(bitmap, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
  } else {
    val (glyph, color) = identity ?: ("bag" to Color(0xFF2879B8))
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(color)
        .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
      ApprovedIcon(glyph, modifier = Modifier.size(size * .49f), tint = Color.White)
    }
  }
}

@Composable
internal fun rememberApprovedPackageInstalled(pkg: String, status: String? = null): State<Boolean?> {
  val context = LocalContext.current
  val resume = rememberApprovedResume()
  return produceState<Boolean?>(null, pkg, status, resume) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StoreCatalog.isInstalled(context, pkg) }
  }
}

/** Keep the 1920 × 1280 dp canvas aligned after permission and external-app handoffs. */
@Composable
private fun ApprovedAppsImmersive() {
  val context = LocalContext.current
  val activity = context as? android.app.Activity ?: return
  val resume = rememberApprovedResume()
  val hidden = remember(resume) { ImmortalSettings.hideStatusBar(context) }
  DisposableEffect(activity, hidden) {
    val window = activity.window
    fun applyBars() {
      androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !hidden)
      androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
        if (hidden) hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    }
    applyBars()
    val observer = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) applyBars() }
    window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(observer)
    onDispose { if (window.decorView.viewTreeObserver.isAlive) window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(observer) }
  }
}
