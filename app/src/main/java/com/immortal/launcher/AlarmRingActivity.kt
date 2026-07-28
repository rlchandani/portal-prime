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
    private const val SNOOZE_RC = 0x5A1D
    private const val SNOOZE_MIN = 9
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
    setContent {
      PortalPrimeTheme(darkTheme = true) {
        AlarmRingScreen(label = label, onDismiss = ::dismiss, onSnooze = ::snooze)
      }
    }
  }

  private fun dismiss() {
    AlarmSound.stop(this)
    finish()
  }

  private fun snooze() {
    AlarmSound.stop(this)
    val at = System.currentTimeMillis() + SNOOZE_MIN * 60_000L
    val intent = Intent(this, AlarmReceiver::class.java).setAction(AlarmScheduler.ACTION_FIRE)
    val pi =
        PendingIntent.getBroadcast(
            this, SNOOZE_RC, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val am = getSystemService(AlarmManager::class.java)
    runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        .onFailure { runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } }
    finish()
  }

  override fun onDestroy() {
    super.onDestroy()
    // Safety net: ensure the sound is stopped if the screen goes away by any path.
    AlarmSound.stop(this)
  }
}

@Composable
private fun AlarmRingScreen(label: String, onDismiss: () -> Unit, onSnooze: () -> Unit) {
  var now by remember { mutableStateOf(Date()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

  Box(
      modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0F)),
      contentAlignment = Alignment.Center,
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
      Text(
          text = timeFmt.format(now),
          color = Color.White,
          fontSize = 72.sp,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(16.dp))
      Text(
          text = label,
          color = Color(0xCCFFFFFF),
          fontSize = 22.sp,
          textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(48.dp))
      Button(
          onClick = onDismiss,
          modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).height(72.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
          contentPadding = PaddingValues(vertical = 16.dp),
      ) {
        Text("Dismiss", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
      }
      Spacer(Modifier.height(16.dp))
      Button(
          onClick = onSnooze,
          modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).height(64.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A33)),
          contentPadding = PaddingValues(vertical = 14.dp),
      ) {
        Text("Snooze 9 min", color = Color.White, fontSize = 20.sp)
      }
    }
  }
}
