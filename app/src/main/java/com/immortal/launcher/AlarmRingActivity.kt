/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Full-screen ringing screen shown by [AlarmReceiver] at the alarm time. Displays a live
 * clock and the alarm label over the lock screen, with big Dismiss and Snooze targets.
 */
class AlarmRingActivity : ComponentActivity() {
  companion object {
    const val EXTRA_LABEL = "alarm_label"
    private const val SNOOZE_MIN = 9
  }
  private var preview = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    preview = intent.getBooleanExtra("tools_preview", false)
    if (preview) applyToolsDisplayBars(this)
    // Show over the lock screen and wake the device.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    window.addFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)

    val label = intent.getStringExtra(EXTRA_LABEL) ?: "Alarm"
    val alarm = AlarmConfig.load(this)
    val previewTime = String.format(Locale.getDefault(), "%02d:%02d", alarm.hour, alarm.minute)
    setContent {
      PortalPrimeTheme(darkTheme = true) {
        CompositionLocalProvider(LocalApprovedPalette provides ApprovedToolsPalette) {
          AlarmRingScreen(label, preview, previewTime, onDismiss = ::dismiss, onSnooze = ::snooze)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (preview) applyToolsDisplayBars(this)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (preview && hasFocus) applyToolsDisplayBars(this)
  }

  private fun dismiss() {
    if (!preview) { AlarmSound.stop(this); AlarmReceiver.clearNotification(this) }
    finish()
  }

  private fun snooze() {
    if (preview) { finish(); return }
    AlarmSound.stop(this)
    AlarmReceiver.clearNotification(this)
    val at = System.currentTimeMillis() + SNOOZE_MIN * 60_000L
    val intent = Intent(this, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_SNOOZE)
    val pi =
        PendingIntent.getBroadcast(
            this, AlarmScheduler.SNOOZE_RC, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val am = getSystemService(AlarmManager::class.java)
    runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        .onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } }
    finish()
  }

  // Dismiss/Snooze own ringing cleanup. Activity recreation must preserve the
  // active alert; AlarmSound also has its independent two-minute auto-silence.
}

@Composable
private fun AlarmRingScreen(label: String, preview: Boolean, previewTime: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
  BackHandler(onBack = onDismiss)
  var now by remember { mutableStateOf(Date()) }
  LaunchedEffect(preview) {
    while (!preview) {
      now = Date()
      delay(1000)
    }
  }
  val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

  Box(
      modifier = Modifier.fillMaxSize().background(ApprovedToolsPalette.background),
      contentAlignment = Alignment.Center,
  ) {
    Column(Modifier.fillMaxSize()) {
    ToolsDisplayHeader("‹ Alarm", if (preview) "Alarm preview" else label, onDismiss)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().weight(1f).padding(32.dp),
    ) {
      Text(
          text = if (preview) previewTime else timeFmt.format(now),
          color = Color.White,
          fontSize = 112.sp,
          fontWeight = FontWeight.Medium,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(16.dp))
      Text(
          text = if (preview) "Your alarm is ringing" else label,
          color = Color(0xCCFFFFFF),
          fontSize = 30.sp,
          textAlign = TextAlign.Center,
      )
      if (preview) ToolsText("Preview only · no sound or real alarm scheduled", true, Modifier.padding(top = 20.dp))
      Spacer(Modifier.height(40.dp))
      androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ToolsButton("Dismiss", primary = true, onClick = onDismiss)
        ToolsButton("Snooze 9 minutes", onClick = onSnooze)
      }
    }
    }
  }
}
